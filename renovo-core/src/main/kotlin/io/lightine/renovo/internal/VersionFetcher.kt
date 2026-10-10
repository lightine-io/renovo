package io.lightine.renovo.internal

import io.lightine.renovo.FailureReason
import io.lightine.renovo.UpdateInfo

/**
 * Runs one check: fetches the version file, or asks the host app's [io.lightine.renovo.VersionSource].
 *
 * The implementing task must guarantee:
 * - HTTPS only; any other scheme, or a redirect to one, is [FetchResult.Failed] with
 *   [FailureReason.Network], never a request.
 * - With a non-null `etag`, the request sends `If-None-Match`, and a 304 is [FetchResult.NotModified].
 * - The host app's version headers are read before every request.
 * - A body that cannot be parsed, or values the [UpdateInfo] constructor rejects, is
 *   [FailureReason.Parse]; an I/O error is [FailureReason.Network]. It never throws, except
 *   for coroutine cancellation.
 * - A [io.lightine.renovo.VersionSource] never yields [FetchResult.NotModified] or an ETag.
 */
internal interface VersionFetcher {
    suspend fun fetch(etag: String?): FetchResult
}

/** The outcome of [VersionFetcher.fetch]. */
internal sealed interface FetchResult {
    data class Found(
        val info: UpdateInfo,
        val etag: String?,
    ) : FetchResult

    data object NotModified : FetchResult

    data class Failed(
        val reason: FailureReason,
    ) : FetchResult
}
