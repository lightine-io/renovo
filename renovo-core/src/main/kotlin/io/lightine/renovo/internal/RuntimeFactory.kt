package io.lightine.renovo.internal

import android.content.Context
import io.lightine.renovo.RenovoConfig

/** Assembles the runtime from its components. The one place where the wiring happens. */
internal fun interface RuntimeFactory {
    fun create(
        context: Context,
        config: RenovoConfig,
    ): RenovoRuntime

    companion object {
        /**
         * The factory [io.lightine.renovo.Renovo.init] uses.
         *
         * Placeholder until the wiring task replaces it with the real assembly. It fails instead of
         * returning a runtime that does nothing, so a build that ships without the wiring cannot
         * pass for a working one.
         */
        val Default: RuntimeFactory =
            RuntimeFactory { _, _ ->
                throw IllegalStateException("The Renovo runtime is not wired yet.")
            }
    }
}
