package io.lightine.renovo.internal.install

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.internal.engine.EngineEvent
import io.lightine.renovo.internal.engine.InstallResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowPackageInstaller
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.Executor

private val directExecutor = Executor { it.run() }

private val info = UpdateInfo(2, "2.0", "https://example.com/app-2.apk")

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 29, 33, 37])
class SessionInstallerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val packageInstaller: PackageInstaller = context.packageManager.packageInstaller
    private val sink = RecordingSink()
    private val logger = RecordingLogger()
    private val sessions = InstallSessions()

    private fun installer(stagingExecutor: Executor = directExecutor) = SessionInstaller(context, sink, logger, sessions, stagingExecutor, directExecutor)

    private fun apk(): File = folder.newFile().apply { writeBytes(ByteArray(4096) { it.toByte() }) }

    private fun committedReceiver(sessionId: Int): IntentSender? {
        val session = packageInstaller.openSession(sessionId)
        val shadow = Shadow.extract<ShadowPackageInstaller.ShadowSession>(session)
        return ReflectionHelpers.getField(shadow, "statusReceiver")
    }

    @Test
    fun `the session is created for this package, filled, committed, and reported as started`() {
        installer().install(apk(), info)

        val sessionId = checkNotNull(sessions.openSessionId)
        val sessionInfo = checkNotNull(packageInstaller.getSessionInfo(sessionId))
        assertEquals(context.packageName, sessionInfo.appPackageName)
        // The shadow refuses a commit while a stream is open, so a commit proves the stream was closed.
        assertNotNull(committedReceiver(sessionId))
        assertEquals(listOf(EngineEvent.InstallStarted), sink.events)
    }

    @Test
    fun `the open session remembers the version code it installs`() {
        installer().install(apk(), info)
        assertEquals(info.versionCode, sessions.openTargetVersionCode)
    }

    @Test
    fun `the status callback is a mutable broadcast to the receiver with the session id`() {
        installer().install(apk(), info)
        val sessionId = checkNotNull(sessions.openSessionId)

        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_NO_CREATE
            }
        val pendingIntent = PendingIntent.getBroadcast(context, sessionId, Intent(context, InstallStatusReceiver::class.java), flags)
        assertNotNull("no status PendingIntent for the session's request code", pendingIntent)
        val shadow = shadowOf(pendingIntent)
        assertTrue(shadow.isBroadcast)
        assertFalse(shadow.isImmutable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(shadow.flags and PendingIntent.FLAG_MUTABLE != 0)
        }
        assertEquals(ComponentName(context, InstallStatusReceiver::class.java), shadow.savedIntent.component)
        assertEquals(sessionId, shadow.savedIntent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1))
        assertEquals(pendingIntent.intentSender, committedReceiver(sessionId))
    }

    @Test
    fun `the session settings match the running API level`() {
        val params = sessionParams(context.packageName, 4096)

        assertEquals(PackageInstaller.SessionParams.MODE_FULL_INSTALL, ReflectionHelpers.getField<Int>(params, "mode"))
        assertEquals(context.packageName, ReflectionHelpers.getField<String>(params, "appPackageName"))
        assertEquals(4096L, ReflectionHelpers.getField<Long>(params, "sizeBytes"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertEquals(
                PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED,
                ReflectionHelpers.getField<Int>(params, "requireUserAction"),
            )
            assertEquals(PackageManager.INSTALL_SCENARIO_FAST, ReflectionHelpers.getField<Int>(params, "installScenario"))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertEquals(PackageInstaller.PACKAGE_SOURCE_OTHER, ReflectionHelpers.getField<Int>(params, "packageSource"))
        }
    }

    @Test
    fun `a second install while a session is open does nothing`() {
        val first = installer()
        first.install(apk(), info)
        val queued = mutableListOf<Runnable>()

        installer(stagingExecutor = { queued += it }).install(apk(), info)

        assertTrue("the second install must not start any work", queued.isEmpty())
        assertTrue(logger.contains("already running"))
        assertEquals(listOf(EngineEvent.InstallStarted), sink.events)
    }

    @Test
    fun `a file that cannot be read abandons the session and is rejected`() {
        val missing = File(folder.root, "missing.apk")

        installer().install(missing, info)

        assertEquals(listOf(EngineEvent.InstallFinished(InstallResult.Rejected)), sink.events)
        assertNull(sessions.openSessionId)
        assertTrue("the session must be abandoned", packageInstaller.mySessions.isEmpty())
        // The claim is released, so the next install can run.
        assertTrue(sessions.tryBegin())
    }

    @Test
    fun `sessions left over from an earlier process are abandoned`() {
        val leftover =
            packageInstaller.createSession(
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                },
            )

        installer().install(apk(), info)

        assertNull(packageInstaller.getSessionInfo(leftover))
        assertNotNull(packageInstaller.getSessionInfo(checkNotNull(sessions.openSessionId)))
    }

    @Test
    @Config(shadows = [RefusingPackageInstaller::class])
    fun `a security exception from createSession is blocked`() {
        installer().install(apk(), info)

        assertEquals(listOf(EngineEvent.InstallFinished(InstallResult.Blocked)), sink.events)
        assertNull(sessions.openSessionId)
        assertTrue(sessions.tryBegin())
    }

    // The three tests below use a direct executor, so an exception that escaped the installer would
    // fail the test itself, as it would crash the host app's thread in production.

    @Test
    @Config(shadows = [BrokenCreatePackageInstaller::class])
    fun `a runtime exception from createSession is rejected and releases the claim`() {
        installer().install(apk(), info)

        assertEquals(listOf(EngineEvent.InstallFinished(InstallResult.Rejected)), sink.events)
        assertTrue(logger.contains("Could not create an installer session"))
        assertNull(sessions.openSessionId)
        assertTrue(sessions.tryBegin())
    }

    @Test
    @Config(shadows = [BrokenOpenPackageInstaller::class])
    fun `a runtime exception while staging abandons the session and is rejected`() {
        installer().install(apk(), info)

        assertEquals(listOf(EngineEvent.InstallFinished(InstallResult.Rejected)), sink.events)
        assertTrue(logger.contains("Could not copy the APK"))
        assertNull(sessions.openSessionId)
        assertTrue("the session must be abandoned", packageInstaller.mySessions.isEmpty())
        assertTrue(sessions.tryBegin())
    }

    @Test
    @Config(shadows = [BrokenCommitSession::class])
    fun `a runtime exception while committing abandons the session and is rejected`() {
        installer().install(apk(), info)

        assertEquals("no start is reported for a commit that failed", listOf(EngineEvent.InstallFinished(InstallResult.Rejected)), sink.events)
        assertTrue(logger.contains("Could not commit the installer session"))
        assertNull(sessions.openSessionId)
        assertTrue("the session must be abandoned", packageInstaller.mySessions.isEmpty())
        assertTrue(sessions.tryBegin())
    }

    @Test
    fun `an executor that refuses the work does not leave the install claimed`() {
        installer(stagingExecutor = { throw java.util.concurrent.RejectedExecutionException("shut down") }).install(apk(), info)

        assertEquals(listOf(EngineEvent.InstallFinished(InstallResult.Rejected)), sink.events)
        assertTrue(sessions.tryBegin())
    }
}

/** A system server that fails with something other than the documented exceptions. */
@Implements(PackageInstaller::class)
class BrokenCreatePackageInstaller : ShadowPackageInstaller() {
    @Implementation
    override fun createSession(params: PackageInstaller.SessionParams): Int = throw IllegalStateException("The system server is not available.")
}

/** Creates the session fine, then fails to open it for writing. */
@Implements(PackageInstaller::class)
class BrokenOpenPackageInstaller : ShadowPackageInstaller() {
    @Implementation
    override fun openSession(sessionId: Int): PackageInstaller.Session = throw IllegalStateException("The system server is not available.")
}

/** A session whose commit fails with an unexpected exception. */
@Implements(PackageInstaller.Session::class)
class BrokenCommitSession : ShadowPackageInstaller.ShadowSession() {
    @Implementation
    override fun commit(statusReceiver: IntentSender): Unit = throw IllegalStateException("The system server is not available.")
}

/** Behaves like a restricted user, where the reference says createSession throws. */
@Implements(PackageInstaller::class)
class RefusingPackageInstaller : ShadowPackageInstaller() {
    @Implementation
    override fun createSession(params: PackageInstaller.SessionParams): Int = throw SecurityException("installs are not available")
}
