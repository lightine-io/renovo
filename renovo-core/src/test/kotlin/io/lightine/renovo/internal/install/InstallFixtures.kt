package io.lightine.renovo.internal.install

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.internal.ForegroundTracker
import io.lightine.renovo.internal.engine.EngineEvent
import io.lightine.renovo.internal.engine.EventSink

internal class RecordingSink : EventSink {
    val events = mutableListOf<EngineEvent>()

    override fun send(event: EngineEvent) {
        events += event
    }
}

internal class RecordingLogger : RenovoLogger {
    val messages = mutableListOf<String>()

    override fun log(
        message: String,
        error: Throwable?,
    ) {
        messages += message
    }

    fun contains(text: String): Boolean = messages.any { it.contains(text) }
}

internal class FakeForeground(
    override var resumedActivity: Activity? = null,
    override var isInForeground: Boolean = resumedActivity != null,
) : ForegroundTracker {
    private val callbacks = mutableListOf<() -> Unit>()
    private val resumeCallbacks = mutableListOf<() -> Unit>()

    val callbackCount: Int
        get() = callbacks.size + resumeCallbacks.size

    override fun onForeground(callback: () -> Unit) {
        callbacks += callback
    }

    override fun onResumed(callback: () -> Unit) {
        resumeCallbacks += callback
    }

    /** What the real tracker does when the app returns from the background: an activity resumes. */
    fun comeToForeground(activity: Activity) {
        resumedActivity = activity
        isInForeground = true
        callbacks.forEach { it() }
        resumeCallbacks.forEach { it() }
    }

    /** What the real tracker does when an activity resumes while the app stayed in the foreground. */
    fun resume(activity: Activity) {
        resumedActivity = activity
        resumeCallbacks.forEach { it() }
    }
}

/** An activity for which no target screen exists, as on a device without the settings screen. */
internal class NoScreensActivity : Activity() {
    override fun startActivity(intent: Intent?): Unit = throw ActivityNotFoundException("No activity for $intent")
}
