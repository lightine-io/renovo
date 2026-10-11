package io.lightine.renovo.internal.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.lightine.renovo.DownloadProgress
import io.lightine.renovo.FailureReason
import io.lightine.renovo.internal.engine.EngineEvent
import io.lightine.renovo.internal.engine.EventSink
import io.lightine.renovo.internal.isHttpsUrl
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Downloads one APK. Created by WorkManager, so it finds its collaborators in [WorkEnvironment].
 *
 * When WorkManager stops it (the ten-minute limit of a plain worker, a lost constraint, a
 * cancellation) the coroutine is cancelled, the partial file stays, and WorkManager reschedules it.
 * That is not a failure, so nothing is reported.
 */
internal class DownloadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val runtime = WorkEnvironment.runtimeProvider()
        if (runtime == null) {
            // Nobody to report to: Renovo.init did not run in this process.
            Log.w(TAG, "The download worker started before Renovo.init ran, so it did nothing.")
            return Result.failure()
        }
        val input = DownloadInput.from(inputData)
        if (input == null) {
            runtime.logger?.log("The download worker got no usable request.", null)
            return Result.failure()
        }
        val events = runtime.events
        if (!isHttpsUrl(input.apkUrl)) {
            runtime.logger?.log("Refused to download version ${input.versionCode} from a non-https address.", null)
            return fail(events, input.versionCode, FailureReason.Network)
        }

        val files = DownloadFiles(applicationContext)
        // Only one version is ever wanted. Dropping the others first also frees the room the new one needs.
        if (files.ensureDirectory()) files.deleteOtherVersions(input.versionCode)

        val finished = files.apk(input.versionCode)
        if (finished.isFile && (input.size == null || finished.length() == input.size)) {
            // The same version is never downloaded twice.
            events.send(EngineEvent.DownloadFinished(input.versionCode, finished.path))
            return Result.success()
        }
        finished.delete()

        val foreground = if (input.foreground) ForegroundProgress(DownloadNotification(applicationContext, id), ::setForeground) else null
        foreground?.start(files.part(input.versionCode).length(), input.size)

        val transfer =
            ApkTransfer(
                context = applicationContext,
                versionCode = input.versionCode,
                apkUrl = input.apkUrl,
                expectedSize = input.size,
                files = files,
                connections = WorkEnvironment.connectionFactory,
                freeSpace = WorkEnvironment.freeSpace ?: FreeSpace.forDevice(),
                clock = WorkEnvironment.clock,
                onProgress = { bytes, total ->
                    // A server can announce, and send, an empty file. DownloadProgress takes no total of 0,
                    // and the exception would end the worker without the engine ever hearing an outcome.
                    val progress = DownloadProgress(bytes, total?.takeIf { it > 0 })
                    events.send(EngineEvent.DownloadProgressed(input.versionCode, progress))
                    foreground?.update(bytes, total)
                },
            )
        return try {
            val file = transfer.run()
            events.send(EngineEvent.DownloadFinished(input.versionCode, file.path))
            Result.success()
        } catch (e: CancellationException) {
            // Stopped by WorkManager. The partial file stays for the next run.
            throw e
        } catch (e: StorageException) {
            runtime.logger?.log("The download of version ${input.versionCode} stopped: no room or not writable.", e)
            fail(events, input.versionCode, FailureReason.Storage)
        } catch (e: RejectedDownloadException) {
            runtime.logger?.log("The download of version ${input.versionCode} was refused: ${e.message}", e)
            fail(events, input.versionCode, FailureReason.Network)
        } catch (e: IOException) {
            if (runAttemptCount < MAX_RETRIES) {
                runtime.logger?.log("The download of version ${input.versionCode} was interrupted. Retrying.", e)
                Result.retry()
            } else {
                runtime.logger?.log("The download of version ${input.versionCode} failed after the last retry.", e)
                fail(events, input.versionCode, FailureReason.Network)
            }
        }
    }

    private fun fail(
        events: EventSink,
        versionCode: Long,
        reason: FailureReason,
    ): Result {
        events.send(EngineEvent.DownloadFailed(versionCode, reason))
        return Result.failure()
    }

    companion object {
        private const val TAG = "Renovo"

        /** Retries after the first run. WorkManager counts the first run as attempt 0. */
        const val MAX_RETRIES = 5
    }
}

/**
 * Runs the download as a foreground service and keeps its notification current.
 *
 * Android 12 and later refuse to start a foreground service while the app is in the background, and
 * a missing permission or a time limit can refuse it too. None of that may fail the download: after
 * the first refusal the worker goes on as a plain worker, which resumes across runs anyway.
 */
internal class ForegroundProgress(
    private val notification: DownloadNotification,
    private val setForeground: suspend (ForegroundInfo) -> Unit,
) {
    private var refused = false

    suspend fun start(
        bytes: Long,
        total: Long?,
    ) = update(bytes, total)

    suspend fun update(
        bytes: Long,
        total: Long?,
    ) {
        if (refused) return
        try {
            setForeground(notification.foregroundInfo(bytes, total))
        } catch (_: IllegalStateException) {
            // Includes ForegroundServiceStartNotAllowedException (API 31), which extends it.
            refused = true
        } catch (_: SecurityException) {
            // The host app's manifest lacks the dataSync permission or service type.
            refused = true
        }
    }
}
