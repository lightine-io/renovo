package io.lightine.renovo.internal.runtime

import android.app.Activity
import android.os.Looper
import io.lightine.renovo.FailureReason
import io.lightine.renovo.Network
import io.lightine.renovo.UpdateState
import io.lightine.renovo.UpdateStep
import io.lightine.renovo.internal.FetchResult
import io.lightine.renovo.internal.InstallPermission
import io.lightine.renovo.internal.VerificationResult
import io.lightine.renovo.internal.engine.EngineEvent
import io.lightine.renovo.internal.engine.InstallResult
import io.lightine.renovo.internal.store.PreferencesStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import kotlin.time.Duration.Companion.hours

/** The update flows end to end: the real engine and store, fake components. */
internal class DefaultRuntimeFlowTest : RuntimeTestBase() {
    private fun RuntimeHarness.requiredSteps(): List<String?> = states.filterIsInstance<UpdateState.Required>().map { it.step::class.simpleName }

    @Test
    fun `an optional update goes from check to install and ends up to date`() {
        val h = harness()

        h.startWithUpdate()

        // FR-1: the start schedules the periodic check and runs one check.
        assertEquals(listOf(6.hours), h.scheduler.intervals)
        assertEquals(listOf(false), h.scheduler.checks)
        assertEquals(listOf("Idle", "Checking", "Downloading"), h.stateNames)
        val request = h.downloader.enqueued.single()
        assertEquals(11L, request.info.versionCode)
        assertFalse(request.userInitiated)

        val file = h.finishDownload()

        assertTrue(h.state is UpdateState.ReadyToInstall)
        assertEquals(listOf(file), h.verifier.verified)
        assertEquals(listOf(INSTALLED_VERSION), h.verifier.installedVersionCodes)
        // The verified file is on disk before anything else happens (FR-34).
        assertTrue(
            PreferencesStore(context, null)
                .load()
                ?.download
                ?.verified == true,
        )

        h.runtime.installIfReady()
        h.settle()
        assertEquals(listOf(file), h.installer.installs.map { it.first })

        h.send(EngineEvent.InstallStarted)
        h.send(EngineEvent.InstallFinished(InstallResult.Success))

        // FR-17: the file is deleted, the notification removed, the next check scheduled.
        assertEquals(listOf("Idle", "Checking", "Downloading", "ReadyToInstall", "Installing", "UpToDate"), h.stateNames)
        assertFalse(file.exists())
        assertTrue(h.notifier.cancels >= 1)
        assertEquals(2, h.scheduler.intervals.size)
        assertNull(PreferencesStore(context, null).load()?.download)
        assertTrue(h.uncaught.isEmpty())
    }

    @Test
    fun `the check sends the stored ETag`() {
        val h = harness()
        h.startWithUpdate()

        h.runtime.checkNow()
        h.settle()

        assertEquals(listOf(null, "etag-11"), h.fetcher.etags)
        // The user's check shows as userInitiated.
        assertEquals(listOf(false, true), h.scheduler.checks)
    }

    @Test
    fun `an event sent before the start is handled after it`() {
        val h = harness()
        h.fetcher.result = FetchResult.NotModified

        h.runtime.checkNow()
        h.start()

        // The engine ignores events before the process start, so the loop must report the start first.
        assertEquals(listOf(false, true), h.scheduler.checks)
    }

    @Test
    fun `without autoDownload the update waits and startDownload downloads it`() {
        val h = harness { autoDownload = false }

        h.startWithUpdate()
        assertTrue(h.state is UpdateState.Available)
        assertTrue(h.downloader.enqueued.isEmpty())

        h.runtime.startDownload()
        h.settle()

        assertTrue(h.downloader.enqueued.single().userInitiated)
        assertTrue(h.state is UpdateState.Downloading)
    }

    @Test
    fun `a forced update walks through its steps and waits for the user`() {
        val h = harness()

        h.startWithUpdate(testInfo(mandatory = true))
        h.finishDownload()

        assertEquals(listOf("Downloading", "WaitingForUser"), h.requiredSteps().distinctConsecutive())
        // Nothing installs on its own: the user did not start this download.
        assertTrue(h.installer.installs.isEmpty())

        h.runtime.installIfReady()
        h.settle()
        assertEquals(1, h.installer.installs.size)
        h.send(EngineEvent.InstallStarted)
        assertEquals(UpdateStep.Installing, (h.state as UpdateState.Required).step)
    }

