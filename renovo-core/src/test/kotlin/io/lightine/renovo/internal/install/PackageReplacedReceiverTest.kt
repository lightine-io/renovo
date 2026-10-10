package io.lightine.renovo.internal.install

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.lightine.renovo.RenovoConfig
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateListener
import io.lightine.renovo.UpdateState
import io.lightine.renovo.internal.ForegroundTracker
import io.lightine.renovo.internal.RenovoRuntime
import io.lightine.renovo.internal.engine.EngineEvent
import io.lightine.renovo.internal.engine.EventSink
import io.lightine.renovo.internal.engine.InstallResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeRuntime(
    override val events: EventSink,
    override val logger: RenovoLogger?,
) : RenovoRuntime {
    override val config: RenovoConfig = RenovoConfig.Builder().apply { versionUrl = "https://example.com/version.json" }.build()
    override val state: StateFlow<UpdateState> = MutableStateFlow(UpdateState.Idle)
    override val foreground: ForegroundTracker = FakeForeground()

    override fun addListener(listener: UpdateListener) = Unit

    override fun removeListener(listener: UpdateListener) = Unit

    override fun checkNow() = Unit

    override fun startDownload() = Unit

    override fun installIfReady() = Unit

    override fun submit(info: UpdateInfo) = Unit

    override fun useMobileDataOnce() = Unit

    override fun requestNotificationPermission(activity: Activity) = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 37])
class PackageReplacedReceiverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sink = RecordingSink()
    private val logger = RecordingLogger()
    private val sessions = InstallSessions()
    private val runtime = FakeRuntime(sink, logger)

    @Test
    fun `an open session of this process is reported as installed, once`() {
        sessions.opened(3)
        val receiver = PackageReplacedReceiver(sessions)

        receiver.report(runtime)
        receiver.report(runtime)

        assertEquals(listOf(EngineEvent.InstallFinished(InstallResult.Success)), sink.events)
        assertTrue(logger.contains("replaced by a new version"))
    }

    @Test
    fun `without an open session only the log is written`() {
        PackageReplacedReceiver(sessions).report(runtime)

        assertTrue(sink.events.isEmpty())
        assertTrue(logger.contains("replaced by a new version"))
    }

    @Test
    fun `without init or with another action nothing happens`() {
        sessions.opened(3)
        val receiver = PackageReplacedReceiver(sessions)

        receiver.report(null)
        receiver.onReceive(context, Intent(Intent.ACTION_PACKAGE_ADDED))
        // Renovo.init was never called in this test process.
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        assertTrue(sink.events.isEmpty())
        assertEquals(3, sessions.openSessionId)
    }
}
