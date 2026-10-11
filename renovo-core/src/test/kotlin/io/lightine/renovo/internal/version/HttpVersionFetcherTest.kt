package io.lightine.renovo.internal.version

import io.lightine.renovo.FailureReason
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.internal.FetchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

// Robolectric for org.json, see VersionFileParserTest.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class HttpVersionFetcherTest {
    private val versionFileUrl = "https://updates.example.com/app/version.json"
    private val validJson = """{"versionCode":42,"versionName":"1.8.0","apkUrl":"https://example.com/app-1.8.0.apk"}"""
    private val expectedInfo = UpdateInfo(42, "1.8.0", "https://example.com/app-1.8.0.apk")

    /** What the fake server answers for one request. */
    private class Reply(
        val status: Int = 200,
        val body: InputStream = ByteArrayInputStream(ByteArray(0)),
        val headers: Map<String, String> = emptyMap(),
        val failure: IOException? = null,
    )

    private class FakeConnection(
        url: URL,
        private val reply: Reply,
    ) : HttpURLConnection(url) {
        val sentHeaders = linkedMapOf<String, String>()
        var disconnected = false

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy(): Boolean = false

        override fun setRequestProperty(
            key: String,
            value: String,
        ) {
            sentHeaders[key] = value
        }

        override fun getResponseCode(): Int = reply.failure?.let { throw it } ?: reply.status

        override fun getHeaderField(name: String): String? = reply.headers.entries.firstOrNull { it.key.equals(name, true) }?.value

        override fun getInputStream(): InputStream = reply.body
    }

    /** Hands out one scripted reply per request and remembers every connection it made. */
    private class FakeServer(
        private vararg val replies: Reply,
    ) {
        val connections = mutableListOf<FakeConnection>()
        val factory: (URL) -> HttpURLConnection = { url ->
            // A test that makes more requests than it scripted gets the last reply again.
            FakeConnection(url, replies[minOf(connections.size, replies.lastIndex)]).also { connections += it }
        }
    }

    private class RecordingLogger : RenovoLogger {
        val messages = mutableListOf<String>()

        override fun log(
            message: String,
            error: Throwable?,
        ) {
            messages += message
        }
    }

    private fun json(text: String) = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

    private fun ok(
        body: String = validJson,
        etag: String? = null,
    ) = Reply(body = json(body), headers = listOfNotNull(etag?.let { "ETag" to it }).toMap())

    private fun redirect(
        location: String?,
        status: Int = 302,
    ) = Reply(status = status, headers = listOfNotNull(location?.let { "Location" to it }).toMap())

    private fun fetcher(
        server: FakeServer,
        url: String = versionFileUrl,
        logger: RenovoLogger? = null,
        headers: () -> Map<String, String> = { emptyMap() },
    ) = HttpVersionFetcher(url, headers, logger, server.factory)

    @Test
    fun `200 returns the parsed version and the ETag`() = runTest {
        val server = FakeServer(ok(etag = "\"abc\""))
        val result = fetcher(server).fetch(null)
        assertEquals(FetchResult.Found(expectedInfo, "\"abc\""), result)
    }

    @Test
    fun `200 without an ETag returns null for it`() = runTest {
        val result = fetcher(FakeServer(ok())).fetch("\"old\"")
        assertEquals(FetchResult.Found(expectedInfo, null), result)
    }

    @Test
    fun `the request carries Accept, the host headers and If-None-Match`() = runTest {
        val server = FakeServer(ok())
        fetcher(server, headers = { mapOf("Authorization" to "Bearer t0k3n", "X-App" to "demo") }).fetch("\"etag-1\"")
        val connection = server.connections.single()
        assertEquals(versionFileUrl, connection.url.toString())
        assertEquals("GET", connection.requestMethod)
        assertEquals("application/json", connection.sentHeaders["Accept"])
        assertEquals("Bearer t0k3n", connection.sentHeaders["Authorization"])
        assertEquals("demo", connection.sentHeaders["X-App"])
        assertEquals("\"etag-1\"", connection.sentHeaders["If-None-Match"])
        // The platform must not follow redirects itself, and the timeouts are the specified ones.
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(15_000, connection.connectTimeout)
        assertEquals(30_000, connection.readTimeout)
        assertTrue(connection.disconnected)
    }

    @Test
    fun `no If-None-Match is sent without an ETag`() = runTest {
        val server = FakeServer(ok())
        fetcher(server).fetch(null)
        assertFalse(server.connections.single().sentHeaders.containsKey("If-None-Match"))
    }

    @Test
    fun `a host header cannot replace If-None-Match`() = runTest {
        val server = FakeServer(ok())
        fetcher(server, headers = { mapOf("If-None-Match" to "\"forged\"") }).fetch("\"real\"")
        assertEquals("\"real\"", server.connections.single().sentHeaders["If-None-Match"])
    }

    @Test
    fun `the host headers are read again on every fetch`() = runTest {
        var counter = 0
        val server = FakeServer(ok())
        val fetcher = fetcher(server, headers = { mapOf("X-Count" to (++counter).toString()) })
        fetcher.fetch(null)
        fetcher.fetch(null)
        assertEquals(listOf("1", "2"), server.connections.map { it.sentHeaders["X-Count"] })
    }

    @Test
    fun `304 with an ETag is not modified`() = runTest {
        val server = FakeServer(Reply(status = 304))
        assertEquals(FetchResult.NotModified, fetcher(server).fetch("\"abc\""))
    }

    @Test
    fun `304 without an ETag is a failure, because nothing was asked`() = runTest {
        val server = FakeServer(Reply(status = 304))
        assertEquals(FetchResult.Failed(FailureReason.Network), fetcher(server).fetch(null))
    }

    @Test
    fun `other statuses are network failures`() = runTest {
        for (status in listOf(500, 503, 404, 401, 206, 204)) {
            val result = fetcher(FakeServer(Reply(status = status))).fetch(null)
            assertEquals("status $status", FetchResult.Failed(FailureReason.Network), result)
        }
    }

    @Test
    fun `a redirect from https to https is followed`() = runTest {
        val server =
            FakeServer(
                redirect("https://cdn.example.com/v/version.json", status = 301),
                ok(etag = "\"e\""),
            )
        val result = fetcher(server).fetch(null)
        assertEquals(FetchResult.Found(expectedInfo, "\"e\""), result)
        assertEquals(
            listOf(versionFileUrl, "https://cdn.example.com/v/version.json"),
            server.connections.map { it.url.toString() },
        )
    }

    @Test
    fun `a relative redirect target is resolved against the current URL`() = runTest {
        val server = FakeServer(redirect("/other/version.json", status = 307), ok())
        fetcher(server).fetch(null)
        assertEquals("https://updates.example.com/other/version.json", server.connections.last().url.toString())
    }

    @Test
    fun `a redirect target without a leading slash is resolved against the current folder`() = runTest {
        val server = FakeServer(redirect("beta/version.json"), ok())
        assertEquals(FetchResult.Found(expectedInfo, null), fetcher(server).fetch(null))
        assertEquals("https://updates.example.com/app/beta/version.json", server.connections.last().url.toString())
    }

    @Test
    fun `a query-only redirect target is resolved against the current folder`() = runTest {
        // java.net resolves a reference that is only a query by RFC 2396, which drops the last path
        // segment. This pins that result so a change to it is noticed.
        val server = FakeServer(redirect("?channel=beta"), ok())
        assertEquals(FetchResult.Found(expectedInfo, null), fetcher(server).fetch(null))
        assertEquals("https://updates.example.com/app/?channel=beta", server.connections.last().url.toString())
    }

    @Test
    fun `a redirect target that is not a valid URL is a network failure without a second request`() = runTest {
        for (location in listOf("https://[broken/version.json", "https://updates.example.com/a b.json", "   ")) {
            val logger = RecordingLogger()
            val server = FakeServer(redirect(location), ok())
            assertEquals(location, FetchResult.Failed(FailureReason.Network), fetcher(server, logger = logger).fetch(null))
            assertEquals(location, 1, server.connections.size)
            assertEquals(location, listOf("The version file redirect has no valid target, or the target is not https."), logger.messages)
        }
    }

    @Test
    fun `a redirect from https to http is rejected without a second request`() = runTest {
        val server = FakeServer(redirect("http://updates.example.com/version.json"), ok())
        assertEquals(FetchResult.Failed(FailureReason.Network), fetcher(server).fetch(null))
        assertEquals(1, server.connections.size)
    }

    @Test
    fun `a redirect without a target is a failure`() = runTest {
        val server = FakeServer(redirect(null), ok())
        assertEquals(FetchResult.Failed(FailureReason.Network), fetcher(server).fetch(null))
        assertEquals(1, server.connections.size)
    }

    @Test
    fun `five redirects are followed`() = runTest {
        val hops = (1..5).map { redirect("https://updates.example.com/hop$it") }
        val server = FakeServer(*hops.toTypedArray(), ok())
        assertEquals(FetchResult.Found(expectedInfo, null), fetcher(server).fetch(null))
        assertEquals(6, server.connections.size)
    }

    @Test
    fun `six redirects are rejected`() = runTest {
        val hops = (1..6).map { redirect("https://updates.example.com/hop$it") }
        val server = FakeServer(*hops.toTypedArray(), ok())
        assertEquals(FetchResult.Failed(FailureReason.Network), fetcher(server).fetch(null))
        // The sixth redirect is answered but not followed.
        assertEquals(6, server.connections.size)
    }

    @Test
    fun `a redirect loop ends at the limit`() = runTest {
        val server = FakeServer(redirect(versionFileUrl))
        assertEquals(FetchResult.Failed(FailureReason.Network), fetcher(server).fetch(null))
        assertEquals(6, server.connections.size)
    }

    @Test
    fun `the host headers follow a redirect on the same host`() = runTest {
        val server = FakeServer(redirect("https://updates.example.com/moved.json"), ok())
        fetcher(server, headers = { mapOf("Authorization" to "Bearer t0k3n") }).fetch("\"e\"")
        assertEquals("Bearer t0k3n", server.connections.last().sentHeaders["Authorization"])
    }

    @Test
    fun `the host headers do not follow a redirect to another host`() = runTest {
        val server = FakeServer(redirect("https://other.example.org/version.json"), ok())
        fetcher(server, headers = { mapOf("Authorization" to "Bearer t0k3n") }).fetch("\"e\"")
        val second = server.connections.last()
        assertFalse(second.sentHeaders.containsKey("Authorization"))
        assertEquals("application/json", second.sentHeaders["Accept"])
        assertEquals("\"e\"", second.sentHeaders["If-None-Match"])
    }

    @Test
    fun `an IOException is a network failure`() = runTest {
        val server = FakeServer(Reply(failure = IOException("connection reset")))
        assertEquals(FetchResult.Failed(FailureReason.Network), fetcher(server).fetch(null))
        assertTrue(server.connections.single().disconnected)
    }

    @Test
    fun `an IOException while reading the body is a network failure`() = runTest {
        val broken =
            object : InputStream() {
                override fun read(): Int = throw IOException("timeout")
            }
        val server = FakeServer(Reply(body = broken))
        assertEquals(FetchResult.Failed(FailureReason.Network), fetcher(server).fetch(null))
    }

    @Test
    fun `an IOException from opening the connection is a network failure`() = runTest {
        val fetcher = HttpVersionFetcher(versionFileUrl, { emptyMap() }, null, { throw IOException("no route") })
        assertEquals(FetchResult.Failed(FailureReason.Network), fetcher.fetch(null))
    }

    @Test
    fun `a header lambda that throws is a network failure and not an exception`() = runTest {
        val server = FakeServer(ok())
        val result = fetcher(server, headers = { error("token store is gone") }).fetch(null)
        assertEquals(FetchResult.Failed(FailureReason.Network), result)
        assertTrue(server.connections.isEmpty())
    }

    @Test
    fun `cancellation is not swallowed`() = runTest {
        val fetcher = HttpVersionFetcher(versionFileUrl, { emptyMap() }, null, { throw CancellationException("cancelled") })
        try {
            fetcher.fetch(null)
            fail("The cancellation was swallowed.")
        } catch (_: CancellationException) {
            // Expected: a cancelled check must unwind, not turn into a failure result.
        }
    }

    @Test
    fun `a body that is not a version file is a parse failure`() = runTest {
        assertEquals(FetchResult.Failed(FailureReason.Parse), fetcher(FakeServer(ok(body = "<html>Hi</html>"))).fetch(null))
        assertEquals(
            FetchResult.Failed(FailureReason.Parse),
            fetcher(FakeServer(ok(body = """{"versionCode":1}"""))).fetch(null),
        )
    }

    @Test
    fun `a body that is not valid UTF-8 is a parse failure`() = runTest {
        val bad = byteArrayOf('{'.code.toByte(), 0xC3.toByte(), 0x28, '}'.code.toByte())
        val server = FakeServer(Reply(body = ByteArrayInputStream(bad)))
        assertEquals(FetchResult.Failed(FailureReason.Parse), fetcher(server).fetch(null))
    }

    @Test
    fun `a body above 1 MiB is a parse failure`() = runTest {
        val padded = validJson + " ".repeat(MAX_BODY - validJson.length + 1)
        assertEquals(MAX_BODY + 1, padded.length)
        assertEquals(FetchResult.Failed(FailureReason.Parse), fetcher(FakeServer(ok(body = padded))).fetch(null))
    }

    @Test
    fun `a body of exactly 1 MiB is accepted`() = runTest {
        val padded = validJson + " ".repeat(MAX_BODY - validJson.length)
        assertEquals(MAX_BODY, padded.length)
        assertEquals(FetchResult.Found(expectedInfo, null), fetcher(FakeServer(ok(body = padded))).fetch(null))
    }

    @Test
    fun `a body that never ends is cut off`() = runTest {
        val endless =
            object : InputStream() {
                override fun read(): Int = ' '.code

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    b.fill(' '.code.toByte(), off, off + len)
                    return len
                }
            }
        val server = FakeServer(Reply(body = endless))
        assertEquals(FetchResult.Failed(FailureReason.Parse), fetcher(server).fetch(null))
    }

    @Test
    fun `an http URL fails without a request`() = runTest {
        val server = FakeServer(ok())
        val result = fetcher(server, url = "http://updates.example.com/version.json").fetch(null)
        assertEquals(FetchResult.Failed(FailureReason.Network), result)
        assertTrue(server.connections.isEmpty())
    }

    @Test
    fun `other schemes fail without a request`() = runTest {
        for (url in listOf("ftp://example.com/v.json", "file:///data/v.json", "not a url", "")) {
            val server = FakeServer(ok())
            assertEquals(url, FetchResult.Failed(FailureReason.Network), fetcher(server, url = url).fetch(null))
            assertTrue(server.connections.isEmpty())
        }
    }

    @Test
    fun `each failure logs one line without header values or the URL`() = runTest {
        val logger = RecordingLogger()
        val headers = { mapOf("Authorization" to "Bearer t0k3n") }
        val urlWithToken = "https://updates.example.com/version.json?key=s3cret"
        val replies =
            listOf(
                Reply(status = 500),
                Reply(failure = IOException("boom")),
                ok(body = "nope"),
                redirect("http://updates.example.com/x?key=s3cret"),
            )
        for (reply in replies) {
            logger.messages.clear()
            fetcher(FakeServer(reply), url = urlWithToken, logger = logger, headers = headers).fetch("\"e\"")
            assertEquals(1, logger.messages.size)
            assertFalse(logger.messages.single().contains("t0k3n"))
            assertFalse(logger.messages.single().contains("s3cret"))
        }
    }

    @Test
    fun `success and not modified log nothing`() = runTest {
        val logger = RecordingLogger()
        fetcher(FakeServer(ok()), logger = logger).fetch(null)
        fetcher(FakeServer(Reply(status = 304)), logger = logger).fetch("\"e\"")
        assertTrue(logger.messages.isEmpty())
    }

    @Test
    fun `a failed check without a logger does not throw`() = runTest {
        assertEquals(FetchResult.Failed(FailureReason.Network), fetcher(FakeServer(Reply(status = 500)), logger = null).fetch(null))
    }

    private companion object {
        const val MAX_BODY = 1024 * 1024
    }
}
