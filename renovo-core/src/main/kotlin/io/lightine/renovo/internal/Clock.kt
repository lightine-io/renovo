package io.lightine.renovo.internal

/**
 * The current time, replaceable in tests.
 *
 * Implementations return wall-clock milliseconds since the epoch, as [System.currentTimeMillis] does.
 */
internal fun interface Clock {
    fun nowMillis(): Long
}
