package io.lightine.renovo.internal.install

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.internal.Installer
import io.lightine.renovo.internal.engine.EngineEvent
import io.lightine.renovo.internal.engine.EventSink
import io.lightine.renovo.internal.engine.InstallResult
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Installs the APK with a `PackageInstaller` session, on every supported API level. The few
 * differences between levels are the small functions at the end of this file.
 *
 * The file is copied into the session on [stagingExecutor], because an APK of tens of megabytes
 * plus `fsync` is too slow for the main thread. The commit and the [EngineEvent.InstallStarted]
 * event then run on [mainExecutor]. Status broadcasts are delivered on the main thread too, so a
 * fast status can never reach the engine before the start event.
 */
internal class SessionInstaller(
    private val context: Context,
    private val events: EventSink,
    private val logger: RenovoLogger?,
    private val sessions: InstallSessions = InstallSessions.shared,
    private val stagingExecutor: Executor = newStagingExecutor(),
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context),
) : Installer {
    override fun install(
        file: File,
        info: UpdateInfo,
    ) {
        if (!sessions.tryBegin()) {
            logger?.log("An install is already running. The new request is ignored.", null)
            return
        }
        try {
            stagingExecutor.execute { stage(file, info.versionCode) }
        } catch (e: RuntimeException) {
            // The claim above must not outlive a request that never started.
            failBeforeSession(InstallResult.Rejected, "Could not start the install.", e)
        }
    }

    // Runs on a thread of our own. An exception that leaves it would reach the thread's handler,
    // which crashes the host app, so every RuntimeException is caught and turned into "rejected".
    private fun stage(
        file: File,
        targetVersionCode: Long,
    ) {
        val installer: PackageInstaller
        val length: Long
        try {
            installer = context.packageManager.packageInstaller
            abandonLeftoverSessions(installer)
            length = file.length()
        } catch (e: RuntimeException) {
            failBeforeSession(InstallResult.Rejected, "Could not reach the installer.", e)
            return
        }
        val sessionId =
            try {
                installer.createSession(sessionParams(context.packageName, length))
            } catch (e: SecurityException) {
                // The reference names restricted users as one case: installs are not possible here.
                failBeforeSession(InstallResult.Blocked, "The system does not allow installer sessions here.", e)
                return
            } catch (e: IOException) {
                failBeforeSession(InstallResult.Rejected, "Could not create an installer session.", e)
                return
            } catch (e: IllegalArgumentException) {
                failBeforeSession(InstallResult.Rejected, "The installer refused the session settings.", e)
                return
            } catch (e: RuntimeException) {
                // For example an IllegalStateException, or a failed call to the system server.
                failBeforeSession(InstallResult.Rejected, "Could not create an installer session.", e)
                return
            }
        sessions.opened(sessionId, targetVersionCode)

        val staged =
            runOrFail(sessionId, "Could not copy the APK into the installer session.") {
                installer.openSession(sessionId).use { session ->
                    session.openWrite(APK_NAME, 0, length).use { out ->
                        file.inputStream().use { input -> input.copyTo(out) }
                        // The system may install right after commit; the bytes must be on disk first.
                        session.fsync(out)
                    }
                }
            }
        if (staged) {
            runOrFail(sessionId, "Could not hand the commit to the main thread.") { mainExecutor.execute { commit(sessionId) } }
        }
    }

    private fun commit(sessionId: Int) {
        val committed = runOrFail(sessionId, "Could not commit the installer session.") { commitNow(context, sessionId) }
        if (committed) events.send(EngineEvent.InstallStarted)
    }

    /** Runs [block]. On a failure the session is abandoned and reported as rejected. */
    private inline fun runOrFail(
        sessionId: Int,
        message: String,
        block: () -> Unit,
    ): Boolean {
        val error: Exception =
            try {
                block()
                return true
            } catch (e: IOException) {
                e
            } catch (e: RuntimeException) {
                // IllegalArgumentException and SecurityException among others. Nothing may leave
                // here: this runs on our own thread and on the main thread of the host app.
                e
            }
        logger?.log(message, error)
        abandonQuietly(context, sessionId)
        if (sessions.finish(sessionId)) events.send(EngineEvent.InstallFinished(InstallResult.Rejected))
        return false
    }

    private fun failBeforeSession(
        result: InstallResult,
        message: String,
        error: Exception,
    ) {
        logger?.log(message, error)
        sessions.cancelBegin()
        events.send(EngineEvent.InstallFinished(result))
    }

    // A session of a previous process is never finished by anyone: its status went to a process
    // that no longer exists. Abandoning it lets the system delete its copy of the APK now instead
    // of after about a day. Only called while no session of this process is open.
    private fun abandonLeftoverSessions(installer: PackageInstaller) {
        for (session in installer.mySessions) {
            if (session.appPackageName == context.packageName) {
                logger?.log("Abandoned an installer session left over from an earlier run.", null)
                abandonQuietly(context, session.sessionId)
            }
        }
    }

    private companion object {
        // Any unique name works; one session holds one APK.
        const val APK_NAME = "renovo-update.apk"
    }
}

private fun newStagingExecutor(): Executor = // One thread at most, and none while idle, so an app that never updates keeps no thread.
    ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { runnable ->
        Thread(runnable, "renovo-install")
    }

/** The settings of an installer session for [packageName]. Internal so tests can read them. */
internal fun sessionParams(
    packageName: String,
    length: Long,
): PackageInstaller.SessionParams = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
    // The install fails if the APK is for another package, a second guard behind the verifier.
    setAppPackageName(packageName)
    // Lets the system check free space before the copy starts.
    if (length > 0) setSize(length)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) requestNoUserAction(this)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) markSourceOther(this)
}

// A self-update may skip the confirmation from API 31 when the app holds
// UPDATE_PACKAGES_WITHOUT_USER_ACTION. The system still asks when its other conditions fail, so
// the status receiver always handles "pending user action".
@RequiresApi(Build.VERSION_CODES.S)
private fun requestNoUserAction(params: PackageInstaller.SessionParams) {
    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
    // The user tapped "Install": the fastest path to a running app fits best.
    params.setInstallScenario(PackageManager.INSTALL_SCENARIO_FAST)
}

// The reference asks installers to say OTHER when no store or download source fits.
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun markSourceOther(params: PackageInstaller.SessionParams) {
    params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER)
}
