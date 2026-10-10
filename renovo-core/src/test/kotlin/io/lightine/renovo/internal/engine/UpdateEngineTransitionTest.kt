package io.lightine.renovo.internal.engine

import io.lightine.renovo.DownloadProgress
import io.lightine.renovo.FailureReason
import io.lightine.renovo.Network
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateState
import io.lightine.renovo.UpdateStep
import io.lightine.renovo.internal.engine.EngineEvent.CheckFailed
import io.lightine.renovo.internal.engine.EngineEvent.CheckNotModified
import io.lightine.renovo.internal.engine.EngineEvent.CheckNow
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
import io.lightine.renovo.internal.engine.EngineEvent.Retry
import io.lightine.renovo.internal.engine.EngineEvent.StartDownload
import io.lightine.renovo.internal.engine.EngineEvent.Submit
import io.lightine.renovo.internal.engine.EngineEvent.UseMobileDataOnce
import io.lightine.renovo.internal.engine.EngineEvent.VerificationFailed
import io.lightine.renovo.internal.engine.EngineEvent.VerificationPassed
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Every transition of the update states in technical spec section 5, as one table.
 *
 * Each row: starting from a process start with no snapshot (installed version 10), the `given`
 * events lead to the state named first in the row; then `event` must produce `expected` and exactly
 * the listed effects. Persist effects are left out here; UpdateEngineRulesTest checks them.
 */
