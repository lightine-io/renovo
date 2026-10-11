package io.lightine.renovo.internal.work

import android.app.NotificationManager
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.lightine.renovo.FailureReason
import io.lightine.renovo.Network
import io.lightine.renovo.internal.engine.EngineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

internal class WorkDownloaderTest : WorkTestBase() {
    private val scope = CoroutineScope(Job() + Dispatchers.Default)
    private val tracker = FakeForegroundTracker(isInForeground = true)
    private val files by lazy { DownloadFiles(context) }
    private lateinit var downloader: WorkDownloader

    @Before
    fun createDownloader() {
        // Online on an unmetered, non-roaming network, unless a test says otherwise.
        setNetwork(metered = false, roaming = false)
        downloader = WorkDownloader(context, sink, tracker, scope)
    }

    @After
    fun stopScope() {
        scope.cancel()
    }

    private fun downloadWork(): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(DOWNLOAD_WORK_NAME).get()

    private fun activeWork(): WorkInfo = downloadWork().single { !it.state.isFinished }

    private fun enqueue(
        versionCode: Long = 11,
        network: Network = Network.Any,
        userInitiated: Boolean = false,
    ) {
        downloader.enqueue(updateInfo(versionCode, size = 1000), network, userInitiated)
        runBlocking { downloader.awaitIdle() }
    }

    @Test
    fun `each network setting becomes its constraint`() {
        val expected =
            mapOf(
                Network.Any to NetworkType.CONNECTED,
                Network.UnmeteredOnly to NetworkType.UNMETERED,
                Network.NotRoaming to NetworkType.NOT_ROAMING,
            )
        for ((network, type) in expected) {
            enqueue(network = network)

            assertEquals(network.name, type, activeWork().constraints.requiredNetworkType)
        }
    }

    @Test
    fun `the work is unique, tagged with its version and starts when constraints are met`() {
        enqueue()

        val work = activeWork()
        assertEquals(WorkInfo.State.ENQUEUED, work.state)
        assertTrue(versionTag(11) in work.tags)
        assertEquals(11L, versionCodeOf(work.tags))
        assertEquals("renovo-download", DOWNLOAD_WORK_NAME)
    }

    @Test
    fun `the same version keeps its work id when the constraint changes`() {
        enqueue(network = Network.UnmeteredOnly)
        val before = activeWork()

        enqueue(network = Network.Any, userInitiated = true)

        val after = activeWork()
        assertEquals("updateWork, not cancel and enqueue", before.id, after.id)
        assertEquals(NetworkType.CONNECTED, after.constraints.requiredNetworkType)
        assertEquals("One work, no cancelled leftovers", 1, downloadWork().size)
        assertEquals(WorkInfo.State.ENQUEUED, after.state)
    }

    @Test
    fun `a different version replaces the work`() {
        enqueue(versionCode = 11)
        val old = activeWork()

        enqueue(versionCode = 12)

        val new = activeWork()
        assertNotEquals(old.id, new.id)
        assertEquals(12L, versionCodeOf(new.tags))
        val oldNow = downloadWork().firstOrNull { it.id == old.id }
        assertTrue("The old work is cancelled or gone", oldNow == null || oldNow.state == WorkInfo.State.CANCELLED)
    }

    @Test
    fun `a finished work of the same version is replaced by a new one`() {
        // A runtime is connected and the disk is full, so the worker fails for good and the work is finished.
        WorkEnvironment.runtimeProvider = { FakeRuntime(sink) }
        WorkEnvironment.freeSpace = FreeSpace { _, _ -> 0L }
        enqueue()
        val first = activeWork()
        WorkManagerTestInitHelper.getTestDriver(context)?.setAllConstraintsMet(first.id)
        eventually { downloadWork().all { it.state.isFinished } }

        enqueue()

        assertNotEquals(first.id, activeWork().id)
    }

