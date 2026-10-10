package io.lightine.renovo.internal

import io.lightine.renovo.Network
import io.lightine.renovo.UpdateInfo

/**
 * Downloads the APK of one version into app-internal storage, as unique WorkManager work.
 *
 * The implementing task must guarantee:
 * - One download at a time. [enqueue] for the version that is already queued or running continues
 *   that download (taking the new network constraint and foreground mode); it never starts a
 *   second one and never repeats bytes already on disk.
 * - Resume with `Range`, validated by `If-Range`; a changed file on the server starts over.
 * - `network` maps to WorkManager constraints. A download the user started while the app was in the
 *   foreground runs as a foreground worker of type `dataSync` with a progress notification; any other
 *   runs as a plain worker. If the system refuses the foreground service, it goes on as a plain worker.
 * - Retries with exponential backoff, then reports a final failure.
 * - Reports through the engine's event sink, always with `info.versionCode`:
 *   `DownloadProgressed` and `DownloadWaitingForNetwork` while it runs, then exactly one of
 *   `DownloadFinished` (path of the complete file) or `DownloadFailed` (Network after the retries;
 *   Storage when space runs out or the file cannot be written; Cancelled when the user taps cancel
 *   in the progress notification). A [cancel] from the engine reports nothing.
 * - Keeps the partial file of a failed download, so the next [enqueue] of that version resumes,
 *   and deletes the files of every other version code.
 * - Rejects a non-https `apkUrl` and any redirect away from https.
 */
internal interface Downloader {
    fun enqueue(
        info: UpdateInfo,
        network: Network,
        userInitiated: Boolean,
    )

    /** Stops the download, if any, and deletes its partial file. */
    fun cancel()
}
