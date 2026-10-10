package io.lightine.renovo.internal.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.lightine.renovo.FailureReason
import io.lightine.renovo.Network
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.internal.Downloader
import io.lightine.renovo.internal.ForegroundTracker
import io.lightine.renovo.internal.engine.EngineEvent
import io.lightine.renovo.internal.engine.EventSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * [Downloader] on WorkManager: one unique [DownloadWorker] named [DOWNLOAD_WORK_NAME].
 *
 * WorkManager's calls are asynchronous, and the order of "enqueue" and "cancel" matters (a retry
 * cancels and enqueues in a row). So the calls are turned into operations that one coroutine runs
 * in the order they arrived.
 *
 * @param scope Owned by the runtime. The operations and the watch on the work run in it.
 */
internal class WorkDownloader(
    private val context: Context,
    private val events: EventSink,
    private val foregroundTracker: ForegroundTracker,
    scope: CoroutineScope,
    private val logger: RenovoLogger? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val probe: NetworkProbe = NetworkProbe(context),
) : Downloader {
    private val operations = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val files = DownloadFiles(context)

    /** Ids of work this class cancelled itself, so that only a cancel from outside is reported. */
    private val cancelledByUs: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    // Touched only by the single collector in watchWork.
    private val activeIds = HashSet<UUID>()
    private var lastWaitKey: String? = null

    init {
        scope.launch(dispatcher) {
            for (operation in operations) {
                try {
                    operation()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger?.log("A download operation failed.", e)
                }
            }
        }
        scope.launch(dispatcher) { watchWork() }
    }

    override fun enqueue(
        info: UpdateInfo,
        network: Network,
        userInitiated: Boolean,
    ) {
        // Decided now, while the caller is the user's tap: from API 31 a foreground service can start
        // only from a visible app, and by the time the worker runs the app may be in the background.
        val foreground = userInitiated && foregroundTracker.isInForeground
        operations.trySend {
            try {
                start(info, network, foreground)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Without this the engine would wait for a download that was never queued.
                logger?.log("The download could not be queued.", e)
                events.send(EngineEvent.DownloadFailed(info.versionCode, FailureReason.Network))
            }
        }
    }

    override fun cancel() {
        operations.trySend {
            val workManager = WorkManager.getInstance(context)
            workManager
                .getWorkInfosForUniqueWork(DOWNLOAD_WORK_NAME)
                .await()
                .filterNot { it.state.isFinished }
                .forEach { cancelledByUs.add(it.id) }
            workManager.cancelUniqueWork(DOWNLOAD_WORK_NAME).result.await()
            withContext(Dispatchers.IO) { files.deleteAllPartials() }
        }
    }

    /** Suspends until every operation queued before this call has finished. For tests. */
    internal suspend fun awaitIdle() {
        val done = CompletableDeferred<Unit>()
        operations.trySend { done.complete(Unit) }
        done.await()
    }

    private suspend fun start(
        info: UpdateInfo,
        network: Network,
        foreground: Boolean,
    ) {
        val workManager = WorkManager.getInstance(context)
        val active =
            workManager
                .getWorkInfosForUniqueWork(DOWNLOAD_WORK_NAME)
                .await()
                .firstOrNull { !it.state.isFinished }

        if (active != null && versionTag(info.versionCode) in active.tags) {
            // Same version: change the constraints and mode of the work that exists. Cancelling and
            // enqueueing would stop a running download and start a worker that has to find its
            // partial file again.
            val result = workManager.updateWork(request(info, network, foreground, active.id)).await()
            if (result != WorkManager.UpdateResult.NOT_APPLIED) return
            // It finished between the lookup and the update. Queue a new one below.
        }
        if (active != null) cancelledByUs.add(active.id)
        workManager
            .enqueueUniqueWork(DOWNLOAD_WORK_NAME, ExistingWorkPolicy.REPLACE, request(info, network, foreground, id = null))
            .result
            .await()
    }

    private fun request(
        info: UpdateInfo,
        network: Network,
        foreground: Boolean,
        id: UUID?,
    ): OneTimeWorkRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
        .setConstraints(constraintsFor(network))
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
        .setInputData(DownloadInput.of(info, foreground).toData())
        .addTag(versionTag(info.versionCode))
        .apply { if (id != null) setId(id) }
        .build()

    /**
     * Follows the work and reports what the worker cannot report itself: that it is held back for
     * lack of a network, and that someone outside (the notification's cancel button) cancelled it.
     */
    private suspend fun watchWork() {
        try {
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWorkFlow(DOWNLOAD_WORK_NAME)
                .collect { infos -> infos.forEach(::onWorkInfo) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            logger?.log("WorkManager is not initialized, so waiting for a network is not reported.", e)
        }
    }

    private fun onWorkInfo(info: WorkInfo) {
        val versionCode = versionCodeOf(info.tags) ?: return
        when (info.state) {
            WorkInfo.State.ENQUEUED -> {
                activeIds.add(info.id)
                reportWaitingIfNeeded(info, versionCode)
            }

            WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                activeIds.add(info.id)
                // When it returns to ENQUEUED (a retry, or a lost constraint) the wait is news again.
                lastWaitKey = null
            }

            WorkInfo.State.CANCELLED -> {
                // Only a transition seen in this process counts: the database keeps cancelled work
                // around, and an old one must not fail a new session's download.
                val seen = activeIds.remove(info.id)
                val byUs = cancelledByUs.remove(info.id)
                if (seen && !byUs) events.send(EngineEvent.DownloadFailed(versionCode, FailureReason.Cancelled))
            }

            WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED -> activeIds.remove(info.id)
        }
    }

    private fun reportWaitingIfNeeded(
        info: WorkInfo,
        versionCode: Long,
    ) {
        val type = info.constraints.requiredNetworkType
        // The constraint is part of the key: after "use mobile data" the wait is judged again. So is the
        // run count: WorkManager's state flow may skip a quick RUNNING between two ENQUEUED reports, and
        // a run that came and went is a reason to say "waiting" again.
        val key = "${info.id}:$type:${info.runAttemptCount}"
        if (key == lastWaitKey || probe.satisfies(type)) return
        lastWaitKey = key
        events.send(EngineEvent.DownloadWaitingForNetwork(versionCode))
    }

    internal companion object {
        const val BACKOFF_DELAY_SECONDS = 30L

        fun constraintsFor(network: Network): Constraints = Constraints
            .Builder()
            .setRequiredNetworkType(
                when (network) {
                    Network.Any -> NetworkType.CONNECTED
                    Network.UnmeteredOnly -> NetworkType.UNMETERED
                    Network.NotRoaming -> NetworkType.NOT_ROAMING
                },
            ).build()
    }
}