    @Test
    fun `a forced download the user started installs right after verification`() {
        val h = harness { autoDownload = false }
        h.startWithUpdate(testInfo(mandatory = true))
        assertEquals(UpdateStep.WaitingForUser, (h.state as UpdateState.Required).step)

        h.runtime.startDownload()
        h.settle()
        h.finishDownload()

        assertEquals(1, h.installer.installs.size)
        assertEquals(listOf("WaitingForUser", "Downloading", "WaitingForUser"), h.requiredSteps().distinctConsecutive())
    }

    @Test
    fun `no install starts while canInstallNow is false and it starts once it is true`() {
        var allowed = false
        var asked = 0
        val h =
            harness {
                canInstallNow = {
                    asked++
                    allowed
                }
            }
        h.startWithUpdate()
        h.finishDownload()

        h.runtime.installIfReady()
        h.settle()

        // AC-4
        assertEquals(1, asked)
        assertTrue(h.installer.installs.isEmpty())
        assertTrue(h.state is UpdateState.ReadyToInstall)

        allowed = true
        h.runtime.installIfReady()
        h.settle()

        assertEquals(2, asked)
        assertEquals(1, h.installer.installs.size)
    }

    @Test
    fun `a forced install the host app does not allow stays waiting`() {
        val h =
            harness {
                autoDownload = false
                canInstallNow = { false }
            }
        h.startWithUpdate(testInfo(mandatory = true))
        h.runtime.startDownload()
        h.settle()
        h.finishDownload()

        assertTrue(h.installer.installs.isEmpty())
        assertEquals(UpdateStep.WaitingForUser, (h.state as UpdateState.Required).step)
    }

    @Test
    fun `canInstallNow is asked on the main thread`() {
        var askedOnMain = false
        val h =
            harness(realThreads = true) {
                canInstallNow = {
                    askedOnMain = Looper.myLooper() === Looper.getMainLooper()
                    true
                }
            }
        h.fetcher.result = FetchResult.Found(testInfo(), null)
        h.start()
        waitUntil { h.state is UpdateState.Downloading }
        h.downloader.finish(11)
        waitUntil { h.state is UpdateState.ReadyToInstall }

        h.runtime.installIfReady()

        waitUntil { h.installer.installs.isNotEmpty() }
        assertTrue(askedOnMain)
    }

    @Test
    fun `an install that comes due in the background waits for the foreground`() {
        var asked = 0
        val h =
            harness(inForeground = false) {
                canInstallNow = {
                    asked++
                    true
                }
            }
        h.startWithUpdate()
        h.finishDownload()

        h.runtime.installIfReady()
        h.settle()

        // The installer needs the foreground, so it is neither asked nor run.
        assertEquals(0, asked)
        assertTrue(h.installer.installs.isEmpty())

        h.foreground.comeToForeground()
        h.settle()

        assertEquals(1, asked)
        assertEquals(1, h.installer.installs.size)
    }

    @Test
    fun `an install deferred for a file that is gone is dropped`() {
        val h = harness(inForeground = false)
        h.startWithUpdate()
        h.finishDownload()
        h.runtime.installIfReady()
        h.settle()

        // A newer version replaces the download while the app is away.
        h.runtime.submit(testInfo(versionCode = 12))
        h.settle()
        h.foreground.comeToForeground()
        h.settle()

        assertTrue(h.installer.installs.isEmpty())
    }

    @Test
    fun `a missing install permission is asked for and the install continues after the user returns`() {
        val h = harness()
        h.gate.status = InstallPermission.NeedsUser
        h.startWithUpdate()
        h.finishDownload()

        h.runtime.installIfReady()
        h.settle()

        assertTrue(h.state is UpdateState.PermissionNeeded)
        assertTrue(h.installer.installs.isEmpty())

        // The next tap opens the settings screen (FR-16).
        h.runtime.installIfReady()
        h.settle()
        assertEquals(1, h.gate.opens)

        // Back from the settings without the permission: nothing happens.
        h.foreground.comeToForeground()
        h.settle()
        assertTrue(h.installer.installs.isEmpty())
        assertTrue(h.state is UpdateState.PermissionNeeded)

        // Back with the permission: the install continues by itself.
        h.gate.status = InstallPermission.Granted
        h.foreground.comeToForeground()
        h.settle()
        assertEquals(1, h.installer.installs.size)
    }

