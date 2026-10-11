package io.lightine.renovo.internal.runtime

import io.lightine.renovo.RenovoConfig
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateState
import io.lightine.renovo.VersionSource
import io.lightine.renovo.internal.SafeLogger
import io.lightine.renovo.internal.engine.DownloadRecord
import io.lightine.renovo.internal.engine.Snapshot
import io.lightine.renovo.internal.store.PreferencesStore
import io.lightine.renovo.internal.work.WorkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** The real assembly on a test WorkManager. It does not touch the process-wide `Renovo` object. */
internal class RuntimeAssemblyTest : WorkTestBase() {
    private val update = UpdateInfo(versionCode = 12, versionName = "1.12", apkUrl = "https://example.com/app-12.apk")

    @Test
    fun `a host logger that throws cannot drop an engine event`() {
        // A download record whose file is gone: the engine logs about it while it handles the process start.
        val gone = UpdateInfo(versionCode = 11, versionName = "1.11", apkUrl = "https://example.com/app-11.apk")
        PreferencesStore(context, null).save(Snapshot(download = DownloadRecord(gone, filePath = "/no/such/folder/11.apk")))
        val calls = AtomicInteger()
        val config =
            RenovoConfig
                .Builder()
                .apply {
                    versionSource = VersionSource { update }
                    autoDownload = false
                    logger =
                        RenovoLogger { _, _ ->
                            calls.incrementAndGet()
                            throw IllegalStateException("The host logger is broken.")
                        }
                }.build()

        val runtime = RuntimeAssembly.create(context, config)

        // Without the guard the process start is dropped, the engine ignores everything after it,
        // and the state stays Idle for good.
        waitUntil { runtime.state.value is UpdateState.Available }
        assertEquals(update, (runtime.state.value as UpdateState.Available).info)
        assertTrue("the logger was reached, and it threw", calls.get() > 0)
    }

    @Test
    fun `the runtime only ever holds the guarded logger`() {
        val raw = RenovoLogger { _, _ -> }
        val config =
            RenovoConfig
                .Builder()
                .apply {
                    versionSource = VersionSource { update }
                    logger = raw
                }.build()

        val runtime = RuntimeAssembly.create(context, config)

        assertNotNull(runtime.logger)
        assertEquals("The guard wraps the host's logger once", SafeLogger.wrap(runtime.logger), runtime.logger)
        assertTrue(runtime.logger !== raw)
        assertTrue(runtime.config.logger === runtime.logger)
    }
}
