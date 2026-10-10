package io.lightine.renovo.internal.version

import io.lightine.renovo.FailureReason
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.VersionSource
import io.lightine.renovo.internal.FetchResult
import io.lightine.renovo.internal.VersionFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Asks the host app's [VersionSource] for the latest version.
 *
 * The result never carries an ETag and is never "not modified": the source has no conditional
 * request, so the engine compares the version codes itself.
 */
internal class SourceVersionFetcher(
    private val source: VersionSource,
    private val logger: RenovoLogger?,
) : VersionFetcher {
    // The public contract of VersionSource promises a background thread, whatever called us.
    override suspend fun fetch(etag: String?): FetchResult = withContext(Dispatchers.IO) {
        try {
            FetchResult.Found(source.latest(), null)
        } catch (e: CancellationException) {
            // First, because CancellationException is an IllegalStateException and would
            // otherwise be swallowed by the catch-all below.
            throw e
        } catch (e: IOException) {
            fail(FailureReason.Network, "The version source reported a network failure.", e)
        } catch (e: IllegalArgumentException) {
            // What UpdateInfo's constructor throws for a value it rejects.
            fail(FailureReason.Parse, "The version source returned a value that is not allowed.", e)
        } catch (e: Exception) {
            fail(FailureReason.Network, "The version source failed.", e)
        }
    }

    private fun fail(
        reason: FailureReason,
        message: String,
        error: Throwable,
    ): FetchResult {
        logger?.log(message, error)
        return FetchResult.Failed(reason)
    }
}
