package io.lightine.renovo.testserver

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext

/** A started server. [close] stops it and drops open connections. */
internal class RunningServer(
    private val http: HttpsServer,
    private val pool: ExecutorService,
) : AutoCloseable {
    /** The port the server listens on. Differs from the requested one when that was 0. */
    val port: Int get() = http.address.port

    override fun close() {
        http.stop(0)
        pool.shutdownNow()
    }
}

/** Starts the server on [options], answering TLS with [sslContext]. [log] gets one line per request. */
internal fun startServer(
    options: Options,
    sslContext: SSLContext,
    log: (String) -> Unit = { println(it) },
): RunningServer {
    val http = HttpsServer.create(InetSocketAddress(InetAddress.getByName(options.bind), options.port), 0)
    http.httpsConfigurator = HttpsConfigurator(sslContext)
    // Daemon threads, so that only the server's own thread decides how long the process lives. A pool
    // with several threads lets a resumed download arrive while the cut one is still being closed.
    val pool =
        Executors.newCachedThreadPool { task ->
            Thread(task, "test-server-request").apply { isDaemon = true }
        }
    http.executor = pool
    http.createContext("/", FileHandler(options.dir, options.cutAfter, log))
    http.start()
    return RunningServer(http, pool)
}

/** What to answer: status, headers, and the part of a file to send (if any). */
private class Reply(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: Body? = null,
)

private class Body(
    val file: File,
    val offset: Long,
    val length: Long,
)

private class CachedTag(
    val length: Long,
    val modified: Long,
    val etag: String,
)

