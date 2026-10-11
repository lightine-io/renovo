package io.lightine.renovo.internal.runtime

import io.lightine.renovo.Renovo
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateState
import io.lightine.renovo.VersionSource
import io.lightine.renovo.internal.work.WorkEnvironment
import io.lightine.renovo.internal.work.WorkTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * `Renovo.init` with the real components and the real loop, on a test WorkManager.
 *
 * `Renovo` is a process-wide object that can be initialized once. Robolectric keeps one
 * `Renovo` per SDK level, and other tests assume it is not initialized, so this class has an SDK
 * level of its own and holds the only test that calls `init`.
 */
@Config(sdk = [35])
internal class RenovoInitTest : WorkTestBase() {
    @Test
    fun `init wires the real runtime and the first check finds the update`() {
        val logs = CopyOnWriteArrayList<String>()
        val fetches = AtomicInteger()

        Renovo.init(context) {
            versionSource =
                VersionSource {
                    fetches.incrementAndGet()
                    UpdateInfo(versionCode = 11, versionName = "1.11", apkUrl = "https://example.com/app-11.apk")
                }
            autoDownload = false
            logger = RenovoLogger { message, _ -> logs += message }
        }

        // The start is posted to the main thread, so the runtime exists before anything runs.
        val runtime = Renovo.runtimeOrNull
        assertNotNull(runtime)
        assertEquals(UpdateState.Idle, Renovo.state.value)

        waitUntil { Renovo.state.value is UpdateState.Available }
        assertEquals(11L, (Renovo.state.value as UpdateState.Available).info.versionCode)

        // The workers the system creates find this runtime and the periodic check through it.
        assertSame(runtime, WorkEnvironment.runtimeProvider())
        assertNotNull(WorkEnvironment.checkRunner)

        // The facade forwards user actions to the same loop, which runs another check.
        assertEquals(1, fetches.get())
        Renovo.checkNow()
        waitUntil { fetches.get() == 2 }

        // A second init is ignored and logged (the first one already holds the runtime).
        Renovo.init(context) { versionSource = VersionSource { error("never called") } }
        assertSame(runtime, Renovo.runtimeOrNull)
        assertTrue(logs.any { it.contains("called again") })
    }
}
