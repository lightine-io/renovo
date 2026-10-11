package io.lightine.renovo.internal

import android.content.Context
import io.lightine.renovo.RenovoConfig
import io.lightine.renovo.internal.runtime.RuntimeAssembly

/** Builds the runtime from its components. This is the one place where the parts are connected. */
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