    @Test
    fun `the permission step of a forced update continues the same way`() {
        val h = harness { autoDownload = false }
        h.gate.status = InstallPermission.NeedsUser
        h.startWithUpdate(testInfo(mandatory = true))
        h.runtime.startDownload()
        h.settle()
        h.finishDownload()
        assertEquals(UpdateStep.PermissionNeeded, (h.state as UpdateState.Required).step)

        h.gate.status = InstallPermission.Granted
        h.foreground.comeToForeground()
        h.settle()

        assertEquals(1, h.installer.installs.size)
    }

    @Test
    fun `the foreground callback is registered once`() {
        val h = harness()

        h.start()
        h.runtime.start()

        assertEquals(1, h.foreground.callbackCount)
    }

    @Test
    fun `a device that cannot install is reported as unsupported`() {
        val h = harness()
        h.gate.status = InstallPermission.Unsupported
        h.startWithUpdate()
        h.finishDownload()

        h.runtime.installIfReady()
        h.settle()

        assertEquals(UpdateState.Unsupported, h.state)
        assertTrue(h.installer.installs.isEmpty())
        // FR-18: the downloaded file is not kept for a device that cannot use it.
        assertFalse(h.downloadDirectory.resolve("11.apk").exists())
    }

    @Test
    fun `a settings screen that does not exist is reported as unsupported`() {
        val h = harness()
        h.gate.status = InstallPermission.NeedsUser
        h.gate.openResult = false
        h.startWithUpdate()
        h.finishDownload()
        h.runtime.installIfReady()
        h.settle()
        assertTrue(h.state is UpdateState.PermissionNeeded)

        h.runtime.installIfReady()
        h.settle()

        assertEquals(UpdateState.Unsupported, h.state)
    }

    @Test
    fun `an install result of blocked ends in unsupported`() {
        val h = harness()
        h.startWithUpdate()
        h.finishDownload()
        h.runtime.installIfReady()
        h.settle()

        h.send(EngineEvent.InstallStarted)
        h.send(EngineEvent.InstallFinished(InstallResult.Blocked))

        assertEquals(UpdateState.Unsupported, h.state)
    }

    @Test
    fun `a failed verification deletes the file and reports the reason`() {
        val h = harness()
        h.verifier.result = VerificationResult.Failed(FailureReason.SignatureMismatch)
        h.startWithUpdate()

        val file = h.finishDownload()

        val failed = h.state as UpdateState.Failed
        assertEquals(FailureReason.SignatureMismatch, failed.reason)
        assertFalse(file.exists())
    }

    @Test
    fun `a download that fails is reported and a retry downloads again`() {
        val h = harness()
        h.startWithUpdate()

        h.send(EngineEvent.DownloadFailed(11, FailureReason.Network))
        assertEquals(FailureReason.Network, (h.state as UpdateState.Failed).reason)

        h.runtime.startDownload()
        h.settle()

        assertEquals(2, h.downloader.enqueued.size)
        assertTrue(h.downloader.enqueued.last().userInitiated)
    }

    @Test
    fun `a waiting forced update downloads with mobile data once the user agrees`() {
        val h =
            harness {
                autoDownload = false
                forcedDownloadNetwork = Network.UnmeteredOnly
            }
        h.startWithUpdate(testInfo(mandatory = true))
        h.runtime.startDownload()
        h.settle()
        h.send(EngineEvent.DownloadWaitingForNetwork(11))
        assertEquals(UpdateStep.WaitingForNetwork(canUseMobileData = true), (h.state as UpdateState.Required).step)

        h.runtime.useMobileDataOnce()
        h.settle()

        assertEquals(Network.Any, h.downloader.enqueued.last().network)
    }

