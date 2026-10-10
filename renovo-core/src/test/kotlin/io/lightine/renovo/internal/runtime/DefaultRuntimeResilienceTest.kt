package io.lightine.renovo.internal.runtime

import android.app.Activity
import io.lightine.renovo.FailureReason
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.UpdateState
import io.lightine.renovo.internal.InstallPermission
import io.lightine.renovo.internal.VerificationResult
import io.lightine.renovo.internal.engine.EngineEvent
import io.lightine.renovo.internal.engine.InstallResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Robolectric

/**
 * A component that fails must never crash the host app. Each test breaks one component on purpose,
 * checks the state it ends in, and checks that the runtime still handles the next event.
 */
internal class DefaultRuntimeResilienceTest : RuntimeTestBase() {
    private fun RuntimeHarness.assertNoCrash() {
        assertTrue("Uncaught: $uncaught", uncaught.isEmpty())
        // The loop is still alive: a new user check is handled.
        val before = scheduler.checks.size
        scheduler.checkFailure = null
        runtime.checkNow()
        settle()
        assertEquals(before + 1, scheduler.checks.size)
    }

    private fun RuntimeHarness.logged(fragment: String) = assertTrue("No log line with \"$fragment\" in $logs", logs.any { it.contains(fragment) })

    private fun RuntimeHarness.readyToInstall() {
        startWithUpdate()
        finishDownload()
        assertTrue(state is UpdateState.ReadyToInstall)
    }

    // ---- Store ---------------------------------------------------------------------------------

    @Test
    fun `a store that cannot load is treated as a first run`() {
        lateinit var store: FlakyStore
        val h = harness(wrapStore = { FlakyStore(it).also { flaky -> store = flaky } })
        store.loadFailure = FakeFailure()

        h.startWithUpdate()

        assertTrue(h.state is UpdateState.Downloading)
        h.logged("could not be loaded")
        h.assertNoCrash()
    }

    @Test
    fun `a store that cannot save does not stop the update`() {
        lateinit var store: FlakyStore
        val h = harness(wrapStore = { FlakyStore(it).also { flaky -> store = flaky } })
        store.saveFailure = FakeFailure()

        h.readyToInstall()

        h.logged("Saving the update state failed")
        h.assertNoCrash()
    }

    @Test
    fun `an installed version that cannot be read leaves the runtime idle`() {
        val h = harness()
        h.installedFailure = FakeFailure()

        h.start()

        assertEquals(UpdateState.Idle, h.state)
        h.logged("installed version could not be read")
        assertTrue(h.uncaught.isEmpty())
    }

    // ---- Scheduler and fetcher -----------------------------------------------------------------

    @Test
    fun `a scheduler that cannot start a check reports a failed check`() {
        val h = harness()
        h.scheduler.checkFailure = FakeFailure()
        h.start()

        // The start-up check is a background one: its failure stays unseen.
        assertEquals(UpdateState.Idle, h.state)

        h.runtime.checkNow()
        h.settle()

        val failed = h.state as UpdateState.Failed
        assertTrue(failed.userInitiated)
        h.logged("Starting the check failed")
        h.assertNoCrash()
    }

    @Test
    fun `a scheduler that cannot schedule the periodic check does not stop the first check`() {
        val h = harness()
        h.scheduler.scheduleFailure = FakeFailure()

        h.startWithUpdate()

        assertTrue(h.state is UpdateState.Downloading)
        h.logged("Scheduling the periodic check failed")
        h.assertNoCrash()
    }

    @Test
    fun `a fetcher that throws becomes a failed check`() {
        val h = harness()
        h.start()
        h.fetcher.failure = FakeFailure()

        h.runtime.checkNow()
        h.settle()

        assertEquals(FailureReason.Parse, (h.state as UpdateState.Failed).reason)
        h.assertNoCrash()
    }

    // ---- Downloader ----------------------------------------------------------------------------

    @Test
    fun `a downloader that cannot queue turns into a failed download`() {
        val h = harness()
        h.downloader.enqueueFailure = FakeFailure()

        h.startWithUpdate()

        val failed = h.state as UpdateState.Failed
        assertEquals(FailureReason.Network, failed.reason)
        h.logged("Queueing the download failed")
        h.assertNoCrash()
    }

    @Test
    fun `a downloader that cannot cancel does not stop the next download`() {
        val h = harness()
        h.startWithUpdate()
        h.downloader.cancelFailure = FakeFailure()

        // A newer version replaces the running download: cancel, then enqueue.
        h.runtime.submit(testInfo(versionCode = 12))
        h.settle()

        h.logged("Cancelling the download failed")
        assertEquals(12L, h.downloader.enqueued.last().info.versionCode)
        h.assertNoCrash()
    }

    // ---- Verifier ------------------------------------------------------------------------------

