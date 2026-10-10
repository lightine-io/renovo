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
 * Stands in for the runtime until [io.lightine.renovo.Renovo.init] runs.
 *
 * Every call fails loudly, because silently ignoring an action before init would hide a missing
 * init call from the host app's developer until an update never arrives.
 */
internal object UninitializedRuntime : RenovoRuntime {
    private const val MESSAGE = "Renovo.init was not called"

    override val config: RenovoConfig
        get() = notInitialized()

    override val logger: RenovoLogger? = null

    override val state: StateFlow<UpdateState>
        get() = notInitialized()

    override val events: EventSink
        get() = notInitialized()

    override val foreground: ForegroundTracker
        get() = notInitialized()

    override fun addListener(listener: UpdateListener): Unit = notInitialized()

    override fun removeListener(listener: UpdateListener): Unit = notInitialized()

    override fun checkNow(): Unit = notInitialized()

    override fun startDownload(): Unit = notInitialized()

    override fun installIfReady(): Unit = notInitialized()

    override fun submit(info: UpdateInfo): Unit = notInitialized()

    override fun useMobileDataOnce(): Unit = notInitialized()

    override fun requestNotificationPermission(activity: Activity): Unit = notInitialized()

    private fun notInitialized(): Nothing = throw IllegalStateException(MESSAGE)
}
