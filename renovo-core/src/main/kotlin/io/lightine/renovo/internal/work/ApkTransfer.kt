package io.lightine.renovo.internal.work

import android.content.Context
import io.lightine.renovo.internal.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

/**
 * Moves the bytes of one APK from the server into `<versionCode>.apk.part`, and turns that file into
 * `<versionCode>.apk` when it is complete.
 *
 * Plain Android-free file and HTTP code on purpose: [DownloadWorker] adds the WorkManager parts around
 * it. It resumes a partial file with `Range`, validated by `If-Range`, so bytes already on disk are
 * never fetched again and a file that changed on the server is fetched from the start.
 *
 * Failures are exceptions: [StorageException] when the disk is the problem, [RejectedDownloadException]
 * when a redirect leaves https, any other [IOException] for a network problem that is worth retrying.
 *
 * @param onProgress Called with the bytes on disk and the total (null when unknown). Already
 *   throttled; the last call, with everything downloaded, is never skipped.
 */
internal class ApkTransfer(
    private val context: Context,
    private val versionCode: Long,
    private val apkUrl: String,
    private val expectedSize: Long?,
    private val files: DownloadFiles,
    private val connections: ConnectionFactory,
    private val freeSpace: FreeSpace,
    clock: Clock,
    private val onProgress: suspend (bytesOnDisk: Long, total: Long?) -> Unit,
) {
    private val throttle = ProgressThrottle(clock)

    /** Where a resumed download continues from. [validator] is null exactly when [offset] is 0. */
    private class ResumePoint(
        val offset: Long,
        val validator: String?,
    )

    /** What a 206 response says about the range it carries. */
    private class ContentRange(
        val start: Long,
        val total: Long?,
    )

    /** Returns the complete file. */
    suspend fun run(): File = withContext(Dispatchers.IO) {
        if (!files.ensureDirectory()) throw StorageException("The download folder cannot be created.")
        for (round in 0..MAX_START_OVERS) {
            val resume = resumePoint()
            // A crash between the last byte and the rename leaves a complete partial file. Asking the
            // server for the range after the end would only draw a 416, so skip the request.
            if (resume.offset > 0 && expectedSize != null && resume.offset == expectedSize) {
                return@withContext finish(resume.offset)
            }
            if (expectedSize != null) requireSpace(expectedSize - resume.offset)
            val bytes = attempt(resume)
            if (bytes != null) return@withContext finish(bytes)
            // The server answered 416, or a 206 for another range than the one asked for. The bytes on
            // disk cannot be trusted, so the next round starts from zero. A server that does this
            // again on that round is broken, and the loop ends.
            files.deletePartial(versionCode)
        }
        throw IOException("The server keeps refusing to send the file from the start.")
    }

    private suspend fun finish(bytes: Long): File {
        onProgress(bytes, bytes)
        return files.complete(versionCode)
    }

    private fun resumePoint(): ResumePoint {
        val part = files.part(versionCode)
        val length = if (part.isFile) part.length() else 0L
        val validator = files.readValidator(versionCode)
        // Without a validator the file cannot be checked against the server's, and a partial file
        // longer than the whole file is stale. Both start over.
        if (length > 0 && (validator == null || (expectedSize != null && length > expectedSize))) {
            files.deletePartial(versionCode)
            return ResumePoint(0, null)
        }
        if (length == 0L) files.writeValidator(versionCode, null)
        return ResumePoint(length, validator.takeIf { length > 0 })
    }

    /** Returns the bytes on disk when the file is complete, or null when the download must start over. */
    private suspend fun attempt(resume: ResumePoint): Long? {
        val connection = connect(resume)
        currentCoroutineContext().ensureActive()
        return coroutineScope {
            // A blocked read does not react to coroutine cancellation. Closing the connection from
            // here makes it fail at once, so a worker that WorkManager stopped lets go of the file
            // before its successor starts writing to it.
            val watcher =
                launch(Dispatchers.Default) {
                    try {
                        awaitCancellation()
                    } finally {
                        connection.disconnect()
                    }
                }
            try {
                handle(connection, resume)
            } finally {
                watcher.cancel()
            }
        }
    }

    /** Opens the connection, following at most [MAX_REDIRECTS] redirects, none of them away from https. */
    private fun connect(resume: ResumePoint): HttpURLConnection {
        var url = parse(apkUrl)
        for (hop in 0..MAX_REDIRECTS) {
            if (!url.protocol.equals("https", ignoreCase = true)) {
                throw RejectedDownloadException("The download would leave https.")
            }
            val connection = connections.open(url)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            // Range offsets and byte counts must describe the file itself, not a gzip form of it that
            // HttpURLConnection would unpack on its own.
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (resume.offset > 0 && resume.validator != null) {
                connection.setRequestProperty("Range", "bytes=${resume.offset}-")
                connection.setRequestProperty("If-Range", resume.validator)
            }
            val status = connection.responseCode
            if (status !in REDIRECTS) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (hop == MAX_REDIRECTS || location == null) {
                throw RejectedDownloadException("The server redirects too often.")
            }
            url = parse(location, base = url)
        }
        throw RejectedDownloadException("The server redirects too often.")
    }

    private fun parse(
        spec: String,
        base: URL? = null,
    ): URL = try {
        if (base == null) URL(spec) else URL(base, spec)
    } catch (_: MalformedURLException) {
        // The exception's message repeats the offending text, which for a redirect is whatever the
        // server sent. The worker logs this message, so it stays fixed.
        throw RejectedDownloadException("The download address is not valid.")
    }

    private suspend fun handle(
        connection: HttpURLConnection,
        resume: ResumePoint,
    ): Long? = when (val status = connection.responseCode) {
        // A 200 to a range request means the server ignored it or the file changed. Either way the
        // body is the whole file, and the old partial file is replaced.
        HttpURLConnection.HTTP_OK -> {
            val length = connection.contentLengthLong.takeIf { it >= 0 }
            receive(connection, offset = 0, append = false, serverTotal = length, validator = validatorOf(connection))
        }

        HttpURLConnection.HTTP_PARTIAL -> {
            val range = parseContentRange(connection.getHeaderField("Content-Range"))
            if (resume.offset == 0L || range == null || range.start != resume.offset) {
                null
            } else {
                receive(connection, resume.offset, append = true, serverTotal = range.total, validator = null)
            }
        }

        HTTP_RANGE_NOT_SATISFIABLE -> null

        else -> throw IOException("The server answered HTTP $status.")
    }

    private suspend fun receive(
        connection: HttpURLConnection,
        offset: Long,
        append: Boolean,
        serverTotal: Long?,
        validator: String?,
    ): Long {
        // What the server promises the whole file is, if it said so. Only this is compared with the
        // bytes received; the size in the version file is a display hint and the hash decides validity.
        val shownTotal = serverTotal ?: expectedSize
        if (serverTotal != null) requireSpace(serverTotal - offset)
        if (!append) {
            try {
                files.writeValidator(versionCode, validator)
            } catch (e: IOException) {
                throw StorageException("The download state cannot be saved.", e)
            }
        }
        val sink = Sink(files.part(versionCode), append)
        var written = offset
        try {
            val input = connection.inputStream
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read =
                    try {
                        input.read(buffer)
                    } catch (e: IOException) {
                        // The watcher closes the connection on cancellation, which surfaces here as an I/O error.
                        currentCoroutineContext().ensureActive()
                        throw e
                    }
                if (read < 0) break
                // Checked before the write: a worker that was stopped must not append another chunk.
                currentCoroutineContext().ensureActive()
                sink.write(buffer, read)
                written += read
                if (throttle.shouldSend(written, shownTotal)) onProgress(written, shownTotal)
            }
        } finally {
            sink.closeQuietly()
        }
        sink.close()
        if (serverTotal != null && written != serverTotal) {
            // Longer than promised: the partial file is wrong. Shorter: the connection ended early, and
            // what is on disk is the start of the right file, so it stays for the retry.
            if (written > serverTotal) files.deletePartial(versionCode)
            throw IOException("The connection ended after $written of $serverTotal bytes.")
        }
        return written
    }

    private fun requireSpace(needed: Long) {
        if (needed > 0 && freeSpace.availableBytes(context, files.directory) < needed) {
            throw StorageException("Not enough free space for $needed more bytes.")
        }
    }

    /** The value to send as `If-Range` for this response, or null when the server gave no strong validator. */
    private fun validatorOf(connection: HttpURLConnection): String? {
        val etag = connection.getHeaderField("ETag")?.trim()
        // A weak ETag must not be used in If-Range (RFC 9110, section 13.1.5).
        if (!etag.isNullOrEmpty() && !etag.startsWith("W/")) return etag
        return connection.getHeaderField("Last-Modified")?.trim()?.ifEmpty { null }
    }

    private fun parseContentRange(header: String?): ContentRange? {
        val match = header?.let { CONTENT_RANGE.matchEntire(it.trim()) } ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        return ContentRange(start, match.groupValues[3].toLongOrNull())
    }

    /** Writes to the partial file and turns every failure into a [StorageException]. */
    private class Sink(
        file: File,
        append: Boolean,
    ) {
        private val stream: FileOutputStream =
            try {
                FileOutputStream(file, append)
            } catch (e: IOException) {
                throw StorageException("The partial file cannot be opened.", e)
            }

        fun write(
            buffer: ByteArray,
            length: Int,
        ) {
            try {
                stream.write(buffer, 0, length)
            } catch (e: IOException) {
                throw StorageException("The partial file cannot be written.", e)
            }
        }

        fun close() {
            try {
                stream.close()
            } catch (e: IOException) {
                throw StorageException("The partial file cannot be saved.", e)
            }
        }

        fun closeQuietly() {
            try {
                stream.close()
            } catch (_: IOException) {
                // Only reached while another exception is already on its way; that one is the news.
            }
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val MAX_START_OVERS = 1
        const val BUFFER_SIZE = 64 * 1024
        const val TIMEOUT_MILLIS = 30_000
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        val REDIRECTS =
            setOf(
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                HTTP_TEMPORARY_REDIRECT,
                HTTP_PERMANENT_REDIRECT,
            )
        val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")
    }
}

private const val HTTP_TEMPORARY_REDIRECT = 307
private const val HTTP_PERMANENT_REDIRECT = 308
