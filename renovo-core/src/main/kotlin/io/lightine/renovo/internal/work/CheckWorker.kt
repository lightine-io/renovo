package io.lightine.renovo.internal.work

import android.content.Context
import android.util.Log
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
            // Renovo.init did not run in this process. The next period tries again.
            Log.w(TAG, "The check worker started before Renovo.init ran, so it did nothing.")
            return Result.success()
        }
        // runAttemptCount starts at 0 and, for periodic work, restarts every period.
        val retry = runner.runInBackground(finalAttempt = runAttemptCount >= MAX_ATTEMPTS - 1)
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "Renovo"

        /** Runs per period before a network failure is reported. */
        const val MAX_ATTEMPTS = 3
    }
}
