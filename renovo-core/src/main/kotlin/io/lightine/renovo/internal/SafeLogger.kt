package io.lightine.renovo.internal

import io.lightine.renovo.RenovoLogger

/**
 * Hands every message to the host app's logger and ignores any exception the logger throws.
 *
 * Renovo logs from catch blocks, from the engine, and from worker threads. A host logger that throws
 * there would drop an engine event half way through, or crash a worker, over one log line. The
 * runtime wraps the configured logger once, so no component needs its own guard.
 */
internal class SafeLogger private constructor(
    private val delegate: RenovoLogger,
) : RenovoLogger {
    override fun log(
        message: String,
        error: Throwable?,
    ) {
        try {
            delegate.log(message, error)
        } catch (e: VirtualMachineError) {
            // Out of memory and the like are not the logger's fault, and ignoring them would hide a dying process.
            throw e
        } catch (_: Throwable) {
            // Nowhere left to report it.
        }
    }

    internal companion object {
        /** [logger] made safe, or null when logging is off. Wrapping twice does nothing. */
        fun wrap(logger: RenovoLogger?): RenovoLogger? = when (logger) {
            null, is SafeLogger -> logger
            else -> SafeLogger(logger)
        }
    }
}
