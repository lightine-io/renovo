package io.lightine.renovo.internal.install

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import io.lightine.renovo.Renovo
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.internal.ForegroundTracker
import io.lightine.renovo.internal.engine.EngineEvent
import io.lightine.renovo.internal.engine.EventSink
import io.lightine.renovo.internal.engine.InstallResult

/**
 * Receives the status of an installer session from the system.
 *
 * Declared in the manifest with `exported="false"`: only the `PendingIntent` that Renovo hands to
 * the system can reach it, so the confirmation intent it launches comes from the system installer.
 */
internal class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // Without init there is no engine to report to and no logger to log to. The session ends
        // on its own; the next start after init sees the installed version code.
        val runtime = Renovo.runtimeOrNull ?: return
        InstallStatusHandler(
            context = context.applicationContext,
            events = runtime.events,
            logger = runtime.logger,
            foreground = runtime.foreground,
            sessions = InstallSessions.shared,
            canInstallNow = runtime.config.canInstallNow,
        ).handle(intent)
    }
}

/**
 * Maps one session status to engine events. Kept apart from the receiver so tests can build it.
 *
 * @param canInstallNow The host app's setting. Asked again before a confirmation that waited for
 *   the user to come back is shown.
 */
internal class InstallStatusHandler(
    private val context: Context,
    private val events: EventSink,
    private val logger: RenovoLogger?,
    private val foreground: ForegroundTracker,
    private val sessions: InstallSessions,
    private val canInstallNow: () -> Boolean = { true },
    private val installedVersionCode: () -> Long? = { readInstalledVersionCode(context) },
) {
    fun handle(intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, NO_VALUE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, NO_VALUE)
        if (status == NO_VALUE || sessionId == NO_VALUE) {
            logger?.log("An install status arrived without a status or a session id. Ignored.", null)
            return
        }
        if (!sessions.isOpen(sessionId)) {
            ignoreUnknownSession(sessionId, status)
            return
        }
        when {
            status == PackageInstaller.STATUS_PENDING_USER_ACTION -> askUser(sessionId, intent)

            status == PackageInstaller.STATUS_SUCCESS -> finish(sessionId, InstallResult.Success)

            status == PackageInstaller.STATUS_FAILURE_ABORTED -> onAborted(sessionId, intent)

            status == PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                logger?.log("The install was blocked: ${statusMessage(intent)}", null)
                finish(sessionId, InstallResult.Blocked)
            }

            else -> {
                logger?.log("The install failed with status $status: ${statusMessage(intent)}", null)
                finish(sessionId, InstallResult.Rejected)
            }
        }
    }

    // A status for a session this process does not track is either a repeat for a finished
    // session or one for a session of an earlier process. A confirmation for the latter would
    // never be answered, so that session is abandoned.
    private fun ignoreUnknownSession(
        sessionId: Int,
        status: Int,
    ) {
        logger?.log("Ignored status $status for an installer session that is not open.", null)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) abandonQuietly(context, sessionId)
    }

    private fun askUser(
        sessionId: Int,
        intent: Intent,
    ) {
        val confirmation = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        if (confirmation == null) {
            logger?.log("The installer asked for confirmation without a screen to show.", null)
            abandonQuietly(context, sessionId)
            finish(sessionId, InstallResult.Rejected)
            return
        }
        // The reference asks to launch the confirmation only while the user is in the app. From
        // the background the launch would also be blocked by the background activity rules.
        if (foreground.isInForeground && launch(sessionId, confirmation)) return
        logger?.log("The app is in the background. The install confirmation waits for the app to return.", null)
        sessions.deferConfirmation(confirmation)
        if (sessions.claimForegroundHook()) {
            foreground.onForeground { sessions.takeDeferredConfirmation()?.let { launchDeferred(it) } }
        }
    }

    private fun launchDeferred(confirmation: Intent) {
        val sessionId = sessions.openSessionId ?: return
        // The host app said "safe" when the user tapped install, which can be hours ago. Its answer
        // may have changed since, and a confirmed install starts at once.
        if (!installAllowedNow()) {
            logger?.log("The app is not ready to install now. The waiting install confirmation was withdrawn.", null)
            abandonQuietly(context, sessionId)
            if (sessions.finish(sessionId)) events.send(EngineEvent.InstallNotAllowedNow)
            return
        }
        if (!launch(sessionId, confirmation)) sessions.deferConfirmation(confirmation)
    }

    // Host code. An exception counts as "not now", the safe answer, as before every install.
    private fun installAllowedNow(): Boolean = try {
        canInstallNow()
    } catch (e: Exception) {
        logger?.log("canInstallNow threw an exception. The install is postponed.", e)
        false
    }

    /** Returns false when no activity is resumed, so the caller can keep the confirmation. */
    private fun launch(
        sessionId: Int,
        confirmation: Intent,
    ): Boolean {
        val activity = foreground.resumedActivity ?: return false
        try {
            activity.startActivity(confirmation)
        } catch (e: ActivityNotFoundException) {
            // Without the system confirmation screen this device cannot install the update.
            logger?.log("The system install confirmation screen is missing.", e)
            abandonQuietly(context, sessionId)
            finish(sessionId, InstallResult.Blocked)
        }
        return true
    }

    private fun onAborted(
        sessionId: Int,
        intent: Intent,
    ) {
        val verificationFailure = developerVerificationFailure(intent)
        if (verificationFailure != null) {
            // Not a user decision: the system refused the APK because the developer verification
            // failed. The host app's owner must register the package name and signing key.
            logger?.log("The install was refused by developer verification: $verificationFailure.", null)
            finish(sessionId, InstallResult.Rejected)
        } else if (installCompleted(sessionId)) {
            // The system can report "aborted" for a session whose install went through, for example
            // when the user leaves the Play Protect scan screen. The new version is what counts.
            logger?.log("The installer reported the session as aborted, but the new version is installed.", null)
            finish(sessionId, InstallResult.Success)
        } else {
            finish(sessionId, InstallResult.Cancelled)
        }
    }

    // False when the target is unknown (a session opened before this field existed) or the installed
    // version cannot be read: then "aborted" keeps meaning "cancelled".
    private fun installCompleted(sessionId: Int): Boolean {
        if (!sessions.isOpen(sessionId)) return false
        val target = sessions.openTargetVersionCode ?: return false
        return installedVersionCode() == target
    }

    private fun finish(
        sessionId: Int,
        result: InstallResult,
    ) {
        if (sessions.finish(sessionId)) events.send(EngineEvent.InstallFinished(result))
    }

    // The message is the installer's raw debugging text, for example a failure code. It names
    // packages and files, never the user.
    private fun statusMessage(intent: Intent): String = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "no message"

    private companion object {
        // Status codes are -1 to 8 and session ids are positive, so this value means "missing".
        const val NO_VALUE = Int.MIN_VALUE
    }
}

