package io.lightine.renovo.internal.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * The callback the system installer reports a session's status to.
 *
 * The intent names [InstallStatusReceiver] explicitly: from API 34 a mutable `PendingIntent` around
 * an implicit intent throws. It must be mutable because the system adds the status extras, and
 * from target SDK 35 `commit` throws for an immutable one. The session id is the request code, so
 * each session gets its own `PendingIntent`, and it is also put in the intent so the receiver
 * knows the session even if the system does not add the id itself.
 */
internal fun statusReceiver(
    context: Context,
    sessionId: Int,
): IntentSender {
    val intent =
        Intent(context, InstallStatusReceiver::class.java)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
    return PendingIntent.getBroadcast(context, sessionId, intent, statusFlags()).intentSender
}

// Below API 31 a PendingIntent is mutable by default and FLAG_MUTABLE does not exist yet.
private fun statusFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
} else {
    PendingIntent.FLAG_UPDATE_CURRENT
}

/** Commits [sessionId] right away. Every stream of the session must already be closed. */
internal fun commitNow(
    context: Context,
    sessionId: Int,
) {
    context.packageManager.packageInstaller.openSession(sessionId).use { session ->
        // No install constraints: every install follows a user tap, so waiting for a quiet moment only delays it.
        session.commit(statusReceiver(context, sessionId))
    }
}

/** Abandons [sessionId] so the system deletes the staged file. Never throws. */
internal fun abandonQuietly(
    context: Context,
    sessionId: Int,
) {
    try {
        context.packageManager.packageInstaller.abandonSession(sessionId)
    } catch (e: RuntimeException) {
        // A SecurityException means the session is already finalized or gone, so there is nothing
        // left to clean up. A dead system server throws other runtime exceptions. This runs in failure
        // paths before the installer releases its busy flag, so nothing may escape: an exception here
        // would leave the installer busy and refuse every later install in this process.
    }
}
