package io.lightine.renovo.internal

import kotlin.time.Duration

/**
 * Runs checks: a periodic one through WorkManager, and an immediate one on a coroutine.
 *
 * An implementation guarantees:
 * - [schedulePeriodicCheck] keeps one unique periodic job. The job needs a connected network, and
 *   a failed run is tried again after 30 seconds, then after longer and longer waits. Calling it
 *   again with the same interval changes nothing. A new interval replaces the job's interval
 *   without cancelling the job.
 * - [checkNow] does not go through WorkManager. It fetches right away in a coroutine of the runtime.
 *   Overlapping calls share one request, so a user who taps twice sends one request.
 * - A periodic run that fails for lack of a network is tried again by WorkManager, up to three
 *   runs per period, before the engine hears `CheckFailed`.
 * - Every check reports `CheckStarted` (with `userInitiated` false for periodic runs) and then
 *   exactly one of `CheckSucceeded`, `CheckNotModified`, or `CheckFailed`. The ETag it sends is
 *   the engine's current snapshot ETag. A periodic run sends `CheckStarted` together with its outcome,
 *   after the request, so a run that is retried leaves no unanswered `CheckStarted` behind.
 */
internal interface Scheduler {
    fun schedulePeriodicCheck(interval: Duration)

    fun checkNow(userInitiated: Boolean)
}
