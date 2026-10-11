package io.lightine.renovo.ui

import androidx.compose.runtime.mutableStateSetOf

/** What the user chose to put off. Each choice lasts until the next app start. */
internal enum class Dismissal {
    /** "Later" on the update available dialog. */
    Later,

    /** "Not now" on the ready to install dialog. */
    NotNow,

    /** "Cancel" on the permission dialog. */
    PermissionCancelled,
}

/**
 * The dismissals that last until the next app start (UI specification 2.1, 2.3, and 2.4).
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

    /** Forgets everything. Only tests call it, to start from the state of a fresh process. */
    fun clear() {
        entries.clear()
    }

    internal companion object {
        /** The holder of this process. */
        val process = DismissedUpdates()
    }
}
