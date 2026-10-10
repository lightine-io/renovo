package io.lightine.renovo.internal

import kotlin.time.Duration

/**
 * Runs checks through WorkManager.
 *
 * The implementing task must guarantee:
 * - [schedulePeriodicCheck] keeps one unique periodic job with a "connected" network constraint.
 *   Calling it again with the same interval changes nothing; a new interval replaces the job.
 * - [checkNow] runs one check as unique one-time work, so overlapping requests never run two checks.
 * - A failed check is retried with exponential backoff before the engine hears `CheckFailed`.
 * - Every check reports `CheckStarted` (with `userInitiated` false for periodic runs) and then
 *   exactly one of `CheckSucceeded`, `CheckNotModified`, or `CheckFailed`. The ETag it sends is
 *   the engine's current snapshot ETag.
 */
internal interface Scheduler {
    fun schedulePeriodicCheck(interval: Duration)

    fun checkNow(userInitiated: Boolean)
}
