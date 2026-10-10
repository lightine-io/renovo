package io.lightine.renovo.internal.version

import io.lightine.renovo.FailureReason
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.VersionSource
import io.lightine.renovo.internal.FetchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class SourceVersionFetcherTest {
    private val info = UpdateInfo(42, "1.8.0", "https://example.com/app-1.8.0.apk")

    private class RecordingLogger : RenovoLogger {
        val entries = mutableListOf<Pair<String, Throwable?>>()

        override fun log(
            message: String,
            error: Throwable?,
        ) {
            entries += message to error
        }
    }

    @Test
    fun `a returned version is found, without an ETag`() = runTest {
        val result = SourceVersionFetcher({ info }, null).fetch(null)
        assertEquals(FetchResult.Found(info, null), result)
    }

    @Test
    fun `an ETag given by the engine is ignored and never answered with not modified`() = runTest {
        val result = SourceVersionFetcher({ info }, null).fetch("\"old\"")
        assertEquals(FetchResult.Found(info, null), result)
    }

    @Test
    fun `an IOException is a network failure`() = runTest {
        val logger = RecordingLogger()
        val result = SourceVersionFetcher({ throw IOException("offline") }, logger).fetch(null)
        assertEquals(FetchResult.Failed(FailureReason.Network), result)
        assertEquals(1, logger.entries.size)
    }

    @Test
    fun `an IllegalArgumentException from UpdateInfo is a parse failure`() = runTest {
        val logger = RecordingLogger()
        // Version code 0 is what the UpdateInfo constructor rejects.
        val source = VersionSource { UpdateInfo(0, "1.0", "https://example.com/a.apk") }
        val result = SourceVersionFetcher(source, logger).fetch(null)
        assertEquals(FetchResult.Failed(FailureReason.Parse), result)
        assertEquals(1, logger.entries.size)
    }

    @Test
    fun `any other exception is a network failure and is logged`() = runTest {
        val logger = RecordingLogger()
        val boom = IllegalStateException("the host app's API client is closed")
        val result = SourceVersionFetcher({ throw boom }, logger).fetch(null)
        assertEquals(FetchResult.Failed(FailureReason.Network), result)
        assertEquals(1, logger.entries.size)
        assertTrue(logger.entries.single().second === boom)
    }

    @Test
    fun `a failure without a logger does not throw`() = runTest {
        val result = SourceVersionFetcher({ throw RuntimeException("x") }, null).fetch(null)
        assertEquals(FetchResult.Failed(FailureReason.Network), result)
    }

    @Test
    fun `cancellation is not swallowed`() = runTest {
        val fetcher = SourceVersionFetcher({ throw CancellationException("cancelled") }, null)
        try {
            fetcher.fetch(null)
            fail("The cancellation was swallowed.")
        } catch (_: CancellationException) {
            // Expected: a cancelled check must unwind, not turn into a failure result.
        }
    }
}
