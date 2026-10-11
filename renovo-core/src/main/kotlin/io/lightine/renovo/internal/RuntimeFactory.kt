package io.lightine.renovo.internal

import android.content.Context
import io.lightine.renovo.RenovoConfig
import io.lightine.renovo.internal.runtime.RuntimeAssembly

/** Assembles the runtime from its components. The one place where the wiring happens. */
internal fun interface RuntimeFactory {
    fun create(
        context: Context,
        config: RenovoConfig,
    ): RenovoRuntime

    companion object {
        /** The factory [io.lightine.renovo.Renovo.init] uses: the real components and the real engine loop. */
        val Default: RuntimeFactory = RuntimeFactory(RuntimeAssembly::create)
    }
}
