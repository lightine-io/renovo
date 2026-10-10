package io.lightine.renovo.internal.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.lightine.renovo.Renovo
import io.lightine.renovo.internal.RenovoRuntime
import io.lightine.renovo.internal.engine.EngineEvent
import io.lightine.renovo.internal.engine.InstallResult

/**
 * Receives `MY_PACKAGE_REPLACED`, which the system sends only to the app that was just replaced.
 *
 * This is a safety net, not the main path. A full install kills the app's process, usually before
 * the session's success status arrives, and the new process does not remember the session. The
 * runtime therefore decides on every start by comparing the installed version code with the one
 * it stored. This receiver only reports success when this very process still tracks an open
 * session, which covers a status that was lost while the process survived.
 */
internal class PackageReplacedReceiver(
    private val sessions: InstallSessions = InstallSessions.shared,
) : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        report(Renovo.runtimeOrNull)
    }

    /** Separate from [onReceive] so tests can pass a runtime without the process-wide init. */
    fun report(runtime: RenovoRuntime?) {
        // Without init there is no engine to report to; the next start after init sees the new version.
        if (runtime == null) return
        runtime.logger?.log("The app was replaced by a new version.", null)
        val sessionId = sessions.openSessionId ?: return
        if (sessions.finish(sessionId)) runtime.events.send(EngineEvent.InstallFinished(InstallResult.Success))
    }
}
