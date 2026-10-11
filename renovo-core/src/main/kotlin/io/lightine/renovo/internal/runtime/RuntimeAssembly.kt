package io.lightine.renovo.internal.runtime

import android.content.Context
import io.lightine.renovo.RenovoConfig
import io.lightine.renovo.internal.RenovoRuntime
import io.lightine.renovo.internal.SafeLogger
import io.lightine.renovo.internal.engine.UpdateEngine
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Builds the real runtime. The one place where the engine, the real components, and the loop meet. */
internal object RuntimeAssembly {
    fun create(
        context: Context,
        hostConfig: RenovoConfig,
    ): RenovoRuntime {
        // The one place the host app's logger is wrapped. Everything below, the engine and every
        // component, gets the config with the safe logger, so a logger that throws cannot drop an
        // event or crash a worker.
        val config = hostConfig.withLogger(SafeLogger.wrap(hostConfig.logger))
        // A supervisor, so one failed background task does not cancel the loop. The handler keeps an
        // exception nobody caught from reaching the thread's handler, which would crash the host app.
        val scope =
            CoroutineScope(
                SupervisorJob() + Dispatchers.Default +
                    CoroutineExceptionHandler { _, error -> config.logger?.log("A background task of Renovo failed.", error) },
            )
        try {
            val queue = EventQueue()
            val engine = UpdateEngine(config.engineSettings())
            val components = ProductionComponents.create(context, config, scope, queue) { engine.currentSnapshot().etag }
            val runtime = DefaultRuntime(config, components, scope, Dispatchers.Main.immediate, engine, queue)
            // Not started here. The workers the system creates find the runtime through Renovo, which
            // holds it only after init returns. The plain Main dispatcher always posts, so the start
            // runs after Application.onCreate has returned and the runtime is in place.
            scope.launch(Dispatchers.Main) { runtime.start() }
            return runtime
        } catch (e: Exception) {
            scope.cancel()
            throw e
        }
    }
}
