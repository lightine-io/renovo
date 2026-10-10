package io.lightine.renovo.internal.work

import androidx.work.BackoffPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import io.lightine.renovo.FailureReason
import io.lightine.renovo.internal.FetchResult
import io.lightine.renovo.internal.VersionFetcher
import io.lightine.renovo.internal.engine.EngineEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

internal class WorkSchedulerTest : WorkTestBase() {
    private val scope = CoroutineScope(Job() + Dispatchers.Default)
    private val etags = mutableListOf<String?>()
    private var etag: String? = "\"e1\""
    private val fetches = AtomicInteger()
    private var gate: CompletableDeferred<FetchResult>? = null
    private var next: FetchResult = FetchResult.NotModified

    private val fetcher =
        object : VersionFetcher {
            override suspend fun fetch(etag: String?): FetchResult {
                fetches.incrementAndGet()
                etags.add(etag)
                return gate?.await() ?: next
            }
        }

    private lateinit var scheduler: WorkScheduler

    @Before
    fun createScheduler() {
        scheduler = WorkScheduler(context, fetcher, sink, { etag }, scope)
    }

    @After
    fun stopScope() {
        scope.cancel()
    }

    private fun checkWork(): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(CHECK_WORK_NAME).get()

    private fun waitForEvents(count: Int) = eventually(message = "Expected $count events, got ${sink.events}") { sink.events.size >= count }

    // ---- The periodic job ----------------------------------------------------------------------

