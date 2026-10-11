package io.lightine.renovo.internal.runtime

import android.app.Activity
import io.lightine.renovo.FailureReason
import io.lightine.renovo.Network
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.internal.Downloader
import io.lightine.renovo.internal.FetchResult
import io.lightine.renovo.internal.ForegroundTracker
import io.lightine.renovo.internal.InstallPermission
import io.lightine.renovo.internal.Installer
import io.lightine.renovo.internal.Notifier
import io.lightine.renovo.internal.PermissionGate
import io.lightine.renovo.internal.Scheduler
import io.lightine.renovo.internal.VerificationResult
import io.lightine.renovo.internal.Verifier
import io.lightine.renovo.internal.VersionFetcher
import io.lightine.renovo.internal.engine.EngineEvent
import io.lightine.renovo.internal.engine.EventSink
import io.lightine.renovo.internal.engine.Snapshot
import io.lightine.renovo.internal.engine.Store
import io.lightine.renovo.internal.work.CheckRunner
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/** The version code of the app under test in these tests. */
internal const val INSTALLED_VERSION = 10L

internal fun testInfo(
    versionCode: Long = 11,
    mandatory: Boolean = false,
): UpdateInfo = UpdateInfo(
    versionCode = versionCode,
    versionName = "1.$versionCode",
    apkUrl = "https://example.com/app-$versionCode.apk",
    mandatory = mandatory,
)

/** Fails with a recognizable exception, so a test can tell it from an accident. */
internal class FakeFailure(
    message: String = "A fake component failed on purpose.",
) : RuntimeException(message)

/** Answers every check with [result]. */
internal class FakeVersionFetcher : VersionFetcher {
    @Volatile
    var result: FetchResult = FetchResult.Failed(FailureReason.Network)

    /** The ETag each request was asked to send. */
    val etags = CopyOnWriteArrayList<String?>()

    @Volatile
    var failure: RuntimeException? = null

    override suspend fun fetch(etag: String?): FetchResult {
        etags += etag
        failure?.let { throw it }
        return result
    }
}

/** Runs checks through the real [CheckRunner], like the production scheduler, and records the rest. */
internal class FakeScheduler(
    private val runner: CheckRunner,
) : Scheduler {
    val intervals = CopyOnWriteArrayList<Duration>()
    val checks = CopyOnWriteArrayList<Boolean>()

    @Volatile
    var checkFailure: RuntimeException? = null

    @Volatile
    var scheduleFailure: RuntimeException? = null

    override fun schedulePeriodicCheck(interval: Duration) {
        scheduleFailure?.let { throw it }
        intervals += interval
    }

    override fun checkNow(userInitiated: Boolean) {
        checkFailure?.let { throw it }
        checks += userInitiated
        runner.checkNow(userInitiated)
    }
}

internal data class Enqueued(
    val info: UpdateInfo,
    val network: Network,
    val userInitiated: Boolean,
)

internal class FakeDownloader(
    private val events: EventSink,
    private val directory: File,
) : Downloader {
    val enqueued = CopyOnWriteArrayList<Enqueued>()

    @Volatile
    var cancels = 0

    @Volatile
    var enqueueFailure: RuntimeException? = null

    @Volatile
    var cancelFailure: RuntimeException? = null

    override fun enqueue(
        info: UpdateInfo,
        network: Network,
        userInitiated: Boolean,
    ) {
        enqueueFailure?.let { throw it }
        enqueued += Enqueued(info, network, userInitiated)
    }

    override fun cancel() {
        cancelFailure?.let { throw it }
        cancels++
    }

    /** Writes the file of [versionCode] and reports it finished, like the download worker. */
    fun finish(versionCode: Long): File {
        directory.mkdirs()
        val file = File(directory, "$versionCode.apk")
        file.writeText("apk of $versionCode")
        events.send(EngineEvent.DownloadFinished(versionCode, file.path))
        return file
    }
}

internal class FakeVerifier : Verifier {
    @Volatile
    var result: VerificationResult = VerificationResult.Passed

    @Volatile
    var failure: RuntimeException? = null
    val verified = CopyOnWriteArrayList<File>()
    val installedVersionCodes = CopyOnWriteArrayList<Long>()

    override suspend fun verify(
        file: File,
        info: UpdateInfo,
        installedVersionCode: Long,
    ): VerificationResult {
        verified += file
        installedVersionCodes += installedVersionCode
        failure?.let { throw it }
        return result
    }
}

internal class FakeInstaller : Installer {
    val installs = CopyOnWriteArrayList<Pair<File, UpdateInfo>>()

    @Volatile
    var failure: RuntimeException? = null

    override fun install(
        file: File,
        info: UpdateInfo,
    ) {
        failure?.let { throw it }
        installs += file to info
    }
}

internal class FakePermissionGate : PermissionGate {
    @Volatile
    var status: InstallPermission = InstallPermission.Granted

    @Volatile
    var opens = 0

    @Volatile
    var openResult = true

    @Volatile
    var statusFailure: RuntimeException? = null

    @Volatile
    var openFailure: RuntimeException? = null

    override fun status(): InstallPermission {
        statusFailure?.let { throw it }
        return status
    }

    override fun openSettings(): Boolean {
        openFailure?.let { throw it }
        opens++
        return openResult
    }
}

internal class FakeNotifier : Notifier {
    val shown = CopyOnWriteArrayList<UpdateInfo>()

    @Volatile
    var cancels = 0

    @Volatile
    var permissionRequests = 0

    @Volatile
    var failure: RuntimeException? = null

    override fun showReady(info: UpdateInfo) {
        failure?.let { throw it }
        shown += info
    }

    override fun cancelReady() {
        failure?.let { throw it }
        cancels++
    }

    override fun requestPermission(activity: Activity) {
        failure?.let { throw it }
        permissionRequests++
    }
}

/** @param newActivity Makes the activity that is resumed while the app is in the foreground. */
internal class FakeForeground(
    @Volatile override var isInForeground: Boolean = true,
    private val newActivity: () -> Activity?,
) : ForegroundTracker {
    @Volatile
    override var resumedActivity: Activity? = if (isInForeground) newActivity() else null
    private val callbacks = CopyOnWriteArrayList<() -> Unit>()

    val callbackCount: Int
        get() = callbacks.size

    override fun onForeground(callback: () -> Unit) {
        callbacks += callback
    }

    /** What the real tracker does when the user brings the app back: an activity resumes, then the callbacks run. */
    fun comeToForeground() {
        isInForeground = true
        if (resumedActivity == null) resumedActivity = newActivity()
        callbacks.forEach { it() }
    }

    /** What the real tracker sees when the user leaves the app. */
    fun goToBackground() {
        isInForeground = false
        resumedActivity = null
    }
}

/** Lets a test break loading or saving of the real store. */
internal class FlakyStore(
    private val real: Store,
) : Store {
    @Volatile
    var loadFailure: RuntimeException? = null

    @Volatile
    var saveFailure: RuntimeException? = null

    override fun load(): Snapshot? {
        loadFailure?.let { throw it }
        return real.load()
    }

    override fun save(snapshot: Snapshot) {
        saveFailure?.let { throw it }
        real.save(snapshot)
    }

    override fun clear() = real.clear()
}
