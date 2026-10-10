package io.lightine.renovo.internal.work

import io.lightine.renovo.internal.Clock

/**
 * Decides which progress reports are worth sending.
 *
 * A report passes when at least [minIntervalMillis] have passed since the last one AND the
 * percentage moved by at least one point. Whichever limit is reached later wins, so a fast link
 * does not flood the engine and a slow one does not repeat the same percentage. When the total is
 * unknown only the time limit applies. The first report always passes.
 */
internal class ProgressThrottle(
    private val clock: Clock,
    private val minIntervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
) {
    private var lastSentAt: Long? = null
    private var lastPercent = -1L

    fun shouldSend(
        bytes: Long,
        total: Long?,
    ): Boolean {
        val now = clock.nowMillis()
        val sentAt = lastSentAt
        val percent = if (total != null && total > 0) bytes * 100 / total else -1L
        if (sentAt != null) {
            // A clock that went backwards must not silence progress until it catches up.
            val waited = now < sentAt || now - sentAt >= minIntervalMillis
            val moved = total == null || percent - lastPercent >= 1
            if (!(waited && moved)) return false
        }
        lastSentAt = now
        lastPercent = percent
        return true
    }

    private companion object {
        const val DEFAULT_INTERVAL_MILLIS = 500L
    }
}