    @Test
    fun `the periodic job needs a connection, repeats at the interval and backs off exponentially`() {
        scheduler.schedulePeriodicCheck(6.hours)

        val work = checkWork().single()
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
        assertEquals(6.hours.inWholeMilliseconds, work.periodicityInfo?.repeatIntervalMillis)
        assertEquals(WorkInfo.State.ENQUEUED, work.state)

        // WorkInfo does not expose the backoff, so the request that was enqueued is checked directly.
        // WorkSpec is marked library-internal by WorkManager but is the only place the policy is readable.
        val spec = WorkScheduler.periodicCheckRequest(6.hours).workSpec
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.SECONDS.toMillis(30), spec.backoffDelayDuration)
        assertEquals(6.hours.inWholeMilliseconds, spec.intervalDuration)
    }

    @Test
    fun `scheduling again with the same interval changes nothing`() {
        scheduler.schedulePeriodicCheck(6.hours)
        val before = checkWork().single()

        scheduler.schedulePeriodicCheck(6.hours)
        scheduler.schedulePeriodicCheck(6.hours)

        val after = checkWork().single()
        assertEquals(before.id, after.id)
        assertEquals(before.state, after.state)
        assertEquals(before.periodicityInfo, after.periodicityInfo)
        assertEquals(before.constraints, after.constraints)
        assertEquals("The next run is not pushed back", before.nextScheduleTimeMillis, after.nextScheduleTimeMillis)
    }

    @Test
    fun `a new interval replaces the job's interval without a cancel`() {
        scheduler.schedulePeriodicCheck(6.hours)
        val before = checkWork().single()

        scheduler.schedulePeriodicCheck(90.minutes)

        val after = checkWork().single()
        assertEquals("Same job, updated in place", before.id, after.id)
        assertEquals(90.minutes.inWholeMilliseconds, after.periodicityInfo?.repeatIntervalMillis)
        assertEquals(WorkInfo.State.ENQUEUED, after.state)
        assertEquals(1, checkWork().size)
    }

    // ---- The periodic worker -------------------------------------------------------------------

    private fun runWorker(attempt: Int): ListenableWorker.Result {
        val worker =
            TestListenableWorkerBuilder<CheckWorker>(context)
                .setRunAttemptCount(attempt)
                .build()
        return runBlocking { worker.doWork() }
    }

    @Test
    fun `the scheduler is the one the worker uses`() {
        assertTrue(WorkEnvironment.checkRunner != null)
    }

    @Test
    fun `the worker reports a found version with the engine's etag`() {
        val info = updateInfo()
        next = FetchResult.Found(info, "\"e2\"")

        assertEquals(ListenableWorker.Result.success(), runWorker(0))

        assertEquals(
            listOf(EngineEvent.CheckStarted(userInitiated = false), EngineEvent.CheckSucceeded(info, "\"e2\"")),
            sink.events,
        )
        assertEquals(listOf<String?>("\"e1\""), etags)
    }

    @Test
    fun `the worker reports not modified`() {
        next = FetchResult.NotModified

        assertEquals(ListenableWorker.Result.success(), runWorker(0))

        assertEquals(listOf(EngineEvent.CheckStarted(false), EngineEvent.CheckNotModified), sink.events)
    }

    @Test
    fun `the worker retries a network failure twice and reports the third`() {
        next = FetchResult.Failed(FailureReason.Network)

        assertEquals(ListenableWorker.Result.retry(), runWorker(0))
        assertEquals(ListenableWorker.Result.retry(), runWorker(1))
        assertTrue("Nothing is reported while retries remain", sink.events.isEmpty())

        assertEquals(ListenableWorker.Result.success(), runWorker(2))

        assertEquals(listOf(EngineEvent.CheckStarted(false), EngineEvent.CheckFailed(FailureReason.Network)), sink.events)
    }

    @Test
    fun `the worker does not retry a failure that a retry cannot fix`() {
        next = FetchResult.Failed(FailureReason.Parse)

        assertEquals(ListenableWorker.Result.success(), runWorker(0))

        assertEquals(listOf(EngineEvent.CheckStarted(false), EngineEvent.CheckFailed(FailureReason.Parse)), sink.events)
    }

    @Test
    fun `the worker does nothing before init`() {
        WorkEnvironment.checkRunner = null

        assertEquals(ListenableWorker.Result.success(), runWorker(0))

        assertTrue(sink.events.isEmpty())
        assertEquals(0, fetches.get())
    }

    // ---- checkNow ------------------------------------------------------------------------------

    @Test
    fun `checkNow sends started and then the outcome`() {
        val info = updateInfo()
        next = FetchResult.Found(info, "\"e2\"")

        scheduler.checkNow(userInitiated = true)
        waitForEvents(2)

        assertEquals(listOf(EngineEvent.CheckStarted(true), EngineEvent.CheckSucceeded(info, "\"e2\"")), sink.events)
    }

    @Test
    fun `checkNow reports not modified`() {
        next = FetchResult.NotModified

        scheduler.checkNow(userInitiated = false)
        waitForEvents(2)

        assertEquals(listOf(EngineEvent.CheckStarted(false), EngineEvent.CheckNotModified), sink.events)
    }

    @Test
    fun `checkNow reports a failure`() {
        next = FetchResult.Failed(FailureReason.Parse)

        scheduler.checkNow(userInitiated = true)
        waitForEvents(2)

        assertEquals(listOf(EngineEvent.CheckStarted(true), EngineEvent.CheckFailed(FailureReason.Parse)), sink.events)
    }

    @Test
    fun `a fetcher that throws still ends the check`() {
        val broken =
            object : VersionFetcher {
                override suspend fun fetch(etag: String?): FetchResult = throw IllegalStateException("bug")
            }
        WorkScheduler(context, broken, sink, { null }, scope).checkNow(userInitiated = true)
        waitForEvents(2)

        assertEquals(listOf(EngineEvent.CheckStarted(true), EngineEvent.CheckFailed(FailureReason.Parse)), sink.events)
    }

    @Test
    fun `overlapping checks share one request`() {
        val result = CompletableDeferred<FetchResult>()
        gate = result

        scheduler.checkNow(userInitiated = true)
        eventually { fetches.get() == 1 }
        scheduler.checkNow(userInitiated = true)
        scheduler.checkNow(userInitiated = true)
        result.complete(FetchResult.NotModified)
        waitForEvents(2)

        assertEquals(1, fetches.get())
        assertEquals(listOf(EngineEvent.CheckStarted(true), EngineEvent.CheckNotModified), sink.events)
    }

    @Test
    fun `a user check that joins a background check is announced to the engine`() {
        val result = CompletableDeferred<FetchResult>()
        gate = result

        scheduler.checkNow(userInitiated = false)
        eventually { fetches.get() == 1 }
        scheduler.checkNow(userInitiated = true)
        result.complete(FetchResult.Failed(FailureReason.Network))
        waitForEvents(3)

        assertEquals(1, fetches.get())
        assertEquals(
            listOf(
                EngineEvent.CheckStarted(false),
                EngineEvent.CheckStarted(true),
                EngineEvent.CheckFailed(FailureReason.Network),
            ),
            sink.events,
        )
    }

    @Test
    fun `a check after the first one finished starts a new request`() {
        scheduler.checkNow(userInitiated = true)
        waitForEvents(2)
        // The flag is cleared just after the outcome is sent, so the second call may need a moment.
        eventually {
            scheduler.checkNow(true)
            fetches.get() >= 2
        }
    }

    @Test
    fun `the request carries the etag at the time of the request`() {
        scheduler.checkNow(userInitiated = true)
        waitForEvents(2)
        etag = "\"e9\""
        sink.clear()
        scheduler.checkNow(userInitiated = true)
        waitForEvents(2)

        assertEquals(listOf<String?>("\"e1\"", "\"e9\""), etags)
    }

    @Test
    fun `building a second scheduler makes it the one the worker uses`() {
        val first = WorkEnvironment.checkRunner
        WorkScheduler(context, fetcher, sink, { null }, scope)

        assertTrue(first !== WorkEnvironment.checkRunner)
    }
}