private class FileHandler(
    private val root: File,
    private val cutAfter: Long?,
    private val log: (String) -> Unit,
) : HttpHandler {
    // Only the first APK download is cut; the retry that follows must get through.
    private val cutArmed = AtomicBoolean(cutAfter != null)
    private val tags = ConcurrentHashMap<File, CachedTag>()

    override fun handle(exchange: HttpExchange) {
        val method = exchange.requestMethod
        val path = exchange.requestURI.rawPath
        var status = 0
        var sent = 0L
        var note = ""
        try {
            val reply = route(exchange, method, path)
            status = reply.status
            val cutAt = cutPoint(method, path, reply)
            if (cutAt != null) note = " (cut after $cutAt bytes)"
            sent = send(exchange, reply, headOnly = method == "HEAD", cutAt = cutAt)
        } catch (e: IOException) {
            // The client went away. There is nobody left to answer.
            note = " (${e.javaClass.simpleName}: ${e.message})"
        } finally {
            if (status != 0) log("$method $path $status $sent$note") else log("$method $path - $sent$note")
            // Closing a response that is shorter than its Content-Length also closes the connection,
            // which is what the cut needs. The exception that says so is expected.
            try {
                exchange.close()
            } catch (_: IOException) {
                // Expected after a cut, and harmless after a client that left.
            }
        }
    }

    private fun route(
        exchange: HttpExchange,
        method: String,
        path: String,
    ): Reply {
        val match = ROUTE.matchEntire(path) ?: return Reply(404)
        val file = File(root, match.groupValues[1] + "." + match.groupValues[2])
        if (!file.isFile) return Reply(404)
        if (method != "GET" && method != "HEAD") return Reply(405, mapOf("Allow" to "GET, HEAD"))

        val isApk = match.groupValues[2] == "apk"
        val length = file.length()
        val etag = etagOf(file, length)
        val validators = buildMap {
            put("ETag", etag)
            if (isApk) put("Last-Modified", httpDate(file.lastModified()))
        }
        val contentType = if (isApk) APK_TYPE else JSON_TYPE
        val request = exchange.requestHeaders

        if (matchesNoneMatch(request.getFirst("If-None-Match"), etag)) return Reply(304, validators)

        // If-Range is judged before Range: a stale validator turns the range into "send everything".
        val ifRange = request.getFirst("If-Range")?.trim()
        val rangeApplies = ifRange == null || ifRange == etag || ifRange == validators["Last-Modified"]
        val range = if (rangeApplies) parseRange(request.getFirst("Range"), length) else RangeRequest.Whole

        val common = validators + mapOf("Content-Type" to contentType) + if (isApk) mapOf("Accept-Ranges" to "bytes") else emptyMap()
        return when (range) {
            RangeRequest.Whole -> Reply(200, common, Body(file, 0, length))

            is RangeRequest.Part ->
                Reply(
                    206,
                    common + ("Content-Range" to "bytes ${range.start}-${range.end}/$length"),
                    Body(file, range.start, range.length),
                )

            RangeRequest.Unsatisfiable -> Reply(416, common + ("Content-Range" to "bytes */$length"))
        }
    }

    /** The number of body bytes after which this response is cut, or null for a normal one. */
    private fun cutPoint(
        method: String,
        path: String,
        reply: Reply,
    ): Long? {
        val body = reply.body
        val limit = cutAfter
        if (limit == null || method != "GET" || !path.endsWith(".apk") || body == null || body.length == 0L) return null
        // Taken by the first download that has a body, even when it is shorter than the limit, so the
        // switch means "the first download", not "the first one that is long enough".
        if (!cutArmed.compareAndSet(true, false)) return null
        return limit.takeIf { it < body.length }
    }

    /** Writes the reply and returns the number of body bytes sent. */
    private fun send(
        exchange: HttpExchange,
        reply: Reply,
        headOnly: Boolean,
        cutAt: Long?,
    ): Long {
        reply.headers.forEach { (name, value) -> exchange.responseHeaders.set(name, value) }
        val body = reply.body
        if (body == null || headOnly || body.length == 0L) {
            // The JDK server writes Content-Length itself for a body, but for HEAD it must be set by
            // hand: it is the length a GET would send. -1 means "no body follows".
            if (body != null && headOnly) exchange.responseHeaders.set("Content-Length", body.length.toString())
            exchange.sendResponseHeaders(reply.status, NO_BODY)
            return 0
        }
        exchange.sendResponseHeaders(reply.status, body.length)
        val toSend = cutAt ?: body.length
        var sent = 0L
        exchange.responseBody.let { out ->
            body.file.inputStream().use { input ->
                input.skipNBytes(body.offset)
                val buffer = ByteArray(BUFFER_SIZE)
                while (sent < toSend) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), toSend - sent).toInt())
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    sent += read
                }
            }
            out.flush()
        }
        return sent
    }

    /** A content hash, so the tag changes exactly when the bytes do. Kept while the file stays as it is. */
    private fun etagOf(
        file: File,
        length: Long,
    ): String {
        val modified = file.lastModified()
        tags[file]?.let { if (it.length == length && it.modified == modified) return it.etag }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val etag = "\"" + digest.digest().joinToString("") { "%02x".format(it) } + "\""
        tags[file] = CachedTag(length, modified, etag)
        return etag
    }

    private fun matchesNoneMatch(
        header: String?,
        etag: String,
    ): Boolean {
        if (header == null) return false
        // A weak comparison, as If-None-Match requires: "W/" is ignored.
        return header.split(',').map { it.trim().removePrefix("W/") }.any { it == "*" || it == etag }
    }

    private fun httpDate(millis: Long): String = HTTP_DATE.format(Instant.ofEpochMilli(millis))

    private companion object {
        // Only a plain file name in the served folder; no slashes, so no way out of it.
        val ROUTE = Regex("""/([A-Za-z0-9_][A-Za-z0-9._-]*)\.(json|apk)""")
        const val JSON_TYPE = "application/json"
        const val APK_TYPE = "application/vnd.android.package-archive"
        const val BUFFER_SIZE = 64 * 1024
        const val NO_BODY = -1L

        // HTTP dates have a two digit day and always say GMT, which RFC_1123_DATE_TIME does not guarantee.
        val HTTP_DATE: DateTimeFormatter =
            DateTimeFormatter
                .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
                .withZone(ZoneOffset.UTC)
    }
}
