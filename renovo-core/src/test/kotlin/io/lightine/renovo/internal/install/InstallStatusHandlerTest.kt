package io.lightine.renovo.internal.install

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import io.lightine.renovo.internal.engine.EngineEvent
import io.lightine.renovo.internal.engine.InstallResult
import io.lightine.renovo.internal.notify.ActivityForegroundTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private const val SESSION = 7

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 29, 33, 37])
class InstallStatusHandlerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sink = RecordingSink()
    private val logger = RecordingLogger()
    private val sessions = InstallSessions().apply { opened(SESSION) }
    private val confirmation = Intent("android.content.pm.action.CONFIRM_INSTALL")

    private fun handler(
        foreground: FakeForeground = FakeForeground(),
        installedVersionCode: () -> Long? = { null },
    ) = InstallStatusHandler(context, sink, logger, foreground, sessions, installedVersionCode = installedVersionCode)

    private fun status(
        status: Int,
        session: Int = SESSION,
    ): Intent = Intent()
        .putExtra(PackageInstaller.EXTRA_STATUS, status)
        .putExtra(PackageInstaller.EXTRA_SESSION_ID, session)
        .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "INSTALL_FAILED_TEST")

    private fun resumedActivity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun finished(result: InstallResult) = listOf(EngineEvent.InstallFinished(result))

    @Test
    fun `success is reported once, and a repeated status is ignored`() {
        val handler = handler()
        handler.handle(status(PackageInstaller.STATUS_SUCCESS))
        handler.handle(status(PackageInstaller.STATUS_SUCCESS))

        assertEquals(finished(InstallResult.Success), sink.events)
        assertNull(sessions.openSessionId)
    }

    @Test
    fun `aborted means the user cancelled`() {
        handler().handle(status(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertEquals(finished(InstallResult.Cancelled), sink.events)
    }

    @Test
    fun `aborted is success when the version of the session is already installed`() {
        sessions.opened(SESSION, targetVersionCode = 2L)
        handler(installedVersionCode = { 2L }).handle(status(PackageInstaller.STATUS_FAILURE_ABORTED))

        assertEquals(finished(InstallResult.Success), sink.events)
        assertNull(sessions.openSessionId)
        assertTrue(logger.contains("new version is installed"))
    }

    @Test
    fun `aborted stays cancelled while the old version is installed`() {
        sessions.opened(SESSION, targetVersionCode = 2L)
        handler(installedVersionCode = { 1L }).handle(status(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertEquals(finished(InstallResult.Cancelled), sink.events)
    }

    @Test
    fun `aborted stays cancelled when the installed version cannot be read`() {
        sessions.opened(SESSION, targetVersionCode = 2L)
        handler(installedVersionCode = { null }).handle(status(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertEquals(finished(InstallResult.Cancelled), sink.events)
    }

    @Test
    fun `aborted stays cancelled when the session has no known target`() {
        // The session of setUp has no target, so even a matching installed version proves nothing.
        handler(installedVersionCode = { 2L }).handle(status(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertEquals(finished(InstallResult.Cancelled), sink.events)
    }

    @Test
    @Config(sdk = [37])
    fun `a developer verification rejection stays rejected even if the version matches`() {
        sessions.opened(SESSION, targetVersionCode = 2L)
        handler(installedVersionCode = { 2L }).handle(
            status(PackageInstaller.STATUS_FAILURE_ABORTED).putExtra(
                PackageInstaller.EXTRA_DEVELOPER_VERIFICATION_FAILURE_REASON,
                PackageInstaller.DEVELOPER_VERIFICATION_FAILED_REASON_DEVELOPER_BLOCKED,
            ),
        )
        assertEquals(finished(InstallResult.Rejected), sink.events)
    }

    @Test
    fun `blocked is reported as blocked with the message logged`() {
        handler().handle(status(PackageInstaller.STATUS_FAILURE_BLOCKED))
        assertEquals(finished(InstallResult.Blocked), sink.events)
        assertTrue(logger.contains("INSTALL_FAILED_TEST"))
    }

    @Test
    fun `every other failure is rejected with the message logged`() {
        val failures =
            listOf(
                PackageInstaller.STATUS_FAILURE,
                PackageInstaller.STATUS_FAILURE_INVALID,
                PackageInstaller.STATUS_FAILURE_CONFLICT,
                PackageInstaller.STATUS_FAILURE_STORAGE,
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            )
        for ((index, failure) in failures.withIndex()) {
            val session = 100 + index
            sessions.opened(session)
            handler().handle(status(failure, session))
        }

        assertEquals(failures.map { EngineEvent.InstallFinished(InstallResult.Rejected) }, sink.events)
        assertTrue(logger.contains("status ${PackageInstaller.STATUS_FAILURE_CONFLICT}: INSTALL_FAILED_TEST"))
    }

    @Test
    fun `a status for a session that is not open is ignored`() {
        handler().handle(status(PackageInstaller.STATUS_SUCCESS, session = 99))
        assertTrue(sink.events.isEmpty())
        assertEquals(SESSION, sessions.openSessionId)
    }

    @Test
    fun `a broadcast without a status is ignored`() {
        handler().handle(Intent().putExtra(PackageInstaller.EXTRA_SESSION_ID, SESSION))
        assertTrue(sink.events.isEmpty())
        assertEquals(SESSION, sessions.openSessionId)
    }

    @Test
    fun `pending user action in the foreground launches the confirmation at once`() {
        val activity = resumedActivity()

        handler(FakeForeground(activity)).handle(
            status(PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, confirmation),
        )

        assertEquals(confirmation.action, shadowOf(activity).nextStartedActivity.action)
        assertTrue("the session stays open until its final status", sink.events.isEmpty())
        assertEquals(SESSION, sessions.openSessionId)
    }

    @Test
    fun `pending user action in the background waits for the next foreground`() {
        val foreground = FakeForeground()
        val handler = handler(foreground)

        handler.handle(status(PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, confirmation))
        // A second request while waiting registers no second callback.
        handler.handle(status(PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, confirmation))
        assertEquals(1, foreground.callbackCount)

        val activity = resumedActivity()
        assertNull(shadowOf(activity).nextStartedActivity)
        foreground.comeToForeground(activity)

        assertEquals(confirmation.action, shadowOf(activity).nextStartedActivity.action)
        // Shown once: coming to the foreground again does not repeat it.
        foreground.comeToForeground(activity)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `a deferred confirmation is launched when the user returns, with the real tracker`() {
        // The real tracker, not the fake: it decides when the callback runs relative to the resume.
        val tracker = ActivityForegroundTracker(context as Application)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        // The user left the app while the APK was copied into the session.
        controller.pause().stop()
        val handler = InstallStatusHandler(context, sink, logger, tracker, sessions)

        handler.handle(status(PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, confirmation))
        assertNull(shadowOf(controller.get()).nextStartedActivity)

        controller.restart().start().resume()

        assertEquals(confirmation.action, shadowOf(controller.get()).nextStartedActivity?.action)
        assertEquals(SESSION, sessions.openSessionId)
    }

    @Test
    fun `a deferred confirmation is withdrawn when the app is not ready to install any more`() {
        var safe = true
        val foreground = FakeForeground()
        val handler = InstallStatusHandler(context, sink, logger, foreground, sessions, canInstallNow = { safe })
        handler.handle(status(PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, confirmation))

        // Unsent data appeared while the user was away.
        safe = false
        val activity = resumedActivity()
        foreground.comeToForeground(activity)

        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(listOf(EngineEvent.InstallNotAllowedNow), sink.events)
        assertNull("the session is given up, so the next tap can start a new one", sessions.openSessionId)
    }

    @Test
    fun `a canInstallNow that throws withdraws a deferred confirmation too`() {
        val foreground = FakeForeground()
        val handler =
            InstallStatusHandler(context, sink, logger, foreground, sessions, canInstallNow = { error("host failure") })
        handler.handle(status(PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, confirmation))

        val activity = resumedActivity()
        foreground.comeToForeground(activity)

        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(listOf(EngineEvent.InstallNotAllowedNow), sink.events)
        assertTrue(logger.contains("canInstallNow threw"))
    }

    @Test
    fun `a deferred confirmation is dropped once the session has ended`() {
        val foreground = FakeForeground()
        val handler = handler(foreground)
        handler.handle(status(PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, confirmation))
        handler.handle(status(PackageInstaller.STATUS_FAILURE_ABORTED))

        val activity = resumedActivity()
        foreground.comeToForeground(activity)

        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(finished(InstallResult.Cancelled), sink.events)
    }

    @Test
    fun `pending user action without a confirmation screen is rejected`() {
        handler(FakeForeground(resumedActivity())).handle(status(PackageInstaller.STATUS_PENDING_USER_ACTION))
        assertEquals(finished(InstallResult.Rejected), sink.events)
    }

    @Test
    fun `a missing system confirmation screen is blocked`() {
        val activity = Robolectric.buildActivity(NoScreensActivity::class.java).get()

        handler(FakeForeground(activity)).handle(
            status(PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, confirmation),
        )

        assertEquals(finished(InstallResult.Blocked), sink.events)
    }

    @Test
    @Config(sdk = [37])
    fun `an abort with a developer verification reason is rejected and the reason logged`() {
        handler().handle(
            status(PackageInstaller.STATUS_FAILURE_ABORTED).putExtra(
                PackageInstaller.EXTRA_DEVELOPER_VERIFICATION_FAILURE_REASON,
                PackageInstaller.DEVELOPER_VERIFICATION_FAILED_REASON_DEVELOPER_BLOCKED,
            ),
        )

        assertEquals(finished(InstallResult.Rejected), sink.events)
        assertTrue(logger.contains("developer verification: developer blocked"))
    }

    @Test
    fun `the receiver does nothing before init`() {
        // Renovo.init was never called in this test process, so there is no runtime.
        InstallStatusReceiver().onReceive(context, status(PackageInstaller.STATUS_SUCCESS))
        assertEquals(SESSION, sessions.openSessionId)
        assertFalse(InstallSessions.shared.isOpen(SESSION))
    }
}
