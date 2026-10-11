package io.lightine.renovo.internal.work

import android.app.NotificationManager
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.lightine.renovo.DownloadProgress
import io.lightine.renovo.FailureReason
import io.lightine.renovo.internal.engine.EngineEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.Random

internal class DownloadWorkerTest : WorkTestBase() {
    private val body = ByteArray(200_000).also { Random(7).nextBytes(it) }
    private val files by lazy { DownloadFiles(context) }

    @Before
    fun connectRuntime() {
        WorkEnvironment.runtimeProvider = { FakeRuntime(sink) }
    }

    private fun run(
        input: DownloadInput,
        attempt: Int = 0,
    ): ListenableWorker.Result {
        val worker =
            TestListenableWorkerBuilder<DownloadWorker>(context)
                .setInputData(input.toData())
                .setRunAttemptCount(attempt)
                .build()
        return runBlocking { worker.doWork() }
    }

    private fun input(
        versionCode: Long = 11,
        size: Long? = body.size.toLong(),
        foreground: Boolean = false,
        url: String = "https://example.com/app-$versionCode.apk",
    ) = DownloadInput(versionCode, url, size, foreground)

    private fun serve(
        behavior: FileServerBehavior = FileServerBehavior(body),
        cutOnFirst: Int? = null,
        chunk: Int = Int.MAX_VALUE,
    ): FakeServer {
        lateinit var server: FakeServer
        server =
            FakeServer { request ->
                behavior.respond(request, cutAfter = cutOnFirst.takeIf { server.requests.size == 1 }, chunk = chunk)
            }
        WorkEnvironment.connectionFactory = server.factory
        return server
    }

    private fun writePartial(
        versionCode: Long,
        bytes: ByteArray,
        validator: String? = "\"v1\"",
    ) {
        files.ensureDirectory()
        files.part(versionCode).writeBytes(bytes)
        files.writeValidator(versionCode, validator)
    }

    @Test
    fun `downloads the whole file, renames it and reports finished`() {
        val server = serve()

        val result = run(input())

        assertEquals(ListenableWorker.Result.success(), result)
        val file = files.apk(11)
        assertArrayEquals(body, file.readBytes())
        assertFalse(files.part(11).exists())
        assertEquals(1, server.requests.size)
        assertNull("A plain first request has no Range", server.requests.single().headers["range"])
        assertEquals("identity", server.requests.single().headers["accept-encoding"])
        assertEquals(EngineEvent.DownloadFinished(11, file.path), sink.events.last())
        val last = sink.all<EngineEvent.DownloadProgressed>().last()
        assertEquals(DownloadProgress(body.size.toLong(), body.size.toLong()), last.progress)
        assertTrue(sink.events.none { it is EngineEvent.DownloadFailed })
    }

    @Test
    fun `an interrupted download keeps its partial file and resumes with a 206`() {
        val half = body.size / 2
        val server = serve(cutOnFirst = half)

        val first = run(input())

        assertEquals(ListenableWorker.Result.retry(), first)
        assertEquals(half.toLong(), files.part(11).length())
        assertEquals("\"v1\"", files.readValidator(11))
        assertTrue("An interruption is not a failure", sink.events.none { it is EngineEvent.DownloadFailed })

        val second = run(input(), attempt = 1)

        assertEquals(ListenableWorker.Result.success(), second)
        assertArrayEquals(body, files.apk(11).readBytes())
        val resume = server.requests[1]
        assertEquals("bytes=$half-", resume.headers["range"])
        assertEquals("\"v1\"", resume.headers["if-range"])
        assertEquals(EngineEvent.DownloadFinished(11, files.apk(11).path), sink.events.last())
    }