private fun readInstalledVersionCode(context: Context): Long? = try {
    // The app's own package is always visible to it, so no <queries> entry is needed.
    PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
} catch (e: PackageManager.NameNotFoundException) {
    null
} catch (e: RuntimeException) {
    // A dead package manager must not turn a status broadcast into a crash of the host app.
    null
}

/**
 * The developer verification failure reason, or null when the status carries none. The extra
 * exists from API 36.1; older systems never send it.
 */
private fun developerVerificationFailure(intent: Intent): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) developerVerificationFailureOn36(intent) else null

@RequiresApi(Build.VERSION_CODES.BAKLAVA)
private fun developerVerificationFailureOn36(intent: Intent): String? {
    if (Build.VERSION.SDK_INT_FULL < Build.VERSION_CODES_FULL.BAKLAVA_1) return null
    val key = PackageInstaller.EXTRA_DEVELOPER_VERIFICATION_FAILURE_REASON
    if (!intent.hasExtra(key)) return null
    return when (val reason = intent.getIntExtra(key, NO_REASON)) {
        PackageInstaller.DEVELOPER_VERIFICATION_FAILED_REASON_DEVELOPER_BLOCKED -> "developer blocked"
        PackageInstaller.DEVELOPER_VERIFICATION_FAILED_REASON_NETWORK_UNAVAILABLE -> "network unavailable"
        PackageInstaller.DEVELOPER_VERIFICATION_FAILED_REASON_UNKNOWN -> "unknown reason"
        else -> "reason code $reason"
    }
}

private const val NO_REASON = Int.MIN_VALUE
