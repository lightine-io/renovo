package io.lightine.renovo.internal.version

import io.lightine.renovo.FailureReason
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.internal.FetchResult
import io.lightine.renovo.internal.VersionFetcher
import io.lightine.renovo.internal.isHttpsUrl
import io.lightine.renovo.internal.parseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Fetches the version file over HTTPS. When an ETag is known, the request asks the server to
 * answer 304 Not Modified if the file has not changed, so an unchanged file is not sent again.
 *
 * Redirects are followed here and not by [HttpURLConnection]. The platform follows up to five and
 * silently stops when the protocol changes. It cannot be told to refuse a switch away from https,
 * to keep the host app's headers from reaching another host, or to report either case.
 */
internal class HttpVersionFetcher(
    private val url: String,
    private val headers: () -> Map<String, String>,
    private val logger: RenovoLogger?,
    private val connectionFactory: (URL) -> HttpURLConnection = ::openConnection,
) : VersionFetcher {
    override suspend fun fetch(etag: String?): FetchResult = withContext(Dispatchers.IO) {
        try {
            request(etag)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VersionFileException) {
            fail(FailureReason.Parse, e.message ?: "The version file could not be read.", null)
        } catch (e: IOException) {
            fail(FailureReason.Network, "The version file request failed.", e)
        } catch (e: Exception) {
            // The host app's header lambda, a header the platform rejects, or a bug in the
            // connection. None of them may leave the check hanging in a worker.
            fail(FailureReason.Network, "The version file request could not be made.", e)
        }
    }

    private suspend fun request(etag: String?): FetchResult {
        val origin =
            parseUrl(url)?.takeIf { isHttpsUrl(url) }
                ?: return fail(FailureReason.Network, "The version file URL is not an https URL.", null)
        // Read once per check, so a token that was refreshed since the last check is used.
        val hostHeaders = headers()
        var current = origin
        for (hop in 0..MAX_REDIRECTS) {
            currentCoroutineContext().ensureActive()
            val connection = connectionFactory(current)
            try {
                configure(connection, etag, hostHeaders.takeIf { sameOrigin(origin, current) })
                val status = connection.responseCode
                when {
                    status == HttpURLConnection.HTTP_OK -> {
                        val info = VersionFileParser.parse(readBody(connection.inputStream))
                        return FetchResult.Found(info, connection.getHeaderField(ETAG_HEADER)?.takeIf { it.isNotBlank() })
                    }

                    // Without If-None-Match a 304 cannot be a valid answer, and treating it as
                    // "nothing changed" would hide a broken server.
                    status == HttpURLConnection.HTTP_NOT_MODIFIED && etag != null -> return FetchResult.NotModified

                    status in REDIRECT_STATUSES -> {
                        if (hop == MAX_REDIRECTS) {
                            return fail(FailureReason.Network, "The version file URL redirects more than $MAX_REDIRECTS times.", null)
                        }
                        current = nextHop(current, connection.getHeaderField(LOCATION_HEADER))
                            ?: return fail(FailureReason.Network, "The version file redirect has no valid target, or the target is not https.", null)
                    }

                    else -> return fail(FailureReason.Network, "The version file request returned HTTP $status.", null)
                }
            } finally {
                connection.disconnect()
            }
        }
        // The loop returns on its last pass; this keeps the compiler's flow analysis honest.
        return fail(FailureReason.Network, "The version file request ended without an answer.", null)
    }

    private fun configure(
        connection: HttpURLConnection,
        etag: String?,
        hostHeaders: Map<String, String>?,
    ) {
        connection.requestMethod = "GET"
        // Off, so that no redirect is followed before it has been checked.
        connection.instanceFollowRedirects = false
        // The ETag logic belongs to this class, not to a response cache that may or may not exist.
        connection.useCaches = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/json")
        // After Accept so the host app can replace it, before If-None-Match so it cannot break
        // the conditional request.
        hostHeaders?.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        if (etag != null) connection.setRequestProperty("If-None-Match", etag)
    }

    /** Resolves a redirect target against [from], or returns null when there is none, it is not valid, or it is not https. */
    private fun nextHop(
        from: URL,
        location: String?,
    ): URL? {
        val target = location?.let { parseUrl(it, base = from) } ?: return null
        return if (isHttpsUrl(target.toExternalForm())) target else null
    }

    /**
     * True when [b] is the same host and port as [a].
     *
     * The host app's headers often hold a credential. Following a redirect to another host must
     * not hand it over, the way browsers and curl also drop credentials there.
     */
    private fun sameOrigin(
        a: URL,
        b: URL,
    ): Boolean = a.host.equals(b.host, ignoreCase = true) && portOf(a) == portOf(b)

    private fun portOf(url: URL): Int = if (url.port == -1) url.defaultPort else url.port

    private fun readBody(stream: InputStream): String {
        val bytes =
            stream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (out.size() + read > MAX_BODY_BYTES) throw VersionFileException("The version file is larger than 1 MiB.")
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        // Strict decoding: String(bytes) would quietly swap broken bytes for U+FFFD and let a
        // damaged version name through.
        return try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            throw VersionFileException("The version file is not valid UTF-8.")
        }
    }

    private fun fail(
        reason: FailureReason,
        message: String,
        error: Throwable?,
    ): FetchResult.Failed {
        log(message, error)
        return FetchResult.Failed(reason)
    }

    // One line per failure. The message never holds a URL or a header value.
    private fun log(
        message: String,
        error: Throwable?,
    ) {
        logger?.log(message, error)
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val MAX_BODY_BYTES = 1024 * 1024
        const val BUFFER_SIZE = 8 * 1024
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val ETAG_HEADER = "ETag"
        const val LOCATION_HEADER = "Location"
        val REDIRECT_STATUSES =
            setOf(
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307,
                308,
            )
    }
}

private fun openConnection(url: URL): HttpURLConnection = url.openConnection() as? HttpURLConnection ?: throw IOException("The URL did not open an HTTP connection.")