    @Test
    fun `a work that started before init is not finished, it waits for a retry`() {
        // No runtime is connected: Renovo.init did not run in this process yet.
        enqueue()
        val first = activeWork()
        WorkManagerTestInitHelper.getTestDriver(context)?.setAllConstraintsMet(first.id)
        Thread.sleep(300)

        // The worker answered "retry", so the same work is still waiting, not failed.
        val work = activeWork()
        assertEquals(first.id, work.id)
        assertEquals(WorkInfo.State.ENQUEUED, work.state)
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `cancel stops the work and deletes the partial file`() {
        enqueue()
        files.ensureDirectory()
        files.part(11).writeBytes(ByteArray(100))
        files.writeValidator(11, "\"v1\"")
        val done = files.apk(10).also { it.writeBytes(ByteArray(5)) }

        downloader.cancel()
        runBlocking { downloader.awaitIdle() }

        assertFalse(files.part(11).exists())
        assertEquals(null, files.readValidator(11))
        assertTrue(downloadWork().none { !it.state.isFinished })
        assertTrue("Complete files are not the partial file's business", done.exists())
    }

    @Test
    fun `a cancel from the engine reports nothing, a cancel from outside reports Cancelled`() {
        enqueue()
        downloader.cancel()
        runBlocking { downloader.awaitIdle() }
        Thread.sleep(300)
        assertTrue(sink.events.isEmpty())

        enqueue()
        WorkManager.getInstance(context).cancelUniqueWork(DOWNLOAD_WORK_NAME).result.get()

        eventually { sink.events.isNotEmpty() }
        assertEquals(listOf(EngineEvent.DownloadFailed(11, FailureReason.Cancelled)), sink.events)
    }

    @Test
    fun `replacing the work for a new version reports nothing about the old one`() {
        enqueue(versionCode = 11)
        enqueue(versionCode = 12)
        Thread.sleep(300)

        assertTrue(sink.events.isEmpty())
    }

    /** Runs the queued work for real and tells whether it went foreground, which is when it creates its channel. */
    private fun wentForeground(
        userInitiated: Boolean,
        inForeground: Boolean,
    ): Boolean {
        val body = ByteArray(1000)
        val server = FakeServer { FileServerBehavior(body).respond(it) }
        WorkEnvironment.connectionFactory = server.factory
        WorkEnvironment.runtimeProvider = { FakeRuntime(sink) }
        tracker.isInForeground = inForeground
        enqueue(userInitiated = userInitiated)
        val id = activeWork().id

        WorkManagerTestInitHelper.getTestDriver(context)?.setAllConstraintsMet(id)
        eventually { downloadWork().all { it.state.isFinished } }

        assertEquals(WorkInfo.State.SUCCEEDED, downloadWork().single().state)
        return context.getSystemService(NotificationManager::class.java).getNotificationChannel(DownloadNotification.CHANNEL_ID) != null
    }

    @Test
    fun `a download the user started in the foreground runs as a foreground worker`() {
        assertTrue(wentForeground(userInitiated = true, inForeground = true))
    }

    @Test
    fun `a download the user started that is now in the background is a plain worker`() {
        assertFalse(wentForeground(userInitiated = true, inForeground = false))
    }

    @Test
    fun `a download from a background check is a plain worker`() {
        assertFalse(wentForeground(userInitiated = false, inForeground = true))
    }

    // ---- Waiting for a network -----------------------------------------------------------------

    @Test
    fun `queued work that the network cannot satisfy is reported as waiting`() {
        setNetwork(metered = true)

        enqueue(network = Network.UnmeteredOnly)

        eventually { sink.events.isNotEmpty() }
        assertEquals(listOf(EngineEvent.DownloadWaitingForNetwork(11)), sink.events)
    }

    @Test
    fun `no network at all is waiting too`() {
        setNetwork(metered = false, online = false)

        enqueue(network = Network.Any)

        eventually { sink.events.isNotEmpty() }
        assertEquals(listOf(EngineEvent.DownloadWaitingForNetwork(11)), sink.events)
    }

    @Test
    fun `a network that satisfies the constraint is not waiting`() {
        setNetwork(metered = true)

        enqueue(network = Network.Any)
        Thread.sleep(300)

        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `roaming blocks a not-roaming download`() {
        setNetwork(metered = true, roaming = true)

        enqueue(network = Network.NotRoaming)

        eventually { sink.events.isNotEmpty() }
        assertEquals(listOf(EngineEvent.DownloadWaitingForNetwork(11)), sink.events)
    }

    @Test
    fun `choosing any network ends the wait without another event`() {
        setNetwork(metered = true)
        enqueue(network = Network.UnmeteredOnly)
        eventually { sink.events.size == 1 }

        enqueue(network = Network.Any, userInitiated = true)
        Thread.sleep(300)

        assertEquals(1, sink.events.size)
    }

    @Test
    fun `the wait is reported again when the work returns to queued after a run`() {
        setNetwork(metered = true)
        // A connection that fails makes the worker ask for a retry, so the work goes RUNNING and back to ENQUEUED.
        WorkEnvironment.runtimeProvider = { FakeRuntime(sink) }
        WorkEnvironment.connectionFactory = ConnectionFactory { throw IOException("offline") }
        enqueue(network = Network.UnmeteredOnly)
        eventually { sink.events.size == 1 }
        val id = activeWork().id

        WorkManagerTestInitHelper.getTestDriver(context)?.setAllConstraintsMet(id)

        eventually(message = "Expected a second wait, got ${sink.events}") {
            sink.events.count { it is EngineEvent.DownloadWaitingForNetwork } == 2
        }
    }
}
