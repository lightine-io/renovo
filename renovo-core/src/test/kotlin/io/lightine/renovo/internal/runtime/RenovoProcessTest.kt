package io.lightine.renovo.internal.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.lightine.renovo.Renovo
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.internal.RuntimeFactory
import io.lightine.renovo.internal.engine.EventSink
import io.lightine.renovo.internal.work.FakeRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `Renovo.init` in a process that is not the main one.
 *
 * `Renovo` is a process-wide object that can be initialized once, and Robolectric keeps one per SDK
 * level. This class shares its SDK level only with a test that never touches `Renovo` (a new level
 * costs the test JVM another sandbox, more than its heap holds), and runs the steps that leave
 * `Renovo` initialized in one test, in order. API 28 or later so `Application.getProcessName`
 * answers: Robolectric's `ShadowApplication.setProcessName` sets the name it returns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class RenovoProcessTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `init builds nothing outside the main process, and once in it`() {
        val logs = CopyOnWriteArrayList<String>()
        val runtime = FakeRuntime(EventSink { })
        var created = 0
        Renovo.runtimeFactory = RuntimeFactory { _, _ -> runtime.also { created++ } }
        try {
            ShadowApplication.setProcessName("${context.packageName}:remote")

            // Invalid settings fail in every process, so a mistake shows up wherever init runs.
            assertThrows(IllegalArgumentException::class.java) { Renovo.init(context) { } }

            Renovo.init(context) {
                versionUrl = "https://example.com/version.json"
                logger = RenovoLogger { message, _ -> logs += message }
            }

            assertEquals(0, created)
            assertNull(Renovo.runtimeOrNull)
            assertTrue(logs.any { it.contains("not the app's main process") })
            assertThrows(IllegalStateException::class.java) { Renovo.checkNow() }

            ShadowApplication.setProcessName(context.packageName)
            Renovo.init(context) { versionUrl = "https://example.com/version.json" }

            assertEquals(1, created)
            assertNotNull(Renovo.runtimeOrNull)
            assertSame(runtime, Renovo.runtimeOrNull)
        } finally {
            Renovo.runtimeFactory = RuntimeFactory.Default
        }
    }
}
