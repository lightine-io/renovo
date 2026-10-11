package io.lightine.renovo.ui

import androidx.compose.runtime.mutableStateSetOf
import io.lightine.renovo.FailureReason
import io.lightine.renovo.UpdateState

/** What the user chose to put off. Each choice lasts until the next app start at the latest. */
internal enum class Dismissal {
    /** "Later" on the update available dialog. */
    Later,

    /** "Not now" on the ready to install dialog. */
    NotNow,

    /** "Cancel" on the permission dialog. */
    PermissionCancelled,

    /** "Hide" on the downloading dialog. Lasts for that download only, see [DismissedUpdates.follow]. */
    Hidden,

    /** "Close" on the failed dialog. Lasts for that failure only, see [DismissedUpdates.follow]. */
    Closed,
}

/**
 * The dismissals that last until the next app start (UI specification 2.1, 2.3, and 2.4), and
 * "Hide" and "Close", which last for their download or failure (2.2 and 2.6).
 *
 * The update state is process-wide, so a dismissal has to be too: when the user leaves the app and
 * comes back, or opens a second activity, the dialog they put off must stay away. The holder lives
 * in the process, so a new process starts empty, which is exactly "until the next app start".
 *
 * It keeps the version code with each choice. A newer version is a new question, so it shows again.
 * The entries are snapshot state, so a composition that reads them redraws when another window
 * adds one.
 *
 * ```
 * if (!dismissed.isDismissed(Dismissal.Later, info.versionCode)) showDialog()
 * ```
 */
internal class DismissedUpdates {
    private val entries = mutableStateSetOf<Pair<Dismissal, Long>>()

    /** True when the user chose [dismissal] for the version with [versionCode] in this process. */
    fun isDismissed(
        dismissal: Dismissal,
        versionCode: Long,
    ): Boolean = (dismissal to versionCode) in entries

    /** Remembers that the user chose [dismissal] for the version with [versionCode]. */
    fun dismiss(
        dismissal: Dismissal,
        versionCode: Long,
    ) {
        entries.add(dismissal to versionCode)
    }

    // What follow() saw last. Plain fields: only the main thread calls it, and nothing is drawn from them.
    private var download: Long? = null
    private var failure: Pair<FailureReason, Long?>? = null

    /**
     * Forgets "Hide" when its download is over and "Close" when its failure is over, so the next
     * download or failure shows its dialog again. Every window calls it with the state it draws. The
     * first window to see a change forgets for all of them, and a window that opens later, for
     * example a second activity, sees no change and keeps the choice.
     */
    fun follow(state: UpdateState) {
        val nextDownload = state.downloadVersionCode()
        if (nextDownload != download) {
            download = nextDownload
            forget(Dismissal.Hidden)
        }
        val nextFailure = (state as? UpdateState.Failed)?.let { it.reason to it.info?.versionCode }
        if (nextFailure != failure) {
            failure = nextFailure
            forget(Dismissal.Closed)
        }
    }

    private fun forget(dismissal: Dismissal) {
        val stale = entries.filter { it.first == dismissal }
        if (stale.isNotEmpty()) entries.removeAll(stale.toSet())
    }

    /** Forgets everything. Only tests call it, to start from the state of a fresh process. */
    fun clear() {
        entries.clear()
        download = null
        failure = null
    }

    internal companion object {
        /** The holder of this process. */
        val process = DismissedUpdates()

        /** The version code under which "Close" is kept for a failed check, which has no version. Real codes start at 1. */
        const val NO_VERSION = 0L
    }
}
