package io.lightine.renovo.internal.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.lightine.renovo.RenovoConfig
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateState
import io.lightine.renovo.internal.FetchResult
import io.lightine.renovo.internal.engine.EngineEvent
import io.lightine.renovo.internal.engine.Store
import io.lightine.renovo.internal.engine.UpdateEngine
import io.lightine.renovo.internal.store.PreferencesStore
import io.lightine.renovo.internal.work.CheckRunner
import io.lightine.renovo.internal.work.TEST_SDK
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One real engine, one real store, the real [CheckRunner], and fakes for everything else, driven by
 * a test dispatcher so every step is deterministic. A test calls [settle] to let the loop catch up.
 *
 * Several harnesses on one context share the store and the download folder, which is how a test
 * simulates a process that was killed and started again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RuntimeHarness(
    private val context: Context,
    installed: Long = INSTALLED_VERSION,
    inForeground: Boolean = true,
    wrapStore: (Store) -> Store = { it },
    realThreads: Boolean = false,
    configure: RenovoConfig.Builder.() -> Unit = {},
) {
    val logs = CopyOnWriteArrayList<String>()
    val logErrors = CopyOnWriteArrayList<Throwable>()

    /** Exceptions that no code of the runtime caught. A crash in the host app, were this production. */
    val uncaught = CopyOnWriteArrayList<Throwable>()

    private val testDispatcher = StandardTestDispatcher()

    // With real threads the loop runs on Dispatchers.Default and the main thread is the Robolectric
    // main looper, as in production. Otherwise one test dispatcher runs everything, step by step.
    private val dispatcher: CoroutineDispatcher = if (realThreads) Dispatchers.Default else testDispatcher
    private val mainDispatcher: CoroutineDispatcher = if (realThreads) Dispatchers.Main.immediate else testDispatcher
    val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, error -> uncaught += error })

    val config: RenovoConfig =
        RenovoConfig
            .Builder()
            .apply {
                versionUrl = "https://example.com/version.json"
                logger =
                    RenovoLogger { message, error ->
                        logs += message
                        if (error != null) logErrors += error
                    }
                configure()
            }.build()

    val engine = UpdateEngine(config.engineSettings())
    val queue = EventQueue()
    val downloadDirectory = File(context.filesDir, "renovo")

    /** Set before [start] to make reading the installed version code fail. */
    @Volatile
    var installedFailure: RuntimeException? = null

    val store: Store = wrapStore(PreferencesStore(context, null))
    val fetcher = FakeVersionFetcher()
    val scheduler = FakeScheduler(CheckRunner(fetcher, queue, { engine.currentSnapshot().etag }, scope, dispatcher, null))
    val downloader = FakeDownloader(queue, downloadDirectory)
    val verifier = FakeVerifier()
    val installer = FakeInstaller()
    val gate = FakePermissionGate()
    val notifier = FakeNotifier()
    val foreground = FakeForeground(inForeground)

    val components =
        Components(
            store = store,
            foreground = foreground,
            notifier = notifier,
            scheduler = scheduler,
            downloader = downloader,
            verifier = verifier,
            installer = installer,
            permissionGate = gate,
            installedVersionCode = {
                installedFailure?.let { throw it }
                installed
            },
        )

    val runtime = DefaultRuntime(config, components, scope, mainDispatcher, engine, queue)

    /** Every state the runtime showed, in order. An unconfined collector sees each value as it is set. */
    val states = CopyOnWriteArrayList<UpdateState>()

    init {
        CoroutineScope(UnconfinedTestDispatcher(testDispatcher.scheduler)).launch { runtime.state.collect { states += it } }
    }

    val state: UpdateState
        get() = runtime.state.value

    val stateNames: List<String?>
        get() = states.map { it::class.simpleName }

    /** Lets the loop, the verification, and the main-thread work run until nothing is left. */
    fun settle() = testDispatcher.scheduler.advanceUntilIdle()

    fun start() {
        runtime.start()
        settle()
    }

    /** What a component does on its own thread: report an event. */
    fun send(event: EngineEvent) {
        queue.send(event)
        settle()
    }

    /** Starts with [info] as the answer of the check, then downloads and verifies it, as far as the settings allow. */
    fun startWithUpdate(info: UpdateInfo = testInfo()) {
        fetcher.result = FetchResult.Found(info, etag = "etag-${info.versionCode}")
        start()
    }

    /** Finishes the download of the update that was found; the verifier passes. */
    fun finishDownload(versionCode: Long = 11): File {
        val file = downloader.finish(versionCode)
        settle()
        return file
    }

    fun close() {
        scope.cancel()
    }
}

/** Runs each test on Robolectric and cleans the shared files after it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [TEST_SDK])
internal abstract class RuntimeTestBase {
    protected val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val harnesses = mutableListOf<RuntimeHarness>()

    protected fun harness(
        installed: Long = INSTALLED_VERSION,
        inForeground: Boolean = true,
        wrapStore: (Store) -> Store = { it },
        realThreads: Boolean = false,
        configure: RenovoConfig.Builder.() -> Unit = {},
    ): RuntimeHarness = RuntimeHarness(context, installed, inForeground, wrapStore, realThreads, configure).also { harnesses += it }

    @After
    fun closeHarnesses() {
        harnesses.forEach { it.close() }
        File(context.filesDir, "renovo").deleteRecursively()
    }
}