    @Test
    fun `an update from submit takes the same way as a check`() {
        val h = harness()
        h.start()

        h.runtime.submit(testInfo())
        h.settle()

        assertTrue(h.state is UpdateState.Downloading)
        assertEquals(1, h.downloader.enqueued.size)
    }

    @Test
    fun `restoring after process death offers a finished download without downloading again`() {
        val first = harness()
        first.startWithUpdate()
        val file = first.finishDownload()
        assertTrue(first.state is UpdateState.ReadyToInstall)
        first.close()

        // A new process: same store, same download folder, new runtime.
        val second = harness()
        second.start()

        assertTrue(second.state is UpdateState.ReadyToInstall)
        assertTrue(second.downloader.enqueued.isEmpty())
        assertTrue(file.exists())
        // FR-34, AC-14
        assertTrue(second.verifier.verified.isEmpty())
    }

    @Test
    fun `restoring finds the download gone and downloads it again`() {
        val first = harness()
        first.startWithUpdate()
        val file = first.finishDownload()
        first.close()
        assertTrue(file.delete())

        val second = harness()
        second.start()

        assertTrue(second.state is UpdateState.Downloading)
        assertEquals(11L, second.downloader.enqueued.single().info.versionCode)
    }

    @Test
    fun `restoring in the middle of a download continues it`() {
        val first = harness()
        first.startWithUpdate()
        assertTrue(first.state is UpdateState.Downloading)
        first.close()

        val second = harness()
        second.start()

        // The downloader resumes from its partial file; the runtime only has to ask again (AC-14).
        assertTrue(second.state is UpdateState.Downloading)
        assertEquals(11L, second.downloader.enqueued.single().info.versionCode)
    }

    @Test
    fun `restoring after the update was installed deletes the old file and starts clean`() {
        val first = harness()
        first.startWithUpdate()
        val file = first.finishDownload()
        first.close()

        // The app now runs version 11.
        val second = harness(installed = 11)
        second.start()

        assertEquals(UpdateState.UpToDate, second.state)
        assertFalse(file.exists())
    }

    @Test
    fun `restoring a stored forced update blocks again even when the check fails`() {
        val first = harness()
        first.startWithUpdate(testInfo(mandatory = true))
        first.close()

        val second = harness()
        second.fetcher.result = FetchResult.Failed(FailureReason.Network)
        second.start()

        assertTrue(second.state is UpdateState.Required)
    }

    @Test
    fun `the notification is shown when the update is ready and the app is in the background`() {
        val h = harness(inForeground = false)

        h.startWithUpdate()
        h.finishDownload()

        assertEquals(11L, h.notifier.shown.single().versionCode)
    }

    @Test
    fun `no notification is shown while the app is in the foreground`() {
        val h = harness(inForeground = true)

        h.startWithUpdate()
        h.finishDownload()

        assertTrue(h.state is UpdateState.ReadyToInstall)
        assertTrue(h.notifier.shown.isEmpty())
    }

    @Test
    fun `no notification is asked for when the setting is off`() {
        val h = harness(inForeground = false) { notifications = false }

        h.startWithUpdate()
        h.finishDownload()

        assertTrue(h.notifier.shown.isEmpty())
    }

    @Test
    fun `the notification permission request goes to the notifier`() {
        val h = harness()
        val activity =
            Robolectric
                .buildActivity(Activity::class.java)
                .get()

        h.runtime.requestNotificationPermission(activity)

        assertEquals(1, h.notifier.permissionRequests)
    }

    @Test
    fun `the runtime exposes the foreground tracker and the config of its components`() {
        val h = harness()

        assertNotNull(h.runtime.foreground)
        assertEquals(h.foreground, h.runtime.foreground)
        assertEquals(h.config, h.runtime.config)
    }

    private fun List<String?>.distinctConsecutive(): List<String?> = fold(mutableListOf()) { acc, item ->
        if (acc.lastOrNull() != item) acc += item
        acc
    }
}

/** Waits for [condition] while the main looper runs what the runtime posted to it. For tests with real threads. */
internal fun waitUntil(
    timeoutMillis: Long = 10_000,
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (true) {
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        if (System.currentTimeMillis() > deadline) throw AssertionError("The condition was not met in time.")
        Thread.sleep(10)
    }
}