    @Test
    fun `a verifier that throws rejects the file`() {
        val h = harness()
        h.verifier.failure = FakeFailure()
        h.startWithUpdate()

        val file = h.finishDownload()

        assertEquals(FailureReason.HashMismatch, (h.state as UpdateState.Failed).reason)
        assertFalse(file.exists())
        h.logged("Verifying the download failed")
        h.assertNoCrash()
    }

    // ---- Install -------------------------------------------------------------------------------

    @Test
    fun `an installer that throws tells the user the install failed`() {
        val h = harness()
        h.readyToInstall()
        h.installer.failure = FakeFailure()

        h.runtime.installIfReady()
        h.settle()

        val failed = h.state as UpdateState.Failed
        assertEquals(FailureReason.InstallRejected, failed.reason)
        h.logged("installer failed")
        h.assertNoCrash()
    }

    @Test
    fun `a permission gate that cannot answer postpones the install`() {
        val h = harness()
        h.readyToInstall()
        h.gate.statusFailure = FakeFailure()

        h.runtime.installIfReady()
        h.settle()

        assertTrue(h.state is UpdateState.ReadyToInstall)
        assertTrue(h.installer.installs.isEmpty())
        h.logged("install permission failed")

        // Not a permanent verdict: once the gate answers, the install goes ahead.
        h.gate.statusFailure = null
        h.runtime.installIfReady()
        h.settle()
        assertEquals(1, h.installer.installs.size)
        h.assertNoCrash()
    }

    @Test
    fun `a settings screen that cannot open leaves the state as it was`() {
        val h = harness()
        h.gate.status = InstallPermission.NeedsUser
        h.readyToInstall()
        h.runtime.installIfReady()
        h.settle()
        h.gate.openFailure = FakeFailure()

        h.runtime.installIfReady()
        h.settle()

        assertTrue(h.state is UpdateState.PermissionNeeded)
        h.logged("permission screen failed")
        h.assertNoCrash()
    }

    @Test
    fun `a foreground check of the permission that throws is contained`() {
        val h = harness()
        h.gate.status = InstallPermission.NeedsUser
        h.readyToInstall()
        h.runtime.installIfReady()
        h.settle()
        h.gate.statusFailure = FakeFailure()

        // The tracker would catch this too, but a failure here must not even reach it.
        h.foreground.comeToForeground()
        h.settle()

        assertTrue(h.state is UpdateState.PermissionNeeded)
        h.logged("Checking the install permission on return failed")
        h.assertNoCrash()
    }

    @Test
    fun `a canInstallNow that throws postpones the install`() {
        val h = harness { canInstallNow = { throw FakeFailure() } }
        h.readyToInstall()

        h.runtime.installIfReady()
        h.settle()

        assertTrue(h.state is UpdateState.ReadyToInstall)
        assertTrue(h.installer.installs.isEmpty())
        h.logged("canInstallNow threw")
        h.assertNoCrash()
    }

    // ---- Notifier ------------------------------------------------------------------------------

    @Test
    fun `a notifier that cannot show does not stop the update`() {
        val h = harness(inForeground = false)
        h.notifier.failure = FakeFailure()

        h.readyToInstall()

        h.logged("Showing the update ready notification failed")
        h.assertNoCrash()
    }

    @Test
    fun `a notifier that cannot cancel does not stop the finish of an update`() {
        val h = harness()
        h.readyToInstall()
        h.runtime.installIfReady()
        h.settle()
        h.notifier.failure = FakeFailure()

        h.send(EngineEvent.InstallStarted)
        h.send(EngineEvent.InstallFinished(InstallResult.Success))

        assertEquals(UpdateState.UpToDate, h.state)
        h.logged("Removing the update ready notification failed")
        h.assertNoCrash()
    }

    @Test
    fun `a notifier that cannot ask for the permission does not crash the host app`() {
        val h = harness()
        h.notifier.failure = FakeFailure()
        val activity = Robolectric.buildActivity(Activity::class.java).get()

        h.runtime.requestNotificationPermission(activity)

        h.logged("notification permission failed")
    }

    // ---- Logger and files ----------------------------------------------------------------------

    @Test
    fun `a logger that throws does not undo the handling of a failure`() {
        val h = harness { logger = RenovoLogger { _, _ -> throw FakeFailure() } }
        h.downloader.enqueueFailure = FakeFailure()

        h.startWithUpdate()

        assertEquals(FailureReason.Network, (h.state as UpdateState.Failed).reason)
        assertTrue(h.uncaught.isEmpty())
    }

    @Test
    fun `a file that cannot be deleted is logged`() {
        val h = harness()
        h.startWithUpdate()
        val file = h.downloader.finish(11)
        // A directory with a child cannot be deleted like a file. It stands in for a failing delete.
        file.delete()
        file.mkdirs()
        file.resolve("child").writeText("x")
        h.verifier.result = VerificationResult.Failed(FailureReason.HashMismatch)
        h.settle()

        h.logged("could not be deleted")
        assertTrue(h.uncaught.isEmpty())
    }
}
