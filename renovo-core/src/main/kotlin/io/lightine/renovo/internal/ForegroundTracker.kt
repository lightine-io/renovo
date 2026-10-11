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
 *   the settings screen. It runs once the first activity has resumed, so [resumedActivity] is set
 *   inside it and a callback can launch a screen from it.
 * - The callback given to [onResumed] runs on the main thread after every activity resume, also
 *   when the app never left the foreground (a system dialog that paused the activity was closed).
 *   [resumedActivity] is set inside it.
 */
internal interface ForegroundTracker {
    val isInForeground: Boolean

    val resumedActivity: Activity?

    fun onForeground(callback: () -> Unit)

    fun onResumed(callback: () -> Unit)
}
