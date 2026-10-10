package io.lightine.renovo.internal

import android.app.Activity
import io.lightine.renovo.Renovo
import io.lightine.renovo.RenovoConfig
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.ThemeMode
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateListener
import io.lightine.renovo.UpdateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeHolderTest {
    private class FakeRuntime(
        override val logger: RenovoLogger?,
    ) : RenovoRuntime {
        override val config: RenovoConfig =
            RenovoConfig.Builder()
                .apply {
                    versionUrl = "https://example.com/version.json"
                    ui { themeMode = ThemeMode.Dark }
                }.build()
        override val state: StateFlow<UpdateState> = MutableStateFlow(UpdateState.Idle)
        var checks = 0

        override fun addListener(listener: UpdateListener) = Unit

        override fun removeListener(listener: UpdateListener) = Unit

        override fun checkNow() {
            checks++
        }

        override fun startDownload() = Unit

        override fun installIfReady() = Unit

        override fun submit(info: UpdateInfo) = Unit

        override fun useMobileDataOnce() = Unit

        override fun requestNotificationPermission(activity: Activity) = Unit
    }

    @Test
    fun `every call before init fails with a clear message`() {
        val holder = RuntimeHolder()

        val error = assertThrows(IllegalStateException::class.java) { holder.runtime.state }
        assertEquals("Renovo.init was not called", error.message)
        assertThrows(IllegalStateException::class.java) { holder.runtime.checkNow() }
        assertThrows(IllegalStateException::class.java) { holder.runtime.startDownload() }
        assertThrows(IllegalStateException::class.java) { holder.runtime.installIfReady() }
        assertThrows(IllegalStateException::class.java) { holder.runtime.useMobileDataOnce() }
        assertThrows(IllegalStateException::class.java) { holder.runtime.addListener { } }
        val configError = assertThrows(IllegalStateException::class.java) { holder.runtime.config }
        assertEquals("Renovo.init was not called", configError.message)
    }

    @Test
    fun `the facade fails before init`() {
        val error = assertThrows(IllegalStateException::class.java) { Renovo.checkNow() }
        assertEquals("Renovo.init was not called", error.message)
        val configError = assertThrows(IllegalStateException::class.java) { Renovo.config }
        assertEquals("Renovo.init was not called", configError.message)
    }

    @Test
    fun `the config given to init is readable after init`() {
        val runtime = FakeRuntime(null)
        val holder = RuntimeHolder()
        holder.initialize { runtime }

        assertSame(runtime.config, holder.runtime.config)
        assertEquals(ThemeMode.Dark, holder.runtime.config.ui.themeMode)
        assertTrue(holder.runtime.config.canInstallNow())
    }

    @Test
    fun `a second init is ignored and logged`() {
        val messages = mutableListOf<String>()
        val first = FakeRuntime(RenovoLogger { message, _ -> messages += message })
        val holder = RuntimeHolder()

        assertTrue(holder.initialize { first })
        assertFalse(holder.initialize { error("must not be called") })

        assertSame(first, holder.runtime)
        assertEquals(listOf("Renovo.init was called again. The second call is ignored."), messages)
    }

    @Test
    fun `calls are forwarded after init`() {
        val runtime = FakeRuntime(null)
        val holder = RuntimeHolder()
        holder.initialize { runtime }

        holder.runtime.checkNow()

        assertEquals(1, runtime.checks)
    }

    @Test
    fun `a failed init can be repeated`() {
        val holder = RuntimeHolder()

        assertThrows(IllegalArgumentException::class.java) { holder.initialize { throw IllegalArgumentException("bad") } }
        assertTrue(holder.initialize { FakeRuntime(null) })
    }
}
