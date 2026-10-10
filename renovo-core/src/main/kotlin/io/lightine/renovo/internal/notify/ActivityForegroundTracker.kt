package io.lightine.renovo.internal.notify

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.internal.ForegroundTracker
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Follows the host app's activities through the lifecycle callbacks.
 *
 * It registers once, when it is created. The lifecycle callbacks always run on the main thread,
 * so the counters change on one thread only. They are volatile because [isInForeground] may be
 * read from any thread.
 */
internal class ActivityForegroundTracker(
    application: Application,
    private val logger: RenovoLogger? = null,
) : ForegroundTracker {
    // Started, not resumed: a dialog or a translucent activity pauses the activity below it but
    // does not stop it, and the app is still in front of the user.
    @Volatile
    private var startedCount = 0

    // A rotation stops the old activity before the new one starts. This bridges that gap, so it
    // counts neither as "left the foreground" nor as "came back".
    @Volatile
    private var recreating = false

    @Volatile
    private var resumed: WeakReference<Activity>? = null

    private val callbacks = CopyOnWriteArrayList<() -> Unit>()

    override val isInForeground: Boolean
        get() = startedCount > 0 || recreating

    override val resumedActivity: Activity?
        get() = resumed?.get()

    override fun onForeground(callback: () -> Unit) {
        callbacks.add(callback)
    }

    init {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    val wasBackground = startedCount == 0
                    startedCount++
                    if (!wasBackground) return
                    if (recreating) {
                        recreating = false
                    } else {
                        notifyForeground()
                    }
                }

                override fun onActivityResumed(activity: Activity) {
                    resumed = WeakReference(activity)
                }

                override fun onActivityPaused(activity: Activity) {
                    clearResumed(activity)
                }

                override fun onActivityStopped(activity: Activity) {
                    if (startedCount > 0) startedCount--
                    if (startedCount == 0 && activity.isChangingConfigurations) recreating = true
                }

                override fun onActivityDestroyed(activity: Activity) {
                    clearResumed(activity)
                }

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) = Unit
            },
        )
    }

    // Only the activity that is going away may clear the reference. When the next activity's
    // resume came first, its reference must stay.
    private fun clearResumed(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    private fun notifyForeground() {
        for (callback in callbacks) {
            // A failing callback must neither stop the others nor crash the host app's activity start.
            try {
                callback()
            } catch (error: Exception) {
                logger?.log("A foreground callback failed.", error)
            }
        }
    }
}
