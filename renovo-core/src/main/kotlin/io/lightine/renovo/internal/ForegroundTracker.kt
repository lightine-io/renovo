package io.lightine.renovo.internal

import android.app.Activity

/**
 * Knows whether one of the host app's activities is in the foreground.
 *
 * The implementing task must guarantee:
 * - Tracking through `Application.ActivityLifecycleCallbacks`, registered once at init.
 * - [resumedActivity] is the most recently resumed activity that is still resumed, held weakly so
 *   no activity leaks.
 * - The callback given to [onForeground] runs on the main thread every time the app comes to the
 *   foreground, so the runtime can re-check the install permission when the user returns from
 *   the settings screen.
 */
internal interface ForegroundTracker {
    val isInForeground: Boolean

    val resumedActivity: Activity?

    fun onForeground(callback: () -> Unit)
}
