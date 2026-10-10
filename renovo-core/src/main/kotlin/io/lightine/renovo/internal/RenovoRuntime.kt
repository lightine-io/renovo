package io.lightine.renovo.internal

import android.app.Activity
import io.lightine.renovo.RenovoConfig
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateListener
import io.lightine.renovo.UpdateState
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
