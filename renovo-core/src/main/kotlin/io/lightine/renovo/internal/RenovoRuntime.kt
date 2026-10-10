package io.lightine.renovo.internal

import android.app.Activity
import io.lightine.renovo.RenovoConfig
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateListener
import io.lightine.renovo.UpdateState
import io.lightine.renovo.internal.engine.EventSink
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything behind the [io.lightine.renovo.Renovo] facade, which only forwards to this.
 *
 * The implementation (wiring task) owns the engine, feeds it events, and executes its effects in
 * the order the engine returns them, one event at a time.
 */
internal interface RenovoRuntime {
    /** The settings given to init. */
    val config: RenovoConfig

    /** The host app's logger, or null when logging is off. */
    val logger: RenovoLogger?

    /** The engine's state flow. */
    val state: StateFlow<UpdateState>

    /**
     * Where workers and receivers that the system creates report their events. Components built by
     * the runtime get the sink through their constructor instead.
     */
    val events: EventSink

    /**
     * Whether the host app is in the foreground. System-created receivers read it here, for example
     * to decide whether the install confirmation can be shown now or must wait.
     */
    val foreground: ForegroundTracker

    /** Registers [listener]; it is called on the main thread with the current state and every change. */
    fun addListener(listener: UpdateListener)

    fun removeListener(listener: UpdateListener)

    fun checkNow()

    fun startDownload()

    fun installIfReady()

    fun submit(info: UpdateInfo)

    fun useMobileDataOnce()

    /** Does nothing below API 33, where notifications need no runtime permission. */
    fun requestNotificationPermission(activity: Activity)
}