@RunWith(Parameterized::class)
class UpdateEngineTransitionTest(
    private val row: Row,
) {
    // Public for the Parameterized runner; its contents stay internal like the engine types.
    class Row internal constructor(
        private val name: String,
        internal val settings: EngineSettings,
        internal val given: List<EngineEvent>,
        internal val event: EngineEvent,
        internal val expected: UpdateState,
        internal val effects: List<Effect>,
    ) {
        override fun toString(): String = name
    }

    @Test
    fun transition() {
        val harness = EngineHarness(row.settings)
        harness.sendAll(row.given)

        val effects = harness.send(row.event).withoutPersist()

        assertEquals("state", row.expected, harness.state)
        assertEquals("effects", row.effects, effects)
    }

    companion object {
        private val O = OPTIONAL
        private val F = FORCED

        private fun found(info: UpdateInfo) = CheckSucceeded(info, etag = null)

        private fun progress(bytes: Long) = DownloadProgress(bytes, 1000)

        private val START = progress(0)
        private val DONE = progress(1000)

        // Paths to each optional state.
        private val available = listOf(found(O)) // with MANUAL
        private val downloadingBackground = listOf(found(O)) // with AUTO
        private val downloadingUser = listOf(found(O), StartDownload) // with MANUAL
        private val downloaded = downloadingBackground + DownloadFinished(11, PATH)
        private val ready = downloaded + VerificationPassed(11)
        private val permissionNeeded = ready + InstallIfReady + PermissionNeeded
        private val installing = ready + InstallIfReady + InstallStarted
        private val installCancelled = installing + InstallFinished(InstallResult.Cancelled)

        // Paths to each forced step.
        private val forcedOffered = listOf(found(F)) // with MANUAL or FORCED_WIFI
        private val forcedDownloadingUser = forcedOffered + StartDownload
        private val forcedDownloadedUser = forcedDownloadingUser + DownloadFinished(11, PATH)
        private val forcedReady = listOf(found(F), DownloadFinished(11, PATH), VerificationPassed(11)) // with AUTO
        private val forcedInstalling = forcedReady + InstallIfReady + InstallStarted

        private fun row(
            name: String,
            settings: EngineSettings,
            given: List<EngineEvent>,
            event: EngineEvent,
            expected: UpdateState,
            vararg effects: Effect,
        ) = arrayOf<Any>(Row(name, settings, given, event, expected, effects.toList()))

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun rows(): List<Array<Any>> = listOf(
            // ---- Idle ----
            row("Idle + CheckStarted -> Checking", AUTO, emptyList(), CheckStarted(false), UpdateState.Checking),
            row("Idle + CheckNow -> Idle, starts a user check", AUTO, emptyList(), CheckNow, UpdateState.Idle, Effect.StartCheck(true)),
            row("Idle + Submit(newer) -> Available", MANUAL, emptyList(), Submit(O), UpdateState.Available(O)),
            row("Idle + StartDownload -> Idle (nothing to download)", AUTO, emptyList(), StartDownload, UpdateState.Idle),
            // ---- Checking ----
            row(
                "Checking + CheckSucceeded(older) -> UpToDate",
                AUTO,
                listOf(CheckStarted(false)),
                found(info(9)),
                UpdateState.UpToDate,
            ),
            row(
                "Checking + CheckSucceeded(same version) -> UpToDate",
                AUTO,
                listOf(CheckStarted(false)),
                found(info(10)),
                UpdateState.UpToDate,
            ),
            row(
                "Checking + CheckSucceeded(newer), autoDownload off -> Available",
                MANUAL,
                listOf(CheckStarted(false)),
                found(O),
                UpdateState.Available(O),
            ),
            row(
                "Checking + CheckSucceeded(newer), autoDownload on -> Downloading",
                AUTO,
                listOf(CheckStarted(false)),
                found(O),
                UpdateState.Downloading(O, START, userInitiated = false),
                Effect.EnqueueDownload(O, Network.Any, false),
            ),
            row(
                "Checking + CheckSucceeded(mandatory) -> Required(WaitingForUser)",
                MANUAL,
                listOf(CheckStarted(false)),
                found(F),
                UpdateState.Required(F, UpdateStep.WaitingForUser),
            ),
            row(
                "Checking + CheckSucceeded(installed below minimum) -> Required(WaitingForUser)",
                MANUAL,
                listOf(CheckStarted(false)),
                found(info(minSupportedVersionCode = 11)),
                UpdateState.Required(info(minSupportedVersionCode = 11), UpdateStep.WaitingForUser),
            ),
            row(
                "Checking (background) + CheckFailed -> Idle",
                AUTO,
                listOf(CheckStarted(false)),
                CheckFailed(FailureReason.Network),
                UpdateState.Idle,
            ),
            row(
                "Checking (user) + CheckFailed -> Failed(shown)",
                AUTO,
                listOf(CheckStarted(true)),
                CheckFailed(FailureReason.Network),
                UpdateState.Failed(FailureReason.Network, null, true),
            ),
            row(
                "Checking (user) + CheckFailed(Parse) -> Failed(Parse, shown)",
                AUTO,
                listOf(CheckStarted(true)),
                CheckFailed(FailureReason.Parse),
                UpdateState.Failed(FailureReason.Parse, null, true),
            ),
            row(
                "Checking + CheckNotModified -> state of the stored version",
                AUTO,
                listOf(CheckSucceeded(info(10), "e1"), CheckStarted(false)),
                CheckNotModified,
                UpdateState.UpToDate,
            ),
            // ---- UpToDate ----
            row("UpToDate + CheckStarted -> Checking", AUTO, listOf(found(info(10))), CheckStarted(false), UpdateState.Checking),
            row(
                "UpToDate + background CheckFailed -> UpToDate",
                AUTO,
                listOf(found(info(10)), CheckStarted(false)),
                CheckFailed(FailureReason.Network),
                UpdateState.UpToDate,
            ),
            row(
                "UpToDate + CheckSucceeded(newer) -> Available",
                MANUAL,
                listOf(found(info(10))),
                found(O),
                UpdateState.Available(O),
            ),
            // ---- Available ----
            row(
                "Available + StartDownload -> Downloading (user)",
                MANUAL,
                available,
                StartDownload,
                UpdateState.Downloading(O, START, userInitiated = true),
                Effect.EnqueueDownload(O, Network.Any, true),
            ),
            row("Available + CheckStarted -> Available", MANUAL, available, CheckStarted(false), UpdateState.Available(O)),
            row(
                "Available + CheckFailed -> Available",
                MANUAL,
                available + CheckStarted(false),
                CheckFailed(FailureReason.Network),
                UpdateState.Available(O),
            ),
            row("Available + InstallIfReady -> Available (nothing ready)", MANUAL, available, InstallIfReady, UpdateState.Available(O)),
            row("Available + Retry -> Available (nothing failed)", MANUAL, available, Retry, UpdateState.Available(O)),
            row(
                "Available + CheckSucceeded(older) -> UpToDate",
                MANUAL,
                available,
                found(info(9)),
                UpdateState.UpToDate,
            ),
            // ---- Downloading ----
            row(
                "Downloading + DownloadProgressed -> Downloading(progress)",
                AUTO,
                downloadingBackground,
                DownloadProgressed(11, progress(500)),
                UpdateState.Downloading(O, progress(500), userInitiated = false),
            ),
            row(
                "Downloading + DownloadWaitingForNetwork -> WaitingForNetwork",
                AUTO,
                downloadingBackground,
                DownloadWaitingForNetwork(11),
                UpdateState.WaitingForNetwork(O),
            ),
            row(
                "Downloading + DownloadFinished -> Downloading, verifies the file",
                AUTO,
                downloadingBackground,
                DownloadFinished(11, PATH),
                UpdateState.Downloading(O, DONE, userInitiated = false),
                Effect.Verify(PATH, O),
            ),
            row(
                "Downloading (background) + DownloadFailed -> Failed(not shown)",
                AUTO,
                downloadingBackground,
                DownloadFailed(11, FailureReason.Network),
                UpdateState.Failed(FailureReason.Network, O, false),
            ),
            row(
                "Downloading (user) + DownloadFailed(Storage) -> Failed(shown)",
                MANUAL,
                downloadingUser,
                DownloadFailed(11, FailureReason.Storage),
                UpdateState.Failed(FailureReason.Storage, O, true),
            ),
            row(
                "Downloading (background) + StartDownload -> Downloading, now started by the user",
                AUTO,
                downloadingBackground,
                StartDownload,
                UpdateState.Downloading(O, START, userInitiated = true),
                Effect.EnqueueDownload(O, Network.Any, true),
            ),
            row(
                "Downloading (user) + StartDownload -> Downloading, nothing new",
                MANUAL,
                downloadingUser,
                StartDownload,
                UpdateState.Downloading(O, START, userInitiated = true),
            ),
            row(
                "Downloading + CheckSucceeded(same version) -> Downloading, no second download",
                AUTO,
                downloadingBackground,
                found(O),
                UpdateState.Downloading(O, START, userInitiated = false),
            ),
            row(
                "Downloading + CheckSucceeded(newer) -> Downloading the newer version",
                AUTO,
                downloadingBackground,
                found(info(12)),
                UpdateState.Downloading(info(12), START, userInitiated = false),
                Effect.CancelDownload,
                Effect.EnqueueDownload(info(12), Network.Any, false),
            ),
            // ---- WaitingForNetwork ----
            row(
                "WaitingForNetwork + DownloadProgressed -> Downloading",
                AUTO,
                downloadingBackground + DownloadWaitingForNetwork(11),
                DownloadProgressed(11, progress(100)),
                UpdateState.Downloading(O, progress(100), userInitiated = false),
            ),
            row(
                "WaitingForNetwork (optional) + UseMobileDataOnce -> WaitingForNetwork, ignored",
                AUTO.copy(optionalDownloadNetwork = Network.UnmeteredOnly),
                downloadingBackground + DownloadWaitingForNetwork(11),
                UseMobileDataOnce,
                UpdateState.WaitingForNetwork(O),
            ),
            // ---- Verification ----
            row(
                "Downloaded + VerificationPassed -> ReadyToInstall",
                AUTO,
                downloaded,
                VerificationPassed(11),
                UpdateState.ReadyToInstall(O),
                Effect.ShowReadyNotification(O),
            ),
            row(
                "Downloaded + VerificationFailed(HashMismatch) -> Failed",
                AUTO,
                downloaded,
                VerificationFailed(11, FailureReason.HashMismatch),
                UpdateState.Failed(FailureReason.HashMismatch, O, false),
                Effect.DeleteFile(PATH),
            ),
            row(
                "Downloaded + VerificationFailed(SignatureMismatch) -> Failed",
                AUTO,
                downloaded,
                VerificationFailed(11, FailureReason.SignatureMismatch),
                UpdateState.Failed(FailureReason.SignatureMismatch, O, false),
                Effect.DeleteFile(PATH),
            ),
            row(
                "Downloaded + VerificationFailed(NotNewer) -> Failed",
                AUTO,
                downloaded,
                VerificationFailed(11, FailureReason.NotNewer),
                UpdateState.Failed(FailureReason.NotNewer, O, false),
                Effect.DeleteFile(PATH),
            ),
            row(
                "Downloaded (user) + VerificationFailed -> Failed(shown)",
                MANUAL,
                downloadingUser + DownloadFinished(11, PATH),
                VerificationFailed(11, FailureReason.HashMismatch),
                UpdateState.Failed(FailureReason.HashMismatch, O, true),
                Effect.DeleteFile(PATH),
            ),
            // ---- ReadyToInstall ----
            row(
                "ReadyToInstall + InstallIfReady -> ReadyToInstall, installs",
                AUTO,
                ready,
                InstallIfReady,
                UpdateState.ReadyToInstall(O),
                Effect.Install(PATH, O),
            ),
            row(
                "ReadyToInstall + StartDownload -> ReadyToInstall, installs (already downloaded)",
                AUTO,
                ready,
                StartDownload,
                UpdateState.ReadyToInstall(O),
                Effect.Install(PATH, O),
            ),
            row("ReadyToInstall + PermissionNeeded -> PermissionNeeded", AUTO, ready, PermissionNeeded, UpdateState.PermissionNeeded(O)),
            row("ReadyToInstall + InstallStarted -> Installing", AUTO, ready, InstallStarted, UpdateState.Installing(O)),
            row(
                "ReadyToInstall + InstallNotAllowedNow -> ReadyToInstall",
                AUTO,
                ready + InstallIfReady,
                InstallNotAllowedNow,
                UpdateState.ReadyToInstall(O),
            ),
            row(
                "ReadyToInstall + DeviceUnsupported -> Unsupported",
                AUTO,
                ready,
                DeviceUnsupported,
                UpdateState.Unsupported,
                Effect.DeleteFile(PATH),
                Effect.CancelReadyNotification,
            ),
            row(
                "ReadyToInstall + CheckSucceeded(newer) -> Downloading the newer version",
                AUTO,
                ready,
                found(info(12)),
                UpdateState.Downloading(info(12), START, userInitiated = false),
                Effect.DeleteFile(PATH),
                Effect.CancelReadyNotification,
                Effect.EnqueueDownload(info(12), Network.Any, false),
            ),
            // ---- PermissionNeeded ----
            row(
                "PermissionNeeded + InstallIfReady -> PermissionNeeded, opens the setting",
                AUTO,
                permissionNeeded,
                InstallIfReady,
                UpdateState.PermissionNeeded(O),
                Effect.OpenInstallPermission,
            ),
            row(
                "PermissionNeeded + PermissionGranted -> PermissionNeeded, installs",
                AUTO,
                permissionNeeded,
                PermissionGranted,
                UpdateState.PermissionNeeded(O),
                Effect.Install(PATH, O),
            ),
            row(
                "PermissionNeeded + InstallStarted -> Installing",
                AUTO,
                permissionNeeded + PermissionGranted,
                InstallStarted,
                UpdateState.Installing(O),
            ),
            row(
                "PermissionNeeded + DeviceUnsupported -> Unsupported",
                AUTO,
                permissionNeeded,
                DeviceUnsupported,
                UpdateState.Unsupported,
                Effect.DeleteFile(PATH),
                Effect.CancelReadyNotification,
            ),
            // ---- Installing ----
            row(
                "Installing + InstallFinished(Success) -> UpToDate",
                AUTO,
                installing,
                InstallFinished(InstallResult.Success),
                UpdateState.UpToDate,
                Effect.DeleteFile(PATH),
                Effect.CancelReadyNotification,
                Effect.ScheduleChecks,
            ),
            row(
                "Installing + InstallFinished(Rejected) -> Failed(InstallRejected, shown)",
                AUTO,
                installing,
                InstallFinished(InstallResult.Rejected),
                UpdateState.Failed(FailureReason.InstallRejected, O, true),
            ),
            row(
                "Installing + InstallFinished(Cancelled) -> Failed(Cancelled, shown)",
                AUTO,
                installing,
                InstallFinished(InstallResult.Cancelled),
                UpdateState.Failed(FailureReason.Cancelled, O, true),
            ),
            row(
                "Installing + InstallFinished(Blocked) -> Unsupported",
                AUTO,
                installing,
                InstallFinished(InstallResult.Blocked),
                UpdateState.Unsupported,
                Effect.DeleteFile(PATH),
                Effect.CancelReadyNotification,
            ),
            row("Installing + InstallIfReady -> Installing, ignored", AUTO, installing, InstallIfReady, UpdateState.Installing(O)),
            row("Installing + CheckSucceeded(same) -> Installing", AUTO, installing, found(O), UpdateState.Installing(O)),
            // ---- Failed ----
            row(
                "Failed(check, shown) + Retry -> Failed, starts a user check",
                AUTO,
                listOf(CheckStarted(true), CheckFailed(FailureReason.Network)),
                Retry,
                UpdateState.Failed(FailureReason.Network, null, true),
                Effect.StartCheck(true),
            ),
            row(
                "Failed(check, shown) + CheckStarted(user) -> Checking",
                AUTO,
                listOf(CheckStarted(true), CheckFailed(FailureReason.Network)),
                CheckStarted(true),
                UpdateState.Checking,
            ),
            row(
                "Failed(check, shown) + CheckStarted(background) -> Failed(shown)",
                AUTO,
                listOf(CheckStarted(true), CheckFailed(FailureReason.Network)),
                CheckStarted(false),
                UpdateState.Failed(FailureReason.Network, null, true),
            ),
            row(
                "Failed(check) + CheckSucceeded(newer) -> Available",
                MANUAL,
                listOf(CheckStarted(true), CheckFailed(FailureReason.Network)),
                found(O),
                UpdateState.Available(O),
            ),
            row(
                "Failed(download) + Retry -> Downloading (user)",
                AUTO,
                downloadingBackground + DownloadFailed(11, FailureReason.Network),
                Retry,
                UpdateState.Downloading(O, START, userInitiated = true),
                Effect.EnqueueDownload(O, Network.Any, true),
            ),
            row(
                "Failed(download) + StartDownload -> Downloading (user)",
                AUTO,
                downloadingBackground + DownloadFailed(11, FailureReason.Network),
                StartDownload,
                UpdateState.Downloading(O, START, userInitiated = true),
                Effect.EnqueueDownload(O, Network.Any, true),
            ),
            row(
                "Failed(download, background) + CheckSucceeded(same), autoDownload on -> Downloading",
                AUTO,
                downloadingBackground + DownloadFailed(11, FailureReason.Network),
                found(O),
                UpdateState.Downloading(O, START, userInitiated = false),
                Effect.EnqueueDownload(O, Network.Any, false),
            ),
            row(
                "Failed(HashMismatch) + StartDownload -> Downloading (user)",
                AUTO,
                downloaded + VerificationFailed(11, FailureReason.HashMismatch),
                StartDownload,
                UpdateState.Downloading(O, START, userInitiated = true),
                Effect.EnqueueDownload(O, Network.Any, true),
            ),
            row(
                "Failed(Cancelled) + Retry -> Failed, installs the kept file",
                AUTO,
                installCancelled,
                Retry,
                UpdateState.Failed(FailureReason.Cancelled, O, true),
                Effect.Install(PATH, O),
            ),
            row(
                "Failed(Cancelled) + InstallIfReady -> Failed, installs the kept file",
                AUTO,
                installCancelled,
                InstallIfReady,
                UpdateState.Failed(FailureReason.Cancelled, O, true),
                Effect.Install(PATH, O),
            ),
            row(
                "Failed(Cancelled) + InstallStarted -> Installing",
                AUTO,
                installCancelled + Retry,
                InstallStarted,
                UpdateState.Installing(O),
            ),
            // ---- Unsupported ----
            row(
                "Unsupported + StartDownload -> Unsupported",
                MANUAL,
                available + DeviceUnsupported,
                StartDownload,
                UpdateState.Unsupported,
            ),
            row(
                "Unsupported + CheckStarted -> Unsupported",
                AUTO,
                listOf(DeviceUnsupported),
                CheckStarted(true),
                UpdateState.Unsupported,
            ),
            row(
                "Unsupported + CheckSucceeded(newer, optional) -> Unsupported, no download",
                AUTO,
                listOf(DeviceUnsupported),
                found(O),
                UpdateState.Unsupported,
            ),
            row(
                "Unsupported + CheckSucceeded(forced) -> Required(Unsupported)",
                AUTO,
                listOf(DeviceUnsupported),
                found(F),
                UpdateState.Required(F, UpdateStep.Unsupported),
            ),
            // ---- Required: the forced update's steps ----
            row(
                "Required(WaitingForUser) + StartDownload -> Required(Downloading) on the forced network",
                FORCED_WIFI,
                forcedOffered,
                StartDownload,
                UpdateState.Required(F, UpdateStep.Downloading(START)),
                Effect.EnqueueDownload(F, Network.UnmeteredOnly, true),
            ),
            row(
                "Required(WaitingForUser) + background CheckFailed -> Required(WaitingForUser)",
                MANUAL,
                forcedOffered + CheckStarted(false),
                CheckFailed(FailureReason.Network),
                UpdateState.Required(F, UpdateStep.WaitingForUser),
            ),
            row(
                "Required(Downloading) + DownloadProgressed -> Required(Downloading(progress))",
                FORCED_WIFI,
                forcedDownloadingUser,
                DownloadProgressed(11, progress(300)),
                UpdateState.Required(F, UpdateStep.Downloading(progress(300))),
            ),
            row(
                "Required(Downloading) + DownloadWaitingForNetwork -> Required(WaitingForNetwork(mobile data allowed))",
                FORCED_WIFI,
                forcedDownloadingUser,
                DownloadWaitingForNetwork(11),
                UpdateState.Required(F, UpdateStep.WaitingForNetwork(true)),
            ),
            row(
                "Required(WaitingForNetwork) + UseMobileDataOnce -> Required(Downloading) on any network",
                FORCED_WIFI,
                forcedDownloadingUser + DownloadWaitingForNetwork(11),
                UseMobileDataOnce,
                UpdateState.Required(F, UpdateStep.Downloading(START)),
                Effect.EnqueueDownload(F, Network.Any, true),
            ),
            row(
                "Required(WaitingForNetwork), override off -> no mobile data choice, UseMobileDataOnce ignored",
                FORCED_WIFI.copy(allowMobileDataOverride = false),
                forcedDownloadingUser + DownloadWaitingForNetwork(11),
                UseMobileDataOnce,
                UpdateState.Required(F, UpdateStep.WaitingForNetwork(false)),
            ),
            row(
                "Required(Downloading) + DownloadFinished -> Required(Downloading), verifies",
                FORCED_WIFI,
                forcedDownloadingUser,
                DownloadFinished(11, PATH),
                UpdateState.Required(F, UpdateStep.Downloading(DONE)),
                Effect.Verify(PATH, F),
            ),
            row(
                "Required(Downloading, user) + VerificationPassed -> Required(WaitingForUser), installs at once",
                FORCED_WIFI,
                forcedDownloadedUser,
                VerificationPassed(11),
                UpdateState.Required(F, UpdateStep.WaitingForUser),
                Effect.ShowReadyNotification(F),
                Effect.Install(PATH, F),
            ),
            row(
                "Required(Downloading, background) + VerificationPassed -> Required(WaitingForUser)",
                AUTO,
                listOf(found(F), DownloadFinished(11, PATH)),
                VerificationPassed(11),
                UpdateState.Required(F, UpdateStep.WaitingForUser),
                Effect.ShowReadyNotification(F),
            ),
            row(
                "Required(Downloading) + VerificationFailed -> Required(Failed)",
                FORCED_WIFI,
                forcedDownloadedUser,
                VerificationFailed(11, FailureReason.SignatureMismatch),
                UpdateState.Required(F, UpdateStep.Failed(FailureReason.SignatureMismatch)),
                Effect.DeleteFile(PATH),
            ),
            row(
                "Required(Downloading) + DownloadFailed -> Required(Failed)",
                FORCED_WIFI,
                forcedDownloadingUser,
                DownloadFailed(11, FailureReason.Network),
                UpdateState.Required(F, UpdateStep.Failed(FailureReason.Network)),
            ),
            row(
                "Required(WaitingForUser, file ready) + StartDownload -> installs",
                AUTO,
                forcedReady,
                StartDownload,
                UpdateState.Required(F, UpdateStep.WaitingForUser),
                Effect.Install(PATH, F),
            ),
            row(
                "Required(WaitingForUser, file ready) + InstallIfReady -> installs",
                AUTO,
                forcedReady,
                InstallIfReady,
                UpdateState.Required(F, UpdateStep.WaitingForUser),
                Effect.Install(PATH, F),
            ),
            row(
                "Required + InstallNotAllowedNow -> Required(WaitingForUser)",
                AUTO,
                forcedReady + InstallIfReady,
                InstallNotAllowedNow,
                UpdateState.Required(F, UpdateStep.WaitingForUser),
            ),
            row(
                "Required + PermissionNeeded -> Required(PermissionNeeded)",
                AUTO,
                forcedReady + InstallIfReady,
                PermissionNeeded,
                UpdateState.Required(F, UpdateStep.PermissionNeeded),
            ),
            row(
                "Required(PermissionNeeded) + InstallIfReady -> opens the setting",
                AUTO,
                forcedReady + InstallIfReady + PermissionNeeded,
                InstallIfReady,
                UpdateState.Required(F, UpdateStep.PermissionNeeded),
                Effect.OpenInstallPermission,
            ),
            row(
                "Required(PermissionNeeded) + PermissionGranted -> installs",
                AUTO,
                forcedReady + InstallIfReady + PermissionNeeded,
                PermissionGranted,
                UpdateState.Required(F, UpdateStep.PermissionNeeded),
                Effect.Install(PATH, F),
            ),
            row(
                "Required + InstallStarted -> Required(Installing)",
                AUTO,
                forcedReady + InstallIfReady,
                InstallStarted,
                UpdateState.Required(F, UpdateStep.Installing),
            ),
            row(
                "Required(Installing) + InstallFinished(Cancelled) -> Required(Failed(Cancelled))",
                AUTO,
                forcedInstalling,
                InstallFinished(InstallResult.Cancelled),
                UpdateState.Required(F, UpdateStep.Failed(FailureReason.Cancelled)),
            ),
            row(
                "Required(Installing) + InstallFinished(Rejected) -> Required(Failed(InstallRejected))",
                AUTO,
                forcedInstalling,
                InstallFinished(InstallResult.Rejected),
                UpdateState.Required(F, UpdateStep.Failed(FailureReason.InstallRejected)),
            ),
            row(
                "Required(Installing) + InstallFinished(Success) -> UpToDate",
                AUTO,
                forcedInstalling,
                InstallFinished(InstallResult.Success),
                UpdateState.UpToDate,
                Effect.DeleteFile(PATH),
                Effect.CancelReadyNotification,
                Effect.ScheduleChecks,
            ),
            row(
                "Required(Installing) + InstallFinished(Blocked) -> Required(Unsupported)",
                AUTO,
                forcedInstalling,
                InstallFinished(InstallResult.Blocked),
                UpdateState.Required(F, UpdateStep.Unsupported),
                Effect.DeleteFile(PATH),
                Effect.CancelReadyNotification,
            ),
            row(
                "Required(Failed) + Retry -> Required(Downloading)",
                AUTO,
                listOf(found(F), DownloadFailed(11, FailureReason.Network)),
                Retry,
                UpdateState.Required(F, UpdateStep.Downloading(START)),
                Effect.EnqueueDownload(F, Network.Any, true),
            ),
            row(
                "Required(Failed(Cancelled)) + Retry -> installs the kept file",
                AUTO,
                forcedInstalling + InstallFinished(InstallResult.Cancelled),
                Retry,
                UpdateState.Required(F, UpdateStep.Failed(FailureReason.Cancelled)),
                Effect.Install(PATH, F),
            ),
            row(
                "Required + DeviceUnsupported -> Required(Unsupported)",
                MANUAL,
                forcedOffered,
                DeviceUnsupported,
                UpdateState.Required(F, UpdateStep.Unsupported),
            ),
            row(
                "Required(Unsupported) + StartDownload -> Required(Unsupported)",
                MANUAL,
                forcedOffered + DeviceUnsupported,
                StartDownload,
                UpdateState.Required(F, UpdateStep.Unsupported),
            ),
        )
    }
}
