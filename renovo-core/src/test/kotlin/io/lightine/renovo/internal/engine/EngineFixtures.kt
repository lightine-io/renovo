package io.lightine.renovo.internal.engine

import io.lightine.renovo.Network
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateState

/** Version code of the app under test. */
internal const val INSTALLED = 10L

/** Where the fake downloader puts the file of version 11. */
internal const val PATH = "/data/files/renovo/11.apk"

internal const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
internal const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

internal fun info(
    versionCode: Long = 11,
    mandatory: Boolean = false,
    minSupportedVersionCode: Long? = null,
    sha256: String? = HASH_A,
    size: Long? = 1000,
    apkUrl: String = "https://example.com/app-$versionCode.apk",
): UpdateInfo = UpdateInfo(
    versionCode = versionCode,
    versionName = "1.$versionCode",
    apkUrl = apkUrl,
    sha256 = sha256,
    size = size,
    mandatory = mandatory,
    minSupportedVersionCode = minSupportedVersionCode,
)

/** An optional update to version 11. */
internal val OPTIONAL = info()

/** A forced update to version 11. */
internal val FORCED = info(mandatory = true)

/** Automatic download on, every network allowed: the defaults. */
internal val AUTO = EngineSettings()

/** Automatic download off: a newer version waits for startDownload. */
internal val MANUAL = EngineSettings(autoDownload = false)

/** Forced updates only on unmetered networks, so they can wait for Wi-Fi. */
internal val FORCED_WIFI = EngineSettings(autoDownload = false, forcedDownloadNetwork = Network.UnmeteredOnly)

/** Drives one engine through events, starting with a reported process start. */
internal class EngineHarness(
    settings: EngineSettings = AUTO,
    snapshot: Snapshot? = null,
    installed: Long = INSTALLED,
    fileExists: Boolean = false,
) {
    val engine = UpdateEngine(settings)

    /** Effects of the process start. */
    val startEffects: List<Effect> = engine.handle(EngineEvent.Restored(snapshot, installed, fileExists))

    val state: UpdateState
        get() = engine.state.value

    val snapshot: Snapshot
        get() = engine.currentSnapshot()

    /** Sends [events] in order and returns all their effects. */
    fun send(vararg events: EngineEvent): List<Effect> = events.flatMap { engine.handle(it) }

    fun sendAll(events: List<EngineEvent>): List<Effect> = events.flatMap { engine.handle(it) }
}

internal fun List<Effect>.withoutPersist(): List<Effect> = filterNot { it is Effect.Persist }

internal fun List<Effect>.lastPersisted(): Snapshot? = filterIsInstance<Effect.Persist>().lastOrNull()?.snapshot
