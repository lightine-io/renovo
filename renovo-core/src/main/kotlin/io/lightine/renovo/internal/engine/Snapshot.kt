package io.lightine.renovo.internal.engine

import io.lightine.renovo.FailureReason
import io.lightine.renovo.UpdateInfo

/**
 * Everything that must survive process death. The engine is its only author; the [Store] writes
 * it to disk whenever the engine emits [Effect.Persist].
 *
 * On start the engine rebuilds its state from the last snapshot ([EngineEvent.Restored]).
 */
internal data class Snapshot(
    /** ETag of the last version file response, sent as If-None-Match. Only set together with [latest]. */
    val etag: String? = null,
    /**
     * The version information of the last successful check or submit. Kept so a 304 response can
     * be evaluated again, and so a forced update still blocks after a restart without a network.
     */
    val latest: UpdateInfo? = null,
    /** The version being downloaded, or downloaded. Null when no download is wanted. */
    val download: DownloadRecord? = null,
    /** Version code that the user allowed to download with mobile data, once. */
    val mobileDataVersionCode: Long? = null,
    /**
     * A file that failed verification. The same file is not downloaded again automatically, so a
     * broken release does not cost a full download at every check; a user retry clears it.
     */
    val rejected: Rejection? = null,
    /** Installed version code at the moment the device was found unable to install. */
    val unsupportedAtVersionCode: Long? = null,
    /** Installed version code seen at the last start. A change means an update was installed. */
    val lastInstalledVersionCode: Long? = null,
)

/**
 * The download of one version.
 *
 * [filePath] is null while the download runs. [verified] is true only after every check passed.
 */
internal data class DownloadRecord(
    val info: UpdateInfo,
    val filePath: String? = null,
    val verified: Boolean = false,
)

/** A downloaded file of [info] that failed verification with [reason]. */
internal data class Rejection(
    val info: UpdateInfo,
    val reason: FailureReason,
)
