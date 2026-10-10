package io.lightine.renovo.internal.engine

import io.lightine.renovo.DownloadProgress
import io.lightine.renovo.FailureReason
import io.lightine.renovo.Network
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateState
import io.lightine.renovo.UpdateStep
import io.lightine.renovo.internal.engine.EngineEvent.CheckFailed
import io.lightine.renovo.internal.engine.EngineEvent.CheckNotModified
import io.lightine.renovo.internal.engine.EngineEvent.CheckStarted
import io.lightine.renovo.internal.engine.EngineEvent.CheckSucceeded
import io.lightine.renovo.internal.engine.EngineEvent.DeviceUnsupported
import io.lightine.renovo.internal.engine.EngineEvent.DownloadFailed
import io.lightine.renovo.internal.engine.EngineEvent.DownloadFinished
import io.lightine.renovo.internal.engine.EngineEvent.DownloadProgressed
import io.lightine.renovo.internal.engine.EngineEvent.DownloadWaitingForNetwork
import io.lightine.renovo.internal.engine.EngineEvent.InstallFinished
import io.lightine.renovo.internal.engine.EngineEvent.InstallIfReady
import io.lightine.renovo.internal.engine.EngineEvent.InstallNotAllowedNow
import io.lightine.renovo.internal.engine.EngineEvent.InstallStarted
import io.lightine.renovo.internal.engine.EngineEvent.PermissionGranted
import io.lightine.renovo.internal.engine.EngineEvent.PermissionNeeded
import io.lightine.renovo.internal.engine.EngineEvent.Restored
import io.lightine.renovo.internal.engine.EngineEvent.Retry
import io.lightine.renovo.internal.engine.EngineEvent.StartDownload
import io.lightine.renovo.internal.engine.EngineEvent.Submit
import io.lightine.renovo.internal.engine.EngineEvent.UseMobileDataOnce
import io.lightine.renovo.internal.engine.EngineEvent.VerificationFailed
import io.lightine.renovo.internal.engine.EngineEvent.VerificationPassed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of the requirements and the technical spec, one or more tests each. */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateEngineRulesTest {
    private fun found(
        info: UpdateInfo,
        etag: String? = null,
    ) = CheckSucceeded(info, etag)

    private val readyPath = listOf(found(OPTIONAL), DownloadFinished(11, PATH), VerificationPassed(11))

    // ---- FR-19 and AC-5: when an update is forced ----

    @Test
    fun `FR-19 a mandatory update is forced`() {
        val harness = EngineHarness(MANUAL)
        harness.send(found(FORCED))

        assertEquals(UpdateState.Required(FORCED, UpdateStep.WaitingForUser), harness.state)
    }

    @Test
    fun `FR-19 AC-5 an installed version below the minimum is forced even when not mandatory`() {
        // A device that skipped several releases: installed 5, latest 20, minimum 15.
        val latest = info(versionCode = 20, minSupportedVersionCode = 15)
        val harness = EngineHarness(MANUAL, installed = 5)
        harness.send(found(latest))

        assertEquals(UpdateState.Required(latest, UpdateStep.WaitingForUser), harness.state)
    }

    @Test
    fun `FR-19 an installed version at the minimum is not forced`() {
        val latest = info(versionCode = 20, minSupportedVersionCode = 15)
        val harness = EngineHarness(MANUAL, installed = 15)
        harness.send(found(latest))

        assertEquals(UpdateState.Available(latest), harness.state)
    }

    @Test
    fun `FR-19 a stored forced update blocks again right after a restart, before any check`() {
        val harness = EngineHarness(MANUAL, snapshot = Snapshot(latest = FORCED, lastInstalledVersionCode = INSTALLED))

        assertEquals(UpdateState.Required(FORCED, UpdateStep.WaitingForUser), harness.state)
    }

    // ---- FR-20: a forced update stays Required ----

    @Test
    fun `FR-20 a forced update stays Required through every step`() = runTest {
        val harness = EngineHarness(FORCED_WIFI)
        val states = mutableListOf<UpdateState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { harness.engine.state.toList(states) }

        harness.send(
            found(FORCED),
            StartDownload,
            DownloadProgressed(11, DownloadProgress(100, 1000)),
            DownloadWaitingForNetwork(11),
            UseMobileDataOnce,
            DownloadProgressed(11, DownloadProgress(600, 1000)),
            DownloadFinished(11, PATH),
            VerificationPassed(11),
            PermissionNeeded,
            PermissionGranted,
            InstallStarted,
            InstallFinished(InstallResult.Cancelled),
            Retry,
            InstallNotAllowedNow,
            InstallIfReady,
            InstallStarted,
        )

        val afterFirstRequired = states.dropWhile { it !is UpdateState.Required }
        assertTrue(afterFirstRequired.isNotEmpty())
        afterFirstRequired.forEach { assertTrue("Left Required: $it", it is UpdateState.Required) }
        val steps = afterFirstRequired.map { (it as UpdateState.Required).step }
        assertEquals(
            listOf(
                UpdateStep.WaitingForUser,
                UpdateStep.Downloading(DownloadProgress(0, 1000)),
                UpdateStep.Downloading(DownloadProgress(100, 1000)),
                UpdateStep.WaitingForNetwork(canUseMobileData = true),
                UpdateStep.Downloading(DownloadProgress(100, 1000)),
                UpdateStep.Downloading(DownloadProgress(600, 1000)),
                UpdateStep.Downloading(DownloadProgress(1000, 1000)),
                UpdateStep.WaitingForUser,
                UpdateStep.PermissionNeeded,
                UpdateStep.Installing,
                UpdateStep.Failed(FailureReason.Cancelled),
                UpdateStep.WaitingForUser,
                UpdateStep.Installing,
            ),
            steps,
        )
    }

    // ---- FR-6: automatic download ----

    @Test
    fun `FR-6 with autoDownload on a newer version downloads in the background`() {
        val harness = EngineHarness(AUTO)
        val effects = harness.send(found(OPTIONAL)).withoutPersist()

        assertEquals(listOf(Effect.EnqueueDownload(OPTIONAL, Network.Any, false)), effects)
    }

    @Test
    fun `FR-6 with autoDownload off a newer version waits in Available until startDownload`() {
        val harness = EngineHarness(MANUAL)
        val checkEffects = harness.send(found(OPTIONAL), CheckStarted(false), found(OPTIONAL))

        assertTrue(checkEffects.none { it is Effect.EnqueueDownload })
        assertEquals(UpdateState.Available(OPTIONAL), harness.state)

        val startEffects = harness.send(StartDownload).withoutPersist()
        assertEquals(listOf(Effect.EnqueueDownload(OPTIONAL, Network.Any, true)), startEffects)
    }

    @Test
    fun `FR-6 the optional and forced network settings are used for their kind of update`() {
        val settings = AUTO.copy(optionalDownloadNetwork = Network.UnmeteredOnly, forcedDownloadNetwork = Network.NotRoaming)

        val optional = EngineHarness(settings).send(found(OPTIONAL)).withoutPersist()
        val forced = EngineHarness(settings).send(found(FORCED)).withoutPersist()

        assertEquals(listOf(Effect.EnqueueDownload(OPTIONAL, Network.UnmeteredOnly, false)), optional)
        assertEquals(listOf(Effect.EnqueueDownload(FORCED, Network.NotRoaming, false)), forced)
    }

    // ---- FR-8 and FR-34: never download the same version twice ----

    @Test
    fun `FR-8 repeated checks during a download do not start a second download`() {
        val harness = EngineHarness(AUTO)
        val effects = harness.send(found(OPTIONAL), found(OPTIONAL), CheckStarted(false), CheckNotModified, EngineEvent.Submit(OPTIONAL))

        assertEquals(1, effects.count { it is Effect.EnqueueDownload })
    }

    @Test
    fun `FR-8 a new download URL for the same file does not restart the download`() {
        val harness = EngineHarness(AUTO)
        harness.send(found(OPTIONAL))

        val moved = info(apkUrl = "https://cdn.example.com/signed/app-11.apk?token=2")
        val effects = harness.send(found(moved)).withoutPersist()

        assertEquals(emptyList<Effect>(), effects)
        assertEquals(moved, harness.snapshot.download?.info)
    }

    @Test
    fun `FR-8 a verified file of the version goes straight to ReadyToInstall on the next check`() {
        val harness = EngineHarness(AUTO)
        harness.sendAll(readyPath)

        val effects = harness.send(CheckStarted(false), found(OPTIONAL)).withoutPersist()

        assertEquals(emptyList<Effect>(), effects)
        assertEquals(UpdateState.ReadyToInstall(OPTIONAL), harness.state)
    }

    @Test
    fun `FR-34 AC-14 a verified download is offered after a restart without downloading again`() {
        val first = EngineHarness(AUTO)
        val saved = first.sendAll(readyPath).lastPersisted()

        val second = EngineHarness(AUTO, snapshot = saved, fileExists = true)

        assertEquals(UpdateState.ReadyToInstall(OPTIONAL), second.state)
        assertEquals(listOf(Effect.ScheduleChecks, Effect.StartCheck(false)), second.startEffects.withoutPersist())
    }

    @Test
    fun `FR-34 AC-14 a download interrupted by process death continues after the restart`() {
        val first = EngineHarness(AUTO)
        val saved = first.send(found(OPTIONAL), DownloadProgressed(11, DownloadProgress(400, 1000))).lastPersisted()

        val second = EngineHarness(AUTO, snapshot = saved)

        assertEquals(UpdateState.Downloading(OPTIONAL, DownloadProgress(0, 1000), userInitiated = false), second.state)
        assertEquals(
            listOf(Effect.EnqueueDownload(OPTIONAL, Network.Any, false), Effect.ScheduleChecks, Effect.StartCheck(false)),
            second.startEffects.withoutPersist(),
        )
    }

    @Test
    fun `FR-34 a file downloaded but not verified before process death is verified after the restart`() {
        val first = EngineHarness(AUTO)
        val saved = first.send(found(OPTIONAL), DownloadFinished(11, PATH)).lastPersisted()

        val second = EngineHarness(AUTO, snapshot = saved, fileExists = true)

        assertEquals(
            listOf(Effect.Verify(PATH, OPTIONAL), Effect.ScheduleChecks, Effect.StartCheck(false)),
            second.startEffects.withoutPersist(),
        )
        second.send(VerificationPassed(11))
        assertEquals(UpdateState.ReadyToInstall(OPTIONAL), second.state)
    }

    // ---- FR-12: never install a version that is not newer ----

    @Test
    fun `FR-12 a version file with an older or equal version ends in UpToDate`() {
        listOf(9L, 10L).forEach { versionCode ->
            val harness = EngineHarness(AUTO)
            val effects = harness.send(CheckStarted(false), found(info(versionCode))).withoutPersist()

            assertEquals(UpdateState.UpToDate, harness.state)
            assertEquals(emptyList<Effect>(), effects)
        }
    }

    @Test
    fun `FR-12 an APK that is not newer ends in Failed(NotNewer) and is deleted`() {
        val harness = EngineHarness(AUTO)
        val effects = harness.send(found(OPTIONAL), DownloadFinished(11, PATH), VerificationFailed(11, FailureReason.NotNewer))

        assertEquals(UpdateState.Failed(FailureReason.NotNewer, OPTIONAL, false), harness.state)
        assertTrue(Effect.DeleteFile(PATH) in effects)
        assertTrue(effects.none { it is Effect.Install })
    }

    @Test
    fun `FR-12 a check that turns out older discards the running download`() {
        val harness = EngineHarness(AUTO)
        harness.send(found(OPTIONAL))

        val effects = harness.send(found(info(10))).withoutPersist()

        assertEquals(listOf(Effect.CancelDownload), effects)
        assertEquals(UpdateState.UpToDate, harness.state)
        assertNull(harness.snapshot.download)
    }

    // ---- FR-15: canInstallNow ----

    @Test
    fun `FR-15 canInstallNow false keeps ReadyToInstall and the next installIfReady tries again`() {
        val harness = EngineHarness(AUTO)
        harness.sendAll(readyPath)

        harness.send(InstallIfReady, InstallNotAllowedNow)
        assertEquals(UpdateState.ReadyToInstall(OPTIONAL), harness.state)

        val retry = harness.send(InstallIfReady).withoutPersist()
        assertEquals(listOf(Effect.Install(PATH, OPTIONAL)), retry)
    }

    @Test
    fun `FR-15 after canInstallNow false the next app start offers the install again`() {
        val first = EngineHarness(AUTO)
        first.sendAll(readyPath)
        val saved = first.send(InstallIfReady, InstallNotAllowedNow).let { first.snapshot }

        val second = EngineHarness(AUTO, snapshot = saved, fileExists = true)

        assertEquals(UpdateState.ReadyToInstall(OPTIONAL), second.state)
    }

    // ---- FR-32: mobile data for a waiting forced update ----

    @Test
    fun `FR-32 useMobileDataOnce is ignored for an optional update`() {
        val harness = EngineHarness(AUTO.copy(optionalDownloadNetwork = Network.UnmeteredOnly))
        harness.send(found(OPTIONAL), DownloadWaitingForNetwork(11))

        assertEquals(emptyList<Effect>(), harness.send(UseMobileDataOnce))
    }

    @Test
    fun `FR-32 useMobileDataOnce is ignored when the host app turned the choice off`() {
        val harness = EngineHarness(FORCED_WIFI.copy(allowMobileDataOverride = false))
        harness.send(found(FORCED), StartDownload, DownloadWaitingForNetwork(11))

        assertEquals(UpdateState.Required(FORCED, UpdateStep.WaitingForNetwork(false)), harness.state)
        assertEquals(emptyList<Effect>(), harness.send(UseMobileDataOnce))
    }

    @Test
    fun `FR-32 no mobile data choice when the forced update may already use any network`() {
        val harness = EngineHarness(MANUAL)
        harness.send(found(FORCED), StartDownload, DownloadWaitingForNetwork(11))

        assertEquals(UpdateState.Required(FORCED, UpdateStep.WaitingForNetwork(false)), harness.state)
        assertEquals(emptyList<Effect>(), harness.send(UseMobileDataOnce))
    }

    @Test
    fun `FR-32 useMobileDataOnce is ignored while the forced update is not waiting for a network`() {
        val harness = EngineHarness(FORCED_WIFI)
        harness.send(found(FORCED), StartDownload)

        assertEquals(emptyList<Effect>(), harness.send(UseMobileDataOnce))
    }

    @Test
    fun `FR-32 the mobile data grant is persisted and applies to that version only`() {
        val first = EngineHarness(FORCED_WIFI)
        val effects = first.send(found(FORCED), StartDownload, DownloadWaitingForNetwork(11), UseMobileDataOnce)
        assertEquals(11L, effects.lastPersisted()?.mobileDataVersionCode)

        // After a restart the grant still holds for version 11.
        val second = EngineHarness(FORCED_WIFI, snapshot = first.snapshot)
        assertTrue(Effect.EnqueueDownload(FORCED, Network.Any, false) in second.startEffects)

        // A newer forced version waits for Wi-Fi again and offers the choice again.
        val newer = info(versionCode = 12, mandatory = true)
        val newerEffects = second.send(found(newer), StartDownload).withoutPersist()
        assertTrue(Effect.EnqueueDownload(newer, Network.UnmeteredOnly, true) in newerEffects)
        second.send(DownloadWaitingForNetwork(12))
        assertEquals(UpdateState.Required(newer, UpdateStep.WaitingForNetwork(true)), second.state)
    }

    // ---- FR-33 and UI spec 2.6: failed checks ----

    @Test
    fun `FR-33 a failed background check leaves every state untouched`() {
        val setups: List<Pair<EngineSettings, List<EngineEvent>>> =
            listOf(
                AUTO to emptyList(),
                AUTO to listOf(found(info(10))),
                MANUAL to listOf(found(OPTIONAL)),
                AUTO to listOf(found(OPTIONAL)),
                AUTO to listOf(found(OPTIONAL), DownloadWaitingForNetwork(11)),
                AUTO to readyPath,
                AUTO to readyPath + InstallIfReady + PermissionNeeded,
                AUTO to readyPath + InstallIfReady + InstallStarted,
                AUTO to readyPath + InstallIfReady + InstallStarted + InstallFinished(InstallResult.Cancelled),
                MANUAL to listOf(found(FORCED)),
                AUTO to listOf(DeviceUnsupported),
            )
        setups.forEach { (settings, given) ->
            val harness = EngineHarness(settings)
            harness.sendAll(given)
            val before = harness.state

            val effects = harness.send(CheckStarted(false), CheckFailed(FailureReason.Network))

            assertEquals(before, harness.state)
            assertEquals(emptyList<Effect>(), effects)
        }
    }

    @Test
    fun `UI 2-2 Downloading records whether the user started the download`() {
        val background = EngineHarness(AUTO)
        background.send(found(OPTIONAL), DownloadWaitingForNetwork(11), DownloadProgressed(11, DownloadProgress(5, 1000)))
        assertEquals(UpdateState.Downloading(OPTIONAL, DownloadProgress(5, 1000), userInitiated = false), background.state)

        val user = EngineHarness(MANUAL)
        user.send(found(OPTIONAL), StartDownload)
        assertEquals(UpdateState.Downloading(OPTIONAL, DownloadProgress(0, 1000), userInitiated = true), user.state)

        // The user taps Update while a background download runs: from then on it is theirs.
        background.send(StartDownload)
        assertEquals(UpdateState.Downloading(OPTIONAL, DownloadProgress(5, 1000), userInitiated = true), background.state)

        // After a restart the resumed download runs in the background again.
        val restarted = EngineHarness(AUTO, snapshot = user.snapshot)
        assertEquals(UpdateState.Downloading(OPTIONAL, DownloadProgress(0, 1000), userInitiated = false), restarted.state)
    }

    @Test
    fun `UI 2-6 a failed user check is shown when no update is known`() {
        val harness = EngineHarness(AUTO)
        harness.send(CheckStarted(true), CheckFailed(FailureReason.Parse))

        assertEquals(UpdateState.Failed(FailureReason.Parse, null, true), harness.state)
    }

    @Test
    fun `UI 2-6 a failed user check does not replace a known update`() {
        val harness = EngineHarness(MANUAL)
        harness.send(found(OPTIONAL), CheckStarted(true), CheckFailed(FailureReason.Network))

        assertEquals(UpdateState.Available(OPTIONAL), harness.state)
    }

    @Test
    fun `UI 2-6 Failed records whether the user started the failed action`() {
        val background = EngineHarness(AUTO)
        background.send(found(OPTIONAL), DownloadFailed(11, FailureReason.Network))
        assertEquals(UpdateState.Failed(FailureReason.Network, OPTIONAL, false), background.state)

        val user = EngineHarness(MANUAL)
        user.send(found(OPTIONAL), StartDownload, DownloadFailed(11, FailureReason.Network))
        assertEquals(UpdateState.Failed(FailureReason.Network, OPTIONAL, true), user.state)

        val adopted = EngineHarness(AUTO)
        adopted.send(found(OPTIONAL), StartDownload, DownloadFailed(11, FailureReason.Network))
        assertEquals(UpdateState.Failed(FailureReason.Network, OPTIONAL, true), adopted.state)
    }

    // ---- FR-18: unsupported devices ----

    @Test
    fun `FR-18 Unsupported sticks across a restart while the installed version is the same`() {
        val first = EngineHarness(AUTO)
        first.sendAll(readyPath + DeviceUnsupported)
        assertEquals(INSTALLED, first.snapshot.unsupportedAtVersionCode)

        val second = EngineHarness(AUTO, snapshot = first.snapshot)

        assertEquals(UpdateState.Unsupported, second.state)
        assertTrue(second.startEffects.none { it is Effect.EnqueueDownload })
    }

    @Test
    fun `FR-18 Unsupported is cleared when the installed version changes`() {
        val first = EngineHarness(AUTO)
        first.send(DeviceUnsupported)

        val second = EngineHarness(MANUAL, snapshot = first.snapshot, installed = 11)
        assertNull(second.snapshot.unsupportedAtVersionCode)
        second.send(found(info(12)))

        assertEquals(UpdateState.Available(info(12)), second.state)
    }

    // ---- Notifications ----

    @Test
    fun `the ready notification is requested only when notifications are on`() {
        val on = EngineHarness(AUTO).sendAll(readyPath)
        val off = EngineHarness(AUTO.copy(notifications = false)).sendAll(readyPath)

        assertTrue(Effect.ShowReadyNotification(OPTIONAL) in on)
        assertTrue(off.none { it is Effect.ShowReadyNotification })
    }

    @Test
    fun `the ready notification is not requested again on restart`() {
        val first = EngineHarness(AUTO)
        first.sendAll(readyPath)

        val second = EngineHarness(AUTO, snapshot = first.snapshot, fileExists = true)

        assertTrue(second.startEffects.none { it is Effect.ShowReadyNotification })
    }

    // ---- Restart and successful install ----

    @Test
    fun `a first start with no snapshot is Idle, schedules checks, and checks`() {
        val harness = EngineHarness(AUTO)

        assertEquals(UpdateState.Idle, harness.state)
        assertEquals(listOf(Effect.ScheduleChecks, Effect.StartCheck(false)), harness.startEffects.withoutPersist())
        assertEquals(INSTALLED, harness.startEffects.lastPersisted()?.lastInstalledVersionCode)
    }

    @Test
    fun `a restored snapshot whose file is missing is discarded`() {
        val first = EngineHarness(AUTO)
        first.sendAll(readyPath)

        val manual = EngineHarness(MANUAL, snapshot = first.snapshot, fileExists = false)
        assertNull(manual.snapshot.download)
        assertEquals(UpdateState.Available(OPTIONAL), manual.state)
        assertTrue(manual.startEffects.none { it is Effect.DeleteFile || it is Effect.Install })

        // With automatic download on, the version is fetched again.
        val auto = EngineHarness(AUTO, snapshot = first.snapshot, fileExists = false)
        assertTrue(Effect.EnqueueDownload(OPTIONAL, Network.Any, false) in auto.startEffects)
    }

    @Test
    fun `FR-17 a successful install deletes the file, returns to UpToDate, and reschedules checks`() {
        val harness = EngineHarness(AUTO)
        harness.sendAll(readyPath + InstallIfReady + InstallStarted)

        val effects = harness.send(InstallFinished(InstallResult.Success))

        assertEquals(UpdateState.UpToDate, harness.state)
        assertTrue(Effect.DeleteFile(PATH) in effects)
        assertTrue(Effect.ScheduleChecks in effects)
        val saved = effects.lastPersisted()
        assertNull(saved?.download)
        assertEquals(11L, saved?.lastInstalledVersionCode)
    }

    @Test
    fun `FR-17 a restart after the update was installed deletes the file and ends in UpToDate`() {
        val first = EngineHarness(AUTO)
        first.sendAll(readyPath + InstallIfReady + InstallStarted)

        // The process is killed by the update; the new version starts.
        val second = EngineHarness(AUTO, snapshot = first.snapshot, installed = 11, fileExists = true)

        assertEquals(UpdateState.UpToDate, second.state)
        assertEquals(
            listOf(Effect.DeleteFile(PATH), Effect.CancelReadyNotification, Effect.ScheduleChecks, Effect.StartCheck(false)),
            second.startEffects.withoutPersist(),
        )
        assertNull(second.snapshot.download)
    }

    // ---- Rejected files ----

    @Test
    fun `a file that failed verification is not downloaded again by later checks or a restart`() {
        val harness = EngineHarness(AUTO)
        harness.send(found(OPTIONAL), DownloadFinished(11, PATH), VerificationFailed(11, FailureReason.HashMismatch))

        val effects = harness.send(CheckStarted(false), found(OPTIONAL))
        assertTrue(effects.none { it is Effect.EnqueueDownload })
        assertEquals(UpdateState.Failed(FailureReason.HashMismatch, OPTIONAL, false), harness.state)

        val restarted = EngineHarness(AUTO, snapshot = harness.snapshot)
        assertTrue(restarted.startEffects.none { it is Effect.EnqueueDownload })
        assertEquals(UpdateState.Failed(FailureReason.HashMismatch, OPTIONAL, false), restarted.state)
    }

    @Test
    fun `a fixed release with a new hash is downloaded again`() {
        val harness = EngineHarness(AUTO)
        harness.send(found(OPTIONAL), DownloadFinished(11, PATH), VerificationFailed(11, FailureReason.HashMismatch))

        val fixed = info(sha256 = HASH_B)
        val effects = harness.send(found(fixed))

        assertTrue(Effect.EnqueueDownload(fixed, Network.Any, false) in effects)
        assertNull(effects.lastPersisted()?.rejected)
    }

    @Test
    fun `a storage failure during verification is not remembered as a rejected file`() {
        val harness = EngineHarness(AUTO)
        harness.send(found(OPTIONAL), DownloadFinished(11, PATH), VerificationFailed(11, FailureReason.Storage))

        assertNull(harness.snapshot.rejected)
    }

    // ---- ETag and submit ----

    @Test
    fun `a check stores its ETag and version, and 304 re-evaluates the stored version`() {
        val harness = EngineHarness(MANUAL)
        val effects = harness.send(found(OPTIONAL, etag = "\"v11\""))

        assertEquals(Snapshot(etag = "\"v11\"", latest = OPTIONAL, lastInstalledVersionCode = INSTALLED), effects.lastPersisted())

        harness.send(CheckStarted(false), CheckNotModified)
        assertEquals(UpdateState.Available(OPTIONAL), harness.state)
    }

    @Test
    fun `304 without a stored version drops the ETag and changes nothing`() {
        val harness = EngineHarness(AUTO, snapshot = Snapshot(etag = "\"x\""))
        harness.send(CheckStarted(false), CheckNotModified)

        assertEquals(UpdateState.Idle, harness.state)
        assertNull(harness.snapshot.etag)
    }

    @Test
    fun `FR-35 submit has the effect of a successful check and clears the ETag`() {
        val harness = EngineHarness(AUTO, snapshot = Snapshot(etag = "\"old\"", latest = info(10)))

        val effects = harness.send(Submit(OPTIONAL))

        assertEquals(UpdateState.Downloading(OPTIONAL, DownloadProgress(0, 1000), userInitiated = false), harness.state)
        assertTrue(Effect.EnqueueDownload(OPTIONAL, Network.Any, false) in effects)
        assertNull(harness.snapshot.etag)
        assertEquals(OPTIONAL, harness.snapshot.latest)
    }

    // ---- Engine mechanics ----

    @Test
    fun `Persist comes first and only when the snapshot changed`() {
        val harness = EngineHarness(AUTO)

        val checkEffects = harness.send(found(OPTIONAL))
        assertTrue(checkEffects.first() is Effect.Persist)

        val progressEffects = harness.send(DownloadProgressed(11, DownloadProgress(10, 1000)))
        assertEquals(emptyList<Effect>(), progressEffects)
    }

    @Test
    fun `events before the process start are ignored and logged`() {
        val messages = mutableListOf<String>()
        val engine = UpdateEngine(AUTO.copy(logger = RenovoLogger { message, _ -> messages += message }))

        assertEquals(emptyList<Effect>(), engine.handle(found(OPTIONAL)))
        assertEquals(UpdateState.Idle, engine.state.value)
        assertEquals(1, messages.size)
    }

    @Test
    fun `a second process start report is ignored`() {
        val harness = EngineHarness(MANUAL)
        harness.send(found(OPTIONAL))

        assertEquals(emptyList<Effect>(), harness.send(Restored(null, INSTALLED, false)))
        assertEquals(UpdateState.Available(OPTIONAL), harness.state)
    }

    @Test
    fun `stale download events of a replaced version are ignored`() {
        val harness = EngineHarness(AUTO)
        harness.send(found(OPTIONAL), found(info(12)))
        val before = harness.state

        val effects =
            harness.send(
                DownloadProgressed(11, DownloadProgress(500, 1000)),
                DownloadWaitingForNetwork(11),
                DownloadFailed(11, FailureReason.Network),
                VerificationPassed(11),
            )

        assertEquals(before, harness.state)
        assertEquals(emptyList<Effect>(), effects)
    }

    @Test
    fun `a finished download that nobody wants any more is deleted`() {
        val harness = EngineHarness(AUTO)
        harness.send(found(OPTIONAL), found(info(12)))

        val effects = harness.send(DownloadFinished(11, PATH))

        assertEquals(listOf(Effect.DeleteFile(PATH)), effects)
    }

    @Test
    fun `install events without a verified file are ignored`() {
        val harness = EngineHarness(MANUAL)
        harness.send(found(OPTIONAL))

        val effects = harness.send(PermissionNeeded, PermissionGranted, InstallStarted, InstallFinished(InstallResult.Success), InstallIfReady)

        assertEquals(UpdateState.Available(OPTIONAL), harness.state)
        assertEquals(emptyList<Effect>(), effects)
    }

    @Test
    fun `a permission grant without a pending install does not install`() {
        val harness = EngineHarness(AUTO)
        harness.sendAll(readyPath)

        assertEquals(emptyList<Effect>(), harness.send(PermissionGranted))
    }

    @Test
    fun `the state flow does not emit a state equal to the current one`() = runTest {
        val harness = EngineHarness(AUTO)
        val states = mutableListOf<UpdateState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { harness.engine.state.toList(states) }

        harness.send(
            found(OPTIONAL),
            DownloadProgressed(11, DownloadProgress(10, 1000)),
            DownloadProgressed(11, DownloadProgress(10, 1000)),
            found(OPTIONAL),
        )

        assertEquals(
            listOf(
                UpdateState.Idle,
                UpdateState.Downloading(OPTIONAL, DownloadProgress(0, 1000), userInitiated = false),
                UpdateState.Downloading(OPTIONAL, DownloadProgress(10, 1000), userInitiated = false),
            ),
            states,
        )
    }

    @Test
    fun `an unknown size gives an indeterminate progress`() {
        val noSize = info(size = null)
        val harness = EngineHarness(AUTO)
        harness.send(found(noSize))

        assertEquals(UpdateState.Downloading(noSize, DownloadProgress(0, null), userInitiated = false), harness.state)
    }
}
