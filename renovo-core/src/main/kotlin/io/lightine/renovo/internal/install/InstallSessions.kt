package io.lightine.renovo.internal.install

import android.content.Intent

/**
 * The installer session this process has open, shared by the installer and the receivers.
 *
 * The receivers are created by the system and cannot get state through a constructor, so the
 * process uses one [shared] instance. Tests create their own.
 *
 * The state lives in memory only. The process is usually killed by the install itself, and the
 * runtime rebuilds the update state on the next start from the installed version code.
 */
internal class InstallSessions {
    private val lock = Any()

    // True from the start of an install until the session ends, including the short time before
    // the system has handed out a session id.
    private var busy = false
    private var openId: Int? = null
    private var targetCode: Long? = null
    private var deferredConfirmation: Intent? = null
    private var foregroundHookRegistered = false

    /** Claims the right to start an install. False while another install of ours is running. */
    fun tryBegin(): Boolean = synchronized(lock) {
        if (busy) {
            false
        } else {
            busy = true
            true
        }
    }

    /** Gives up a claim from [tryBegin] that never got a session. */
    fun cancelBegin() {
        synchronized(lock) {
            if (openId == null) busy = false
        }
    }

    /**
     * Records the session the system created. [targetVersionCode] is the version code of the APK in
     * it, so a late "aborted" status can be told apart from an install that did complete.
     */
    fun opened(
        sessionId: Int,
        targetVersionCode: Long? = null,
    ) {
        synchronized(lock) {
            busy = true
            openId = sessionId
            targetCode = targetVersionCode
        }
    }

    fun isOpen(sessionId: Int): Boolean = synchronized(lock) { openId == sessionId }

    val openSessionId: Int?
        get() = synchronized(lock) { openId }

    /** The version code the open session installs, or null when it is unknown or no session is open. */
    val openTargetVersionCode: Long?
        get() = synchronized(lock) { targetCode }

    /**
     * Closes [sessionId] and returns true if it was the open session. A second call for the same
     * session returns false, which is how a repeated status for one session is ignored.
     */
    fun finish(sessionId: Int): Boolean = synchronized(lock) {
        if (openId != sessionId) {
            false
        } else {
            openId = null
            targetCode = null
            busy = false
            deferredConfirmation = null
            true
        }
    }

    /** Keeps the system confirmation until the next activity resume. */
    fun deferConfirmation(confirmation: Intent) {
        synchronized(lock) { deferredConfirmation = confirmation }
    }

    fun takeDeferredConfirmation(): Intent? = synchronized(lock) {
        val confirmation = deferredConfirmation
        deferredConfirmation = null
        confirmation
    }

    /**
     * Returns true only on the first call. The foreground tracker has no way to remove a callback,
     * so the callback that launches a deferred confirmation is registered once per process.
     */
    fun claimForegroundHook(): Boolean = synchronized(lock) {
        if (foregroundHookRegistered) {
            false
        } else {
            foregroundHookRegistered = true
            true
        }
    }

    companion object {
        /** The instance the installer and the system-created receivers share. */
        val shared: InstallSessions = InstallSessions()
    }
}
