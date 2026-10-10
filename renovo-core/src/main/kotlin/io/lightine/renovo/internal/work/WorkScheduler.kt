package io.lightine.renovo.internal.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.internal.Scheduler
import io.lightine.renovo.internal.VersionFetcher
import io.lightine.renovo.internal.engine.EventSink
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * [Scheduler] on WorkManager.
 *
 * Building one makes it the checker of [CheckWorker] (see [WorkEnvironment.checkRunner]), because the
 * system creates that worker and it cannot get anything through a constructor.
 *
 * @param currentEtag The engine's current snapshot ETag. The wiring passes `{ engine.currentSnapshot().etag }`.
 * @param scope Owned by the runtime. [checkNow] runs in it.
 */
internal class WorkScheduler(
    private val context: Context,
    fetcher: VersionFetcher,
    events: EventSink,
    currentEtag: () -> String?,
    scope: CoroutineScope,
    private val logger: RenovoLogger? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Scheduler {
    private val runner = CheckRunner(fetcher, events, currentEtag, scope, dispatcher, logger)

    init {
        WorkEnvironment.checkRunner = runner
    }

    override fun schedulePeriodicCheck(interval: Duration) {
        try {
            // UPDATE changes the interval of the pending job in place and keeps its place in the
            // period, so calling this at every process start does not push the next check back. The
            // other policies either ignore a new interval (KEEP), or cancel the running job (the
            // deprecated REPLACE and CANCEL_AND_REENQUEUE).
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(CHECK_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, periodicCheckRequest(interval))
        } catch (e: IllegalStateException) {
            // WorkManager is not initialized: the host app disabled its initializer without providing a
            // configuration. Failing here would crash Application.onCreate, so the checks stay off.
            logger?.log("WorkManager is not initialized, so the periodic check is not scheduled.", e)
        }
    }

    // The check does not go through WorkManager: a user who taps "check for updates" wants the answer
    // now, and WorkManager may hold a job back for minutes. The coalescing lives in the runner.
    override fun checkNow(userInitiated: Boolean) {
        runner.checkNow(userInitiated)
    }

    internal companion object {
        val BACKOFF_POLICY = BackoffPolicy.EXPONENTIAL
        const val BACKOFF_DELAY_SECONDS = 30L

        fun periodicCheckRequest(interval: Duration): PeriodicWorkRequest = PeriodicWorkRequestBuilder<CheckWorker>(interval.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BACKOFF_POLICY, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            // The app start already runs a check, so the first background one waits a full period.
            .setInitialDelay(interval.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .build()
    }
}
