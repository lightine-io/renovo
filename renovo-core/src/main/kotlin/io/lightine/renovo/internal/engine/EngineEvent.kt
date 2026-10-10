package io.lightine.renovo.internal.engine

import io.lightine.renovo.DownloadProgress
import io.lightine.renovo.FailureReason
import io.lightine.renovo.UpdateInfo

/**
 * Something that happened, reported to [UpdateEngine.handle].
 *
 * Download and verification events carry the version code they belong to, so a late event from a
 * download that was replaced by a newer version cannot change the state of the newer one.
 */
internal sealed interface EngineEvent {
    /**
     * The process started. Must be the first event; every other event before it is ignored.
     *
     * @property snapshot What the store loaded, or null on the first run.
     * @property installedVersionCode The version code of the running app.
     * @property fileExists Whether the snapshot's downloaded file still exists. False when the
     *   snapshot records no file.
     */
    data class Restored(
        val snapshot: Snapshot?,
        val installedVersionCode: Long,
        val fileExists: Boolean,
    ) : EngineEvent

    /** A check began, started by the user ([io.lightine.renovo.Renovo.checkNow]) or in the background. */
    data class CheckStarted(
        val userInitiated: Boolean,
    ) : EngineEvent

    /** A check returned [info]. [etag] is the response's ETag, or null when there was none. */
    data class CheckSucceeded(
        val info: UpdateInfo,
        val etag: String?,
    ) : EngineEvent

    /** The server answered 304 Not Modified to the stored ETag. */
    data object CheckNotModified : EngineEvent

    /** A check failed for good (its retries, if any, are used up). */
    data class CheckFailed(
        val reason: FailureReason,
    ) : EngineEvent

    /** The host app handed over version information. Same effect as a successful check. */
    data class Submit(
        val info: UpdateInfo,
    ) : EngineEvent

    data class DownloadProgressed(
        val versionCode: Long,
        val progress: DownloadProgress,
    ) : EngineEvent

    /** The download cannot run until a network the settings allow is available. */
    data class DownloadWaitingForNetwork(
        val versionCode: Long,
    ) : EngineEvent

    /** The whole file is on disk at [filePath]. Not verified yet. */
    data class DownloadFinished(
        val versionCode: Long,
        val filePath: String,
    ) : EngineEvent

    /** The download failed for good ([FailureReason.Network] after the retries, or [FailureReason.Storage]). */
    data class DownloadFailed(
        val versionCode: Long,
        val reason: FailureReason,
    ) : EngineEvent

    data class VerificationPassed(
        val versionCode: Long,
    ) : EngineEvent

    data class VerificationFailed(
        val versionCode: Long,
        val reason: FailureReason,
    ) : EngineEvent

    /** The user must allow installs from this app before the install can start. */
    data object PermissionNeeded : EngineEvent

    /** The user came back with the install permission granted. */
    data object PermissionGranted : EngineEvent

    /** The installer session was committed and the system installer has the file. */
    data object InstallStarted : EngineEvent

    data class InstallFinished(
        val result: InstallResult,
    ) : EngineEvent

    /** The install was not started: `canInstallNow` returned false, or the app is not in the foreground. */
    data object InstallNotAllowedNow : EngineEvent

    /** The device offers no way to allow the install (policy, restriction, missing settings screen). */
    data object DeviceUnsupported : EngineEvent

    /** User action: [io.lightine.renovo.Renovo.checkNow]. */
    data object CheckNow : EngineEvent

    /** User action: [io.lightine.renovo.Renovo.startDownload]. */
    data object StartDownload : EngineEvent

    /** User action: [io.lightine.renovo.Renovo.installIfReady]. */
    data object InstallIfReady : EngineEvent

    /** User action: [io.lightine.renovo.Renovo.useMobileDataOnce]. */
    data object UseMobileDataOnce : EngineEvent

    /** User action: "Try again" after a failure. */
    data object Retry : EngineEvent
}