    @Test
    fun `a size in the version file that is too small does not throw away the bytes already on disk`() {
        // The publisher rebuilt the APK and forgot to update "size". The hash still decides validity.
        val wrongSize = (body.size / 4).toLong()
        val cut = body.size / 2
        val server = serve(cutOnFirst = cut)

        assertEquals(ListenableWorker.Result.retry(), run(input(size = wrongSize)))
        assertEquals(cut.toLong(), files.part(11).length())

        val second = run(input(size = wrongSize), attempt = 1)

        assertEquals(ListenableWorker.Result.success(), second)
        assertArrayEquals(body, files.apk(11).readBytes())
        assertEquals("FR-7: the second run resumes", "bytes=$cut-", server.requests[1].headers["range"])
    }

    @Test
    fun `an empty file on the server is finished and left to the verifier`() {
        // A release uploaded as a 0-byte file. The engine must hear an outcome; the verifier rejects the file.
        serve(FileServerBehavior(ByteArray(0)))

        val result = run(input(size = null))

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0L, files.apk(11).length())
        assertEquals(EngineEvent.DownloadFinished(11, files.apk(11).path), sink.events.last())
    }

    @Test
    fun `a 200 to a range request starts over`() {
        // The file on the server changed: its ETag differs, so If-Range fails and the server sends all of it.
        writePartial(11, ByteArray(1000) { 9 }, validator = "\"old\"")
        val server = serve(FileServerBehavior(body, etag = "\"new\""))

        val result = run(input())

        assertEquals(ListenableWorker.Result.success(), result)
        assertArrayEquals(body, files.apk(11).readBytes())
        assertEquals(1, server.requests.size)
        assertEquals("bytes=1000-", server.requests.single().headers["range"])
    }

    @Test
    fun `a 416 deletes the partial file and downloads from the start`() {
        writePartial(11, ByteArray(1000) { 9 })
        val calls = mutableListOf<RecordedRequest>()
        val behavior = FileServerBehavior(body)
        val server =
            FakeServer { request ->
                calls.add(request)
                if (calls.size == 1) FakeResponse(416) else behavior.respond(request)
            }
        WorkEnvironment.connectionFactory = server.factory

        val result = run(input())

        assertEquals(ListenableWorker.Result.success(), result)
        assertArrayEquals(body, files.apk(11).readBytes())
        assertEquals(2, calls.size)
        assertNull(calls[1].headers["range"])
    }

    @Test
    fun `a partial file without a validator is not resumed`() {
        writePartial(11, ByteArray(1000) { 9 }, validator = null)
        val server = serve()

        val result = run(input())

        assertEquals(ListenableWorker.Result.success(), result)
        assertArrayEquals(body, files.apk(11).readBytes())
        assertNull(server.requests.single().headers["range"])
    }

    @Test
    fun `a weak ETag is never used for If-Range, the date is`() {
        val server =
            FakeServer {
                FakeResponse(
                    200,
                    mapOf("ETag" to "W/\"x\"", "Last-Modified" to "Wed, 21 Oct 2015 07:28:00 GMT", "Content-Length" to "${body.size}"),
                    body,
                    cutAfter = 10,
                )
            }
        WorkEnvironment.connectionFactory = server.factory

        run(input())

        assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", files.readValidator(11))
    }

    @Test
    fun `not enough free space reports Storage without a request`() {
        val server = serve()
        WorkEnvironment.freeSpace = FreeSpace { _, _ -> 1_000 }

        val result = run(input())

        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(EngineEvent.DownloadFailed(11, FailureReason.Storage), sink.events.last())
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `only the remaining bytes need room`() {
        writePartial(11, body.copyOfRange(0, 150_000))
        serve()
        WorkEnvironment.freeSpace = FreeSpace { _, _ -> 50_000 }

        val result = run(input())

        assertEquals(ListenableWorker.Result.success(), result)
        assertArrayEquals(body, files.apk(11).readBytes())
    }

    @Test
    fun `a size that only the server knows is checked when the response arrives`() {
        val server = serve()
        WorkEnvironment.freeSpace = FreeSpace { _, _ -> 1_000 }

        val result = run(input(size = null))

        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(EngineEvent.DownloadFailed(11, FailureReason.Storage), sink.events.last())
        assertEquals(1, server.requests.size)
        assertFalse(files.apk(11).exists())
    }

    @Test
    fun `a network error is retried until the sixth run, then reported`() {
        WorkEnvironment.connectionFactory = ConnectionFactory { throw IOException("Unable to resolve host") }

        for (attempt in 0 until DownloadWorker.MAX_RETRIES) {
            assertEquals("attempt $attempt", ListenableWorker.Result.retry(), run(input(), attempt))
        }
        assertTrue("No failure while retries remain", sink.events.none { it is EngineEvent.DownloadFailed })

        val last = run(input(), DownloadWorker.MAX_RETRIES)

        assertEquals(ListenableWorker.Result.failure(), last)
        assertEquals(EngineEvent.DownloadFailed(11, FailureReason.Network), sink.events.last())
    }

    @Test
    fun `the partial file survives the final network failure`() {
        serve(cutOnFirst = 1000)
        run(input(), DownloadWorker.MAX_RETRIES)

        assertEquals(1000L, files.part(11).length())
        assertEquals(EngineEvent.DownloadFailed(11, FailureReason.Network), sink.events.last())
    }

    @Test
    fun `an HTTP error status is retried like a network error`() {
        WorkEnvironment.connectionFactory = FakeServer { FakeResponse(503) }.factory

        assertEquals(ListenableWorker.Result.retry(), run(input()))
    }

    @Test
    fun `a connection that ends early is not taken for a finished file`() {
        // The server promises the full length, then closes the connection without an error.
        val server =
            FakeServer {
                FakeResponse(200, mapOf("Content-Length" to "${body.size}", "ETag" to "\"v1\""), body.copyOfRange(0, 500))
            }
        WorkEnvironment.connectionFactory = server.factory

        val result = run(input())

        assertEquals(ListenableWorker.Result.retry(), result)
        assertFalse(files.apk(11).exists())
        assertEquals(500L, files.part(11).length())
    }

    @Test
    fun `a file that cannot be written is reported as Storage`() {
        serve()
        // A directory where the partial file belongs makes opening it for writing fail.
        files.ensureDirectory()
        files.part(11).mkdirs()

        val result = run(input())

        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(EngineEvent.DownloadFailed(11, FailureReason.Storage), sink.events.last())
    }

    @Test
    fun `progress is throttled by time and by percent`() {
        val clock = ManualClock()
        WorkEnvironment.clock = clock
        val total = 100_000L
        val data = ByteArray(total.toInt())
        // 100 reads of 1000 bytes, 100 ms apart: a report is allowed every 500 ms, and each 500 ms is 5 percent.
        val behavior = FileServerBehavior(data)
        val sentAt = mutableListOf<Long>()
        val server = FakeServer { behavior.respond(it, chunk = 1000, onRead = { clock.now += 100 }) }
        WorkEnvironment.connectionFactory = server.factory
        WorkEnvironment.runtimeProvider = {
            FakeRuntime(
                { event ->
                    if (event is EngineEvent.DownloadProgressed) sentAt.add(clock.now)
                    sink.send(event)
                },
            )
        }

        run(input(size = total))

        val progress = sink.all<EngineEvent.DownloadProgressed>().map { it.progress }
        assertTrue("fewer reports than reads: ${progress.size}", progress.size in 15..30)
        assertEquals(DownloadProgress(total, total), progress.last())
        val gaps = sentAt.zipWithNext { a, b -> b - a }
        // The final report comes with the last read and may be closer than 500 ms to the one before.
        assertTrue("reports are at least 500 ms apart: $gaps", gaps.dropLast(1).all { it >= 500 })
        val percents = progress.map { it.bytesDownloaded * 100 / total }
        assertTrue("percent moves by at least 1: $percents", percents.dropLast(1).zipWithNext { a, b -> b - a }.all { it >= 1 })
    }

    @Test
    fun `many fast reads inside one percent send one report`() {
        val clock = ManualClock()
        WorkEnvironment.clock = clock
        val total = 1_000_000L
        val behavior = FileServerBehavior(ByteArray(total.toInt()))
        // 10 000 reads of 100 bytes, 600 ms apart: time is never the limit, so only the percent steps count.
        val server = FakeServer { behavior.respond(it, chunk = 100, onRead = { clock.now += 600 }) }
        WorkEnvironment.connectionFactory = server.factory
        val result = run(input(size = total))

        assertEquals(ListenableWorker.Result.success(), result)
        val progress = sink.all<EngineEvent.DownloadProgressed>()
        // 100 percent steps plus the final report, not 10 000.
        assertTrue("reports: ${progress.size}", progress.size in 90..102)
    }

    @Test
    fun `files of other versions are deleted, and unrelated files are not`() {
        files.ensureDirectory()
        val old = listOf("9.apk", "9.apk.part", "9.apk.part.validator", "12.apk.part")
        old.forEach { File(files.directory, it).writeText("x") }
        val unrelated = File(files.directory, "state.json").also { it.writeText("{}") }
        serve()

        run(input())

        old.forEach { assertFalse("$it should be gone", File(files.directory, it).exists()) }
        assertTrue(unrelated.exists())
        assertTrue(files.apk(11).exists())
    }

    @Test
    fun `a finished file of the same version is reported without a request`() {
        files.ensureDirectory()
        files.apk(11).writeBytes(body)
        val server = serve()

        val result = run(input())

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(server.requests.isEmpty())
        assertEquals(EngineEvent.DownloadFinished(11, files.apk(11).path), sink.events.last())
    }

    @Test
    fun `a finished file of the wrong size is replaced`() {
        files.ensureDirectory()
        files.apk(11).writeBytes(ByteArray(10))
        serve()

        run(input())

        assertArrayEquals(body, files.apk(11).readBytes())
    }

    @Test
    fun `a complete partial file is finished without a request`() {
        // The process died between the last byte and the rename.
        writePartial(11, body)
        val server = serve()

        val result = run(input())

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(server.requests.isEmpty())
        assertArrayEquals(body, files.apk(11).readBytes())
    }

    @Test
    fun `an http address is refused without a request`() {
        val server = serve()

        val result = run(input(url = "http://example.com/app.apk"))

        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(EngineEvent.DownloadFailed(11, FailureReason.Network), sink.events.last())
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `redirects on https are followed`() {
        val behavior = FileServerBehavior(body)
        val server =
            FakeServer { request ->
                when (request.url.path) {
                    "/app-11.apk" -> FakeResponse(302, mapOf("Location" to "https://cdn.example.com/a"))
                    "/a" -> FakeResponse(301, mapOf("Location" to "/b"))
                    else -> behavior.respond(request)
                }
            }
        WorkEnvironment.connectionFactory = server.factory

        val result = run(input())

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf("example.com", "cdn.example.com", "cdn.example.com"), server.requests.map { it.url.host })
        assertArrayEquals(body, files.apk(11).readBytes())
    }

    @Test
    fun `a redirect to http is refused for good`() {
        val server = FakeServer { FakeResponse(302, mapOf("Location" to "http://example.com/app.apk")) }
        WorkEnvironment.connectionFactory = server.factory

        val result = run(input())

        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(EngineEvent.DownloadFailed(11, FailureReason.Network), sink.events.last())
        assertEquals("Only the first request was made", 1, server.requests.size)
    }

    @Test
    fun `at most five redirects are followed`() {
        var hops = 0
        val behavior = FileServerBehavior(body)
        val server =
            FakeServer { request ->
                if (hops < 5) {
                    hops++
                    FakeResponse(302, mapOf("Location" to "https://example.com/hop$hops"))
                } else {
                    behavior.respond(request)
                }
            }
        WorkEnvironment.connectionFactory = server.factory
        assertEquals("five redirects are fine", ListenableWorker.Result.success(), run(input()))

        val endless = FakeServer { FakeResponse(302, mapOf("Location" to "https://example.com/again")) }
        WorkEnvironment.connectionFactory = endless.factory
        sink.clear()
        files.apk(11).delete()

        assertEquals(ListenableWorker.Result.failure(), run(input()))
        assertEquals(6, endless.requests.size)
        assertEquals(EngineEvent.DownloadFailed(11, FailureReason.Network), sink.events.last())
    }

    @Test
    fun `a worker that is stopped keeps the partial file and reports nothing`() {
        val half = body.size / 2
        val behavior = FileServerBehavior(body)
        val reading = java.util.concurrent.CountDownLatch(1)
        val server =
            FakeServer { request ->
                val response = behavior.respond(request)
                FakeResponse(response.status, response.headers, response.body, chunk = 1000, blockAfter = half, onRead = { reading.countDown() })
            }
        WorkEnvironment.connectionFactory = server.factory
        val worker =
            TestListenableWorkerBuilder<DownloadWorker>(context)
                .setInputData(input().toData())
                .build()

        runBlocking {
            val job = async(Dispatchers.Default, start = CoroutineStart.DEFAULT) { worker.doWork() }
            eventually { files.part(11).length() >= half }
            job.cancelAndJoin()
        }

        assertEquals(half.toLong(), files.part(11).length())
        assertTrue("Stopping is not a failure", sink.events.none { it is EngineEvent.DownloadFailed || it is EngineEvent.DownloadFinished })
        assertTrue("The connection was closed", server.connections.all { it.disconnected })
    }

    @Test
    fun `a foreground download creates the channel and still downloads`() {
        serve()

        val result = run(input(foreground = true))

        assertEquals(ListenableWorker.Result.success(), result)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(manager.getNotificationChannel(DownloadNotification.CHANNEL_ID))
        assertArrayEquals(body, files.apk(11).readBytes())
    }

    @Test
    fun `the worker does nothing before init`() {
        WorkEnvironment.runtimeProvider = { null }
        val server = serve()

        val result = run(input())

        assertEquals(ListenableWorker.Result.failure(), result)
        assertTrue(server.requests.isEmpty())
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `the request is read back from the input data`() {
        val original = input(size = 1234, foreground = true)
        assertEquals(original, DownloadInput.from(original.toData()))
        assertNull(DownloadInput.from(androidx.work.Data.EMPTY))
        assertEquals(null, DownloadInput.from(input(size = null).toData())?.size)
    }

    @Test
    fun `a rejected foreground start does not stop the download`() {
        val notification = DownloadNotification(context, java.util.UUID.randomUUID())
        var calls = 0
        val foreground =
            ForegroundProgress(notification) {
                calls++
                throw IllegalStateException("Not allowed to start service Intent from the background")
            }

        runBlocking {
            foreground.start(0, 100)
            foreground.update(50, 100)
            ForegroundProgress(notification) { throw SecurityException("Missing permission") }.update(1, 100)
        }

        assertEquals("After one refusal it does not ask again", 1, calls)
    }

    @Test
    fun `a foreground notification shows the sizes and a cancel action`() {
        val notification = DownloadNotification(context, java.util.UUID.randomUUID())

        val info = notification.foregroundInfo(1_048_576, 10_485_760)

        assertEquals(DownloadNotification.NOTIFICATION_ID, info.notificationId)
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        val built = info.notification
        assertEquals(DownloadNotification.CHANNEL_ID, built.channelId)
        assertEquals(1, built.actions.size)
        assertEquals(context.getString(android.R.string.cancel), built.actions[0].title.toString())
        val text = built.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertEquals(
            context.getString(
                io.lightine.renovo.R.string.renovo_download_notification_progress,
                android.text.format.Formatter.formatShortFileSize(context, 1_048_576),
                android.text.format.Formatter.formatShortFileSize(context, 10_485_760),
            ),
            text,
        )
        assertEquals(10_240, built.extras.getInt(android.app.Notification.EXTRA_PROGRESS_MAX))
        assertEquals(1_024, built.extras.getInt(android.app.Notification.EXTRA_PROGRESS))
    }
}
