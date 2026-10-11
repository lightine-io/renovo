package io.lightine.renovo.internal.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * The periodic background check. Created by WorkManager, so it reaches the checker through
 * [WorkEnvironment.checkRunner], which the runtime fills when it builds the [WorkScheduler].
 */
internal class CheckWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val runner = WorkEnvironment.checkRunner
        if (runner == null) {
            // Renovo.init did not run yet in this process, and there is no host logger to log to. A
            // retry comes sooner than the next period, and init may have run by then.
            return Result.retry()
        }
        // runAttemptCount starts at 0 and, for periodic work, restarts every period.
        val retry = runner.runInBackground(finalAttempt = runAttemptCount >= MAX_ATTEMPTS - 1)
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        /** Runs per period before a network failure is reported. */
        const val MAX_ATTEMPTS = 3
    }
}
