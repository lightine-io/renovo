package io.lightine.renovo.internal.work

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One request as the fake server saw it. Header names are lower-cased. */
internal class RecordedRequest(
    val url: URL,
    val headers: Map<String, String>,
)

/**
 * What the fake server answers.
 *
 * @property cutAfter When set, the body stream fails with an [IOException] after this many bytes.
 * @property chunk How many bytes one `read` returns at most. Small values make many reads.
 * @property blockAfter When set, the body stream blocks after this many bytes until the connection is
 *   disconnected, like a socket that waits for data.
 */
internal class FakeResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    val cutAfter: Int? = null,
    val chunk: Int = Int.MAX_VALUE,
    val blockAfter: Int? = null,
    val onRead: () -> Unit = {},
)

/** A scripted server. [handler] decides the answer to each request. */
internal class FakeServer(
    var handler: (RecordedRequest) -> FakeResponse,
) {
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    val factory = ConnectionFactory { url -> FakeConnection(url, this) }
    val connections = CopyOnWriteArrayList<FakeConnection>()

    fun answer(request: RecordedRequest): FakeResponse {
        requests.add(request)
        return handler(request)
    }
}

/**
 * A server for one file that honors `Range` and `If-Range` like a real one.
 *
 * @param etag The strong ETag it sends, or null for a server with no validator at all.
 */
internal class FileServerBehavior(
    private val body: ByteArray,
    private val etag: String? = "\"v1\"",
) {
    fun respond(
        request: RecordedRequest,
        cutAfter: Int? = null,
        chunk: Int = Int.MAX_VALUE,
        onRead: () -> Unit = {},
    ): FakeResponse {
        val range = request.headers["range"]
        val ifRange = request.headers["if-range"]
        val common = buildMap { if (etag != null) put("ETag", etag) }
        if (range != null && (ifRange == null || ifRange == etag)) {
            val start = Regex("""bytes=(\d+)-""").matchEntire(range)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (start >= body.size) return FakeResponse(416)
            val part = body.copyOfRange(start, body.size)
            return FakeResponse(
                status = 206,
                headers = common + mapOf("Content-Range" to "bytes $start-${body.size - 1}/${body.size}", "Content-Length" to "${part.size}"),
                body = part,
                cutAfter = cutAfter,
                chunk = chunk,
                onRead = onRead,
            )
        }
        return FakeResponse(
            status = 200,
            headers = common + mapOf("Content-Length" to "${body.size}"),
            body = body,
            cutAfter = cutAfter,
            chunk = chunk,
            onRead = onRead,
        )
    }
}

internal class FakeConnection(
    url: URL,
    private val server: FakeServer,
) : HttpURLConnection(url) {
    private val requestHeaders = mutableMapOf<String, String>()
    private val released = CountDownLatch(1)
    private var cached: FakeResponse? = null

    @Volatile
    var disconnected = false
        private set

    init {
        server.connections.add(this)
    }

    private val response: FakeResponse
        get() = cached ?: server.answer(RecordedRequest(url, requestHeaders.toMap())).also { cached = it }

    override fun setRequestProperty(
        key: String,
        value: String,
    ) {
        requestHeaders[key.lowercase()] = value
    }

    override fun connect() = Unit

    override fun disconnect() {
        disconnected = true
        released.countDown()
    }

    override fun usingProxy(): Boolean = false

    override fun getResponseCode(): Int = response.status

    override fun getHeaderField(name: String?): String? = response.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    override fun getContentLengthLong(): Long = getHeaderField("Content-Length")?.toLongOrNull() ?: -1

    override fun getInputStream(): InputStream {
        val answer = response
        val source = ByteArrayInputStream(answer.body)
        return object : InputStream() {
            private var delivered = 0

            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                answer.onRead()
                val cut = answer.cutAfter
                if (cut != null && delivered >= cut) throw IOException("Connection reset")
                val block = answer.blockAfter
                if (block != null && delivered >= block) {
                    released.await(10, TimeUnit.SECONDS)
                    throw IOException("Socket closed")
                }
                var limit = minOf(length, answer.chunk)
                if (cut != null) limit = minOf(limit, cut - delivered)
                if (block != null) limit = minOf(limit, block - delivered)
                val read = source.read(buffer, offset, limit)
                if (read > 0) delivered += read
                return read
            }
        }
    }
}
