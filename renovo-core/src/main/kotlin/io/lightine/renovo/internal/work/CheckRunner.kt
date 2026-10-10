package io.lightine.renovo.internal.work

import io.lightine.renovo.FailureReason
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.internal.FetchResult
import io.lightine.renovo.internal.VersionFetcher
import io.lightine.renovo.internal.engine.EngineEvent
import io.lightine.renovo.internal.engine.EventSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs checks and reports them to the engine. Shared by [WorkScheduler.checkNow] and [CheckWorker].
 *
 * @param currentEtag The engine's current snapshot ETag. A function, because it changes after every
 *   check and must be read right before the request.
 */
internal class CheckRunner(
    private val fetcher: VersionFetcher,
    private val events: EventSink,
    private val currentEtag: () -> String?,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val logger: RenovoLogger?,
) {
    private val lock = Any()

    /** Whether the check in flight was started by the user, or null when none is in flight. */
    private var inFlightByUser: Boolean? = null

    /**
     * Starts a check unless one is already running; then the two share the one request.
     *
     * A user who taps while a background check is running is still owed a visible failure, so that
     * case tells the engine again, with `userInitiated` true.
     */
    fun checkNow(userInitiated: Boolean) {
        val claim =
            synchronized(lock) {
                val running = inFlightByUser
                when {
                    running == null -> {
                        inFlightByUser = userInitiated
                        Claim.Start
                    }

                    userInitiated && !running -> {
                        inFlightByUser = true
                        Claim.Upgrade
                    }

                    else -> Claim.Join
                }
            }
        when (claim) {
            Claim.Start ->
                scope.launch {
                    try {
                        events.send(EngineEvent.CheckStarted(userInitiated))
                        report(fetch())
                    } finally {
                        synchronized(lock) { inFlightByUser = null }
                    }
                }

            Claim.Upgrade -> events.send(EngineEvent.CheckStarted(userInitiated = true))

            Claim.Join -> Unit
        }
    }

    private enum class Claim { Start, Upgrade, Join }

    /**
     * Runs one attempt of the periodic check. Returns true when the attempt failed for lack of a
     * network and the worker should be retried, in which case nothing is reported.
     *
     * `CheckStarted` is sent together with the outcome instead of before the request: a retried attempt
     * would otherwise leave the engine in "checking" with no outcome coming.
     */
    suspend fun runInBackground(finalAttempt: Boolean): Boolean {
        val result = fetch()
        if (result is FetchResult.Failed && result.reason == FailureReason.Network && !finalAttempt) return true
        events.send(EngineEvent.CheckStarted(userInitiated = false))
        report(result)
        return false
    }

    private suspend fun fetch(): FetchResult = withContext(dispatcher) {
        try {
            fetcher.fetch(currentEtag())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The fetcher promises not to throw. If it does anyway, the engine must still hear an
            // outcome, or it would wait in "checking" for good.
            logger?.log("The version fetcher threw an exception.", e)
            FetchResult.Failed(FailureReason.Parse)
        }
    }

    private fun report(result: FetchResult) {
        events.send(
            when (result) {
                is FetchResult.Found -> EngineEvent.CheckSucceeded(result.info, result.etag)
                FetchResult.NotModified -> EngineEvent.CheckNotModified
                is FetchResult.Failed -> EngineEvent.CheckFailed(result.reason)
            },
        )
    }
}
