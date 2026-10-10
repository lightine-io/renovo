package io.lightine.renovo.internal.work

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.lightine.renovo.RenovoConfig
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateListener
import io.lightine.renovo.UpdateState
import io.lightine.renovo.internal.Clock
import io.lightine.renovo.internal.ForegroundTracker
import io.lightine.renovo.internal.RenovoRuntime
import io.lightine.renovo.internal.engine.EngineEvent
import io.lightine.renovo.internal.engine.EventSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Robolectric SDK level every test in this package runs on, unless a test says otherwise.
 *
 * Not 36: Robolectric's SDK 36 environment needs `--add-exports java.base/jdk.internal.access` on the
 * test JVM, which would be a build setting for a gain these tests do not need.
 */
internal const val TEST_SDK = 37

/** Starts a test WorkManager and puts [WorkEnvironment] back to its defaults around each test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [TEST_SDK])
internal abstract class WorkTestBase {
    protected val context: Context
        get() = ApplicationProvider.getApplicationContext()

    protected val sink = RecordingSink()

    @Before
    fun setUpWorkManager() {
        WorkEnvironment.reset()
        // Robolectric has no storage volumes, so the real implementation cannot answer. Plenty of room by default.
        WorkEnvironment.freeSpace = FreeSpace { _, _ -> Long.MAX_VALUE }
        val configuration =
            Configuration
                .Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
    }

    /** Sets what the default network can do. [online] false leaves the device without any network. */
    protected fun setNetwork(
        metered: Boolean = false,
        roaming: Boolean = false,
        online: Boolean = true,
        validated: Boolean = true,
    ) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(manager)
        if (!online) {
            shadow.setNetworkCapabilities(manager.activeNetwork, null)
            return
        }
        val capabilities = ShadowNetworkCapabilities.newInstance()
        val flags = shadowOf(capabilities)
        flags.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (validated) flags.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!metered) flags.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        if (!roaming) flags.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        shadow.setNetworkCapabilities(manager.activeNetwork, capabilities)
    }

    @After
    fun tearDownWorkManager() {
        WorkEnvironment.reset()
        File(context.filesDir, "renovo").deleteRecursively()
    }
}

/** Keeps every event, in order. */
internal class RecordingSink : EventSink {
    private val received = CopyOnWriteArrayList<EngineEvent>()

    val events: List<EngineEvent>
        get() = received.toList()

    override fun send(event: EngineEvent) {
        received.add(event)
    }

    inline fun <reified T : EngineEvent> all(): List<T> = events.filterIsInstance<T>()

    fun clear() = received.clear()
}

/** A runtime that has only what the workers read. */
internal class FakeRuntime(
    override val events: EventSink,
    override val logger: RenovoLogger? = null,
) : RenovoRuntime {
    override val config: RenovoConfig = RenovoConfig.Builder().apply { versionUrl = "https://example.com/version.json" }.build()
    override val state: StateFlow<UpdateState> = MutableStateFlow(UpdateState.Idle)

    override fun addListener(listener: UpdateListener) = Unit

    override fun removeListener(listener: UpdateListener) = Unit

    override fun checkNow() = Unit

    override fun startDownload() = Unit

    override fun installIfReady() = Unit

    override fun submit(info: UpdateInfo) = Unit

    override fun useMobileDataOnce() = Unit

    override fun requestNotificationPermission(activity: Activity) = Unit
}

internal class FakeForegroundTracker(
    override var isInForeground: Boolean = true,
) : ForegroundTracker {
    override val resumedActivity: Activity? = null

    override fun onForeground(callback: () -> Unit) = Unit
}

/** A clock the test moves by hand. */
internal class ManualClock(
    var now: Long = 1_000_000,
) : Clock {
    override fun nowMillis(): Long = now
}

internal fun updateInfo(
    versionCode: Long = 11,
    size: Long? = null,
): UpdateInfo = UpdateInfo(
    versionCode = versionCode,
    versionName = "1.$versionCode",
    apkUrl = "https://example.com/app-$versionCode.apk",
    size = size,
)

/** Polls [condition] until it holds. WorkManager's test executor still hands some work to other threads. */
internal fun eventually(
    timeoutMillis: Long = 10_000,
    message: String = "The condition was not met in time.",
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) throw AssertionError(message)
        Thread.sleep(10)
    }
}
