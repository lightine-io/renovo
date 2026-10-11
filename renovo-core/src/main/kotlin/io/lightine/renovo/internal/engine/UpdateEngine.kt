package io.lightine.renovo.internal.engine

import io.lightine.renovo.DownloadProgress
import io.lightine.renovo.FailureReason
import io.lightine.renovo.Network
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateState
import io.lightine.renovo.UpdateStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the update state and decides every transition. The only writer of [state].
 *
 * The runtime reports what happened with [handle] and executes the returned [Effect]s in order.
 * The engine does no I/O and uses no Android class, so a given state and event always produce the
 * same next state and effects, and every rule is tested on the plain JVM.
 *
 * [handle] is thread-safe, but the runtime must feed events one at a time from a single consumer,
 * so effects of consecutive events (two [Effect.Persist] for example) are executed in order.
 */
internal class UpdateEngine(
    private val settings: EngineSettings,
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)

    /** The public update state. Only [handle] changes it. */
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    // Everything below is guarded by lock.
    private var restored = false
    private var installed = 0L
    private var snapshot = Snapshot()
    private var model: Model = Model.Idle

    // Shown in UpdateState.Downloading and in failures of the download. Not persisted: a download
    // resumed after a restart runs in the background, whoever started it.
    private var downloadUserInitiated = false
    private val pending = mutableListOf<Effect>()

    /** The snapshot as of the last event. The runtime reads the ETag for a check from it. */
    fun currentSnapshot(): Snapshot = synchronized(lock) { snapshot }

    /** Applies [event] and returns the effects to execute, in order. */
    fun handle(event: EngineEvent): List<Effect> = synchronized(lock) {
        pending.clear()
        val before = snapshot
        when {
            event is EngineEvent.Restored -> onRestored(event)
            !restored -> log("Ignored ${name(event)} before the process start was reported.")
            else -> dispatch(event)
        }
        val effects =
            buildList {
                if (snapshot != before) add(Effect.Persist(snapshot))
                addAll(pending)
            }
        pending.clear()
        mutableState.value = render()
        effects
    }

    private fun dispatch(event: EngineEvent) {
        when (event) {
            is EngineEvent.Restored -> log("Ignored a second process start report.")
            is EngineEvent.CheckStarted -> onCheckStarted(event.userInitiated)
            is EngineEvent.CheckSucceeded -> onCheckSucceeded(event.info, event.etag)
            EngineEvent.CheckNotModified -> onCheckNotModified()
            is EngineEvent.CheckFailed -> onCheckFailed(event.reason)
            is EngineEvent.Submit -> onSubmit(event.info)
            is EngineEvent.DownloadProgressed -> onDownloadProgressed(event.versionCode, event.progress)
            is EngineEvent.DownloadWaitingForNetwork -> onDownloadWaitingForNetwork(event.versionCode)
            is EngineEvent.DownloadFinished -> onDownloadFinished(event.versionCode, event.filePath)
            is EngineEvent.DownloadFailed -> onDownloadFailed(event.versionCode, event.reason)
            is EngineEvent.VerificationPassed -> onVerificationPassed(event.versionCode, event.filePath)
            is EngineEvent.VerificationFailed -> onVerificationFailed(event.versionCode, event.filePath, event.reason)
            EngineEvent.PermissionNeeded -> onPermissionNeeded()
            EngineEvent.PermissionGranted -> onPermissionGranted()
            EngineEvent.InstallStarted -> onInstallStarted()
            is EngineEvent.InstallFinished -> onInstallFinished(event.result)
            EngineEvent.InstallNotAllowedNow -> onInstallNotAllowedNow()
            EngineEvent.DeviceUnsupported -> markUnsupported()
            EngineEvent.CheckNow -> emit(Effect.StartCheck(userInitiated = true))
            EngineEvent.StartDownload -> onStartDownload()
            EngineEvent.InstallIfReady -> onInstallIfReady()
            EngineEvent.UseMobileDataOnce -> onUseMobileDataOnce()
        }
    }

    // ---- Process start -------------------------------------------------------------------------

    private fun onRestored(event: EngineEvent.Restored) {
        if (restored) {
            log("Ignored a second process start report.")
            return
        }
        restored = true
        installed = event.installedVersionCode
        var restoredSnapshot = event.snapshot ?: Snapshot()

        val record = restoredSnapshot.download
        if (record != null) {
            val path = record.filePath
            when {
                // The recorded version, or a later one, is installed now: the file has done its job.
                record.info.versionCode <= installed -> {
                    if (path == null) {
                        emit(Effect.CancelDownload)
                    } else if (event.fileExists) {
                        emit(Effect.DeleteFile(path))
                    }
                    if (record.verified) emit(Effect.CancelReadyNotification)
                    restoredSnapshot = restoredSnapshot.copy(download = null)
                }

                path != null && !event.fileExists -> {
                    log("The downloaded file of version ${record.info.versionCode} is gone. It is downloaded again if needed.")
                    restoredSnapshot = restoredSnapshot.copy(download = null)
                }
            }
        }
        // Unsupported is tied to the installed version: a new version may install fine.
        if (restoredSnapshot.unsupportedAtVersionCode.let { it != null && it != installed }) {
            restoredSnapshot = restoredSnapshot.copy(unsupportedAtVersionCode = null)
        }
        if (restoredSnapshot.rejected.let { it != null && it.info.versionCode <= installed }) {
            restoredSnapshot = restoredSnapshot.copy(rejected = null)
        }
        if (restoredSnapshot.mobileDataVersionCode.let { it != null && it <= installed }) {
            restoredSnapshot = restoredSnapshot.copy(mobileDataVersionCode = null)
        }
        snapshot = restoredSnapshot

        model = if (isUnsupported()) Model.Unsupported(null) else Model.Idle
        // A forced update must block again right away, even when the device is offline now.
        snapshot.latest?.let { applyInfo(it) }
        emit(Effect.ScheduleChecks)
        emit(Effect.StartCheck(userInitiated = false))
    }

    // ---- Checks --------------------------------------------------------------------------------

    private fun onCheckStarted(userInitiated: Boolean) {
        when (val current = model) {
            Model.Idle, Model.UpToDate -> model = Model.Checking(current, userInitiated)

            // A background check must not hide a failure the user is looking at.
            is Model.CheckFailed -> if (userInitiated) model = Model.Checking(current, true)

            is Model.Checking -> model = current.copy(userInitiated = current.userInitiated || userInitiated)

            // A known update or an unsupported device stays on screen while the check runs.
            is Model.Unsupported, is Model.Working -> Unit
        }
    }

    private fun onCheckSucceeded(
        info: UpdateInfo,
        etag: String?,
    ) {
        snapshot = snapshot.copy(latest = info, etag = etag)
        finishCheck()
        applyInfo(info)
    }

    private fun onCheckNotModified() {
        finishCheck()
        val latest = snapshot.latest
        if (latest != null) {
            applyInfo(latest)
        } else {
            // Cannot happen when the runtime sends the ETag only with a stored version. Drop the
            // ETag so the next check fetches the whole file.
            log("Got 304 Not Modified without stored version information. The ETag is dropped.")
            snapshot = snapshot.copy(etag = null)
        }
    }

    private fun onCheckFailed(reason: FailureReason) {
        val current = model
        if (current is Model.Checking) {
            model = if (current.userInitiated) Model.CheckFailed(reason, userInitiated = true) else current.before
        } else {
            // An update is already known; a failed check does not make it less true.
            log("A check failed ($reason). The state is unchanged.")
        }
    }

    private fun onSubmit(info: UpdateInfo) {
        // The ETag belonged to the previous version file response, not to this information.
        snapshot = snapshot.copy(latest = info, etag = null)
        finishCheck()
        applyInfo(info)
    }

    private fun finishCheck() {
        (model as? Model.Checking)?.let { model = it.before }
    }

    /**
     * Brings the state in line with [info], the newest version information. The newest
     * information wins, even when it names a lower version than an earlier check: the publisher
     * may have withdrawn a release.
     */
    private fun applyInfo(info: UpdateInfo) {
        if (info.versionCode <= installed) {
            discardDownload()
            model = if (isUnsupported()) Model.Unsupported(null) else Model.UpToDate
            return
        }
        if (isUnsupported()) {
            discardDownload()
            model = Model.Unsupported(info)
            return
        }

        val existingStage = (model as? Model.Working)?.takeIf { sameFile(it.info, info) }?.stage
        snapshot.download?.let { if (!sameFile(it.info, info)) discardDownload() }

        val record = snapshot.download
        if (record != null) {
            // Same file: never download it twice. Take over the new details (notes, forced flag).
            val updated = record.copy(info = info)
            snapshot = snapshot.copy(download = updated)
            model = Model.Working(info, existingStage ?: resume(updated))
            return
        }

        val rejection = snapshot.rejected
        if (rejection != null) {
            if (sameFile(rejection.info, info)) {
                // A cancelled download stays offered for the user to start; a broken file stays failed.
                val remembered = if (rejection.reason == FailureReason.Cancelled) Stage.Offered else Stage.Failed(rejection.reason, userInitiated = false)
                model = Model.Working(info, existingStage ?: remembered)
                return
            }
            snapshot = snapshot.copy(rejected = null)
        }

        if (settings.autoDownload) {
            startDownload(info, userInitiated = false)
        } else {
            model = Model.Working(info, existingStage ?: Stage.Offered)
        }
    }

    /** Picks up a download that exists on record but has no stage in memory, after a restart. */
    private fun resume(record: DownloadRecord): Stage {
        val path = record.filePath
        return when {
            path == null -> {
                downloadUserInitiated = false
                emit(Effect.EnqueueDownload(record.info, networkFor(record.info), userInitiated = false))
                Stage.Downloading(DownloadProgress(0, record.info.size))
            }

            // The process died between download and verification.
            !record.verified -> {
                emit(Effect.Verify(path, record.info))
                Stage.Downloading(completeProgress(record.info))
            }

            else -> Stage.Ready
        }
    }

    // ---- Download ------------------------------------------------------------------------------

    private fun startDownload(
        info: UpdateInfo,
        userInitiated: Boolean,
    ) {
        // A download that starts is wanted, so no earlier rejection of this file may hold it back. The
        // other callers have cleared it already; a start from Offered after a cancel has not.
        snapshot = snapshot.copy(download = DownloadRecord(info), rejected = null)
        downloadUserInitiated = userInitiated
        emit(Effect.EnqueueDownload(info, networkFor(info), userInitiated))
        model = Model.Working(info, Stage.Downloading(DownloadProgress(0, info.size)))
    }

    private fun discardDownload() {
        val record = snapshot.download ?: return
        val path = record.filePath
        if (path == null) emit(Effect.CancelDownload) else emit(Effect.DeleteFile(path))
        if (record.verified) emit(Effect.CancelReadyNotification)
        snapshot = snapshot.copy(download = null)
        downloadUserInitiated = false
    }

    private fun onDownloadProgressed(
        versionCode: Long,
        progress: DownloadProgress,
    ) {
        val working = runningDownload(versionCode) ?: return log("Ignored stale download progress for version $versionCode.")
        model = working.copy(stage = Stage.Downloading(progress))
    }

    private fun onDownloadWaitingForNetwork(versionCode: Long) {
        val working = runningDownload(versionCode) ?: return log("Ignored a stale network wait for version $versionCode.")
        model = working.copy(stage = Stage.WaitingForNetwork(lastProgress(working.stage, working.info)))
    }

    private fun onDownloadFinished(
        versionCode: Long,
        filePath: String,
    ) {
        val record = snapshot.download
        val working = runningDownload(versionCode)
        if (record == null || working == null) {
            if (record?.filePath != filePath) {
                // Nobody wants this file any more, for example after a newer version replaced it.
                log("Deleted the file of a download that is no longer wanted (version $versionCode).")
                emit(Effect.DeleteFile(filePath))
            }
            return
        }
        snapshot = snapshot.copy(download = record.copy(filePath = filePath))
        model = working.copy(stage = Stage.Downloading(completeProgress(working.info)))
        emit(Effect.Verify(filePath, record.info))
    }

    private fun onDownloadFailed(
        versionCode: Long,
        reason: FailureReason,
    ) {
        val working = runningDownload(versionCode) ?: return log("Ignored a stale download failure for version $versionCode.")
        // The partial file stays with the downloader, so the next attempt resumes instead of starting over.
        if (reason == FailureReason.Cancelled) {
            // A cancel from the notification means "not now", not a failure. The update goes back to
            // offered, so the user can still start it later, and the cancel is remembered like a
            // rejected file, so the next check or start does not download it again by itself.
            snapshot = snapshot.copy(download = null, rejected = Rejection(working.info, reason))
            downloadUserInitiated = false
            model = working.copy(stage = Stage.Offered)
            return
        }
        snapshot = snapshot.copy(download = null)
        model = working.copy(stage = Stage.Failed(reason, downloadUserInitiated))
    }

    /** The current model, if it is downloading [versionCode] and the file is not complete yet. */
    private fun runningDownload(versionCode: Long): Model.Working? {
        val working = model as? Model.Working ?: return null
        val record = snapshot.download ?: return null
        val downloading = working.stage is Stage.Downloading || working.stage is Stage.WaitingForNetwork
        return working.takeIf {
            downloading && record.filePath == null && record.info.versionCode == versionCode && working.info.versionCode == versionCode
        }
    }

    // ---- Verification --------------------------------------------------------------------------

    private fun onVerificationPassed(
        versionCode: Long,
        filePath: String,
    ) {
        val working = verifying(versionCode, filePath) ?: return log("Ignored a stale verification result for version $versionCode.")
        val record = snapshot.download ?: return
        val path = record.filePath ?: return
        snapshot = snapshot.copy(download = record.copy(verified = true))
        model = working.copy(stage = Stage.Ready)
        if (settings.notifications) emit(Effect.ShowReadyNotification(working.info))
        // The user already asked for this forced update; there is no "ready" screen to confirm it on.
        if (isForced(working.info) && downloadUserInitiated) emit(Effect.Install(path, working.info))
    }

    private fun onVerificationFailed(
        versionCode: Long,
        filePath: String,
        reason: FailureReason,
    ) {
        val working = verifying(versionCode, filePath) ?: return log("Ignored a stale verification result for version $versionCode.")
        val record = snapshot.download ?: return
        val path = record.filePath ?: return
        emit(Effect.DeleteFile(path))
        val remember = reason == FailureReason.HashMismatch || reason == FailureReason.SignatureMismatch || reason == FailureReason.NotNewer
        snapshot =
            snapshot.copy(
                download = null,
                rejected = if (remember) Rejection(record.info, reason) else snapshot.rejected,
            )
        model = working.copy(stage = Stage.Failed(reason, downloadUserInitiated))
    }

    /**
     * The current model, if the file of [versionCode] at [filePath] is complete and waits for its
     * verification. The path must be the recorded one: a result for a file that was deleted and
     * downloaded again under the same version code must not mark the new file as checked.
     */
    private fun verifying(
        versionCode: Long,
        filePath: String,
    ): Model.Working? {
        val working = model as? Model.Working ?: return null
        val record = snapshot.download ?: return null
        return working.takeIf {
            record.filePath == filePath && !record.verified && record.info.versionCode == versionCode && working.info.versionCode == versionCode
        }
    }

    // ---- Install -------------------------------------------------------------------------------

    /** The current model and the file path, if a verified file of the current version exists. */
    private fun verifiedFile(): Pair<Model.Working, String>? {
        val working = model as? Model.Working ?: return null
        val record = snapshot.download ?: return null
        val path = record.filePath ?: return null
        return if (record.verified && sameFile(record.info, working.info)) working to path else null
    }

    private fun requestInstall() {
        val (working, path) = verifiedFile() ?: return log("Install requested, but no verified file exists.")
        emit(Effect.Install(path, working.info))
    }

    private fun onPermissionNeeded() {
        val (working, _) = verifiedFile() ?: return log("Ignored a permission request without a verified file.")
        model = working.copy(stage = Stage.PermissionNeeded)
    }

    private fun onPermissionGranted() {
        val (working, path) = verifiedFile() ?: return
        // Only a pending install continues by itself; a grant at any other time changes nothing.
        if (working.stage == Stage.PermissionNeeded) emit(Effect.Install(path, working.info))
    }

    private fun onInstallStarted() {
        val (working, _) = verifiedFile() ?: return log("Ignored an install start without a verified file.")
        model = working.copy(stage = Stage.Installing)
    }

    private fun onInstallNotAllowedNow() {
        val (working, _) = verifiedFile() ?: return
        // Stays ready; the next installIfReady or the next app start offers the install again.
        model = working.copy(stage = Stage.Ready)
    }

    private fun onInstallFinished(result: InstallResult) {
        val (working, path) = verifiedFile() ?: return log("Ignored an install result ($result) without a verified file.")
        when (result) {
            InstallResult.Success -> {
                emit(Effect.DeleteFile(path))
                emit(Effect.CancelReadyNotification)
                emit(Effect.ScheduleChecks)
                installed = working.info.versionCode
                snapshot =
                    snapshot.copy(
                        download = null,
                        rejected = null,
                        mobileDataVersionCode = null,
                        unsupportedAtVersionCode = null,
                    )
                downloadUserInitiated = false
                model = Model.UpToDate
            }

            // The verified file is kept, so trying again does not download it again.
            InstallResult.Rejected -> model = working.copy(stage = Stage.Failed(FailureReason.InstallRejected, userInitiated = true))

            InstallResult.Cancelled -> model = working.copy(stage = Stage.Failed(FailureReason.Cancelled, userInitiated = true))

            InstallResult.Blocked -> markUnsupported()
        }
    }

    private fun markUnsupported() {
        val info =
            when (val current = model) {
                is Model.Working -> current.info
                is Model.Unsupported -> current.info
                else -> null
            }
        discardDownload()
        snapshot = snapshot.copy(unsupportedAtVersionCode = installed)
        model = Model.Unsupported(info)
    }

    // ---- User actions --------------------------------------------------------------------------

    private fun onStartDownload() {
        when (val current = model) {
            is Model.Working ->
                when (current.stage) {
                    Stage.Offered -> startDownload(current.info, userInitiated = true)
                    is Stage.Downloading, is Stage.WaitingForNetwork -> adoptRunningDownload(current)
                    Stage.Ready -> requestInstall()
                    Stage.PermissionNeeded -> emit(Effect.OpenInstallPermission)
                    Stage.Installing -> log("Ignored startDownload while installing.")
                    is Stage.Failed -> retry()
                }

            is Model.CheckFailed -> retry()

            else -> log("Ignored startDownload: no update is available.")
        }
    }

    /** The user asked for a download that already runs in the background: make it theirs. */
    private fun adoptRunningDownload(working: Model.Working) {
        val record = snapshot.download
        if (downloadUserInitiated || record == null || record.filePath != null) return
        downloadUserInitiated = true
        emit(Effect.EnqueueDownload(working.info, networkFor(working.info), userInitiated = true))
    }

    private fun onInstallIfReady() {
        val current = model as? Model.Working ?: return log("Ignored installIfReady: no update is ready.")
        when (current.stage) {
            Stage.PermissionNeeded -> emit(Effect.OpenInstallPermission)
            Stage.Installing -> log("Ignored installIfReady while installing.")
            else -> requestInstall()
        }
    }

    private fun retry() {
        when (val current = model) {
            is Model.CheckFailed -> emit(Effect.StartCheck(userInitiated = true))

            is Model.Working -> {
                if (current.stage !is Stage.Failed) return log("Ignored a retry: nothing failed.")
                if (verifiedFile() != null) {
                    requestInstall()
                } else {
                    // A retry by the user overrides the memory of a rejected file.
                    snapshot = snapshot.copy(rejected = null)
                    discardDownload()
                    startDownload(current.info, userInitiated = true)
                }
            }

            else -> log("Ignored a retry: nothing failed.")
        }
    }

    private fun onUseMobileDataOnce() {
        val current = model as? Model.Working
        val stage = current?.stage
        if (current == null || stage !is Stage.WaitingForNetwork || !isForced(current.info) || !canUseMobileData(current.info)) {
            return log("Ignored useMobileDataOnce: no forced update waits for a network, or the choice is turned off.")
        }
        snapshot = snapshot.copy(mobileDataVersionCode = current.info.versionCode)
        downloadUserInitiated = true
        emit(Effect.EnqueueDownload(current.info, Network.Any, userInitiated = true))
        model = current.copy(stage = Stage.Downloading(stage.progress))
    }

    // ---- Rules ---------------------------------------------------------------------------------

    /** FR-19: forced by the flag, or because the installed version is below the minimum. */
    private fun isForced(info: UpdateInfo): Boolean = info.mandatory || info.minSupportedVersionCode.let { it != null && installed < it }

    private fun isUnsupported(): Boolean = snapshot.unsupportedAtVersionCode == installed

    private fun networkFor(info: UpdateInfo): Network = when {
        snapshot.mobileDataVersionCode == info.versionCode -> Network.Any
        isForced(info) -> settings.forcedDownloadNetwork
        else -> settings.optionalDownloadNetwork
    }

    private fun canUseMobileData(info: UpdateInfo): Boolean = settings.allowMobileDataOverride &&
        settings.forcedDownloadNetwork != Network.Any &&
        snapshot.mobileDataVersionCode != info.versionCode

    /**
     * Two descriptions name the same file when version code and hash agree. The URL is left out on
     * purpose: hosts that sign their download URLs change them on every request.
     */
    private fun sameFile(
        a: UpdateInfo,
        b: UpdateInfo,
    ): Boolean = a.versionCode == b.versionCode && a.sha256 == b.sha256

    private fun completeProgress(info: UpdateInfo): DownloadProgress = DownloadProgress(info.size ?: 0, info.size)

    private fun lastProgress(
        stage: Stage,
        info: UpdateInfo,
    ): DownloadProgress = when (stage) {
        is Stage.Downloading -> stage.progress
        is Stage.WaitingForNetwork -> stage.progress
        else -> DownloadProgress(0, info.size)
    }

    // ---- Rendering -----------------------------------------------------------------------------

    private fun render(): UpdateState = when (val current = model) {
        Model.Idle -> UpdateState.Idle

        is Model.Checking -> UpdateState.Checking

        Model.UpToDate -> UpdateState.UpToDate

        is Model.CheckFailed -> UpdateState.Failed(current.reason, null, current.userInitiated)

        is Model.Unsupported -> {
            val info = current.info
            if (info != null && isForced(info)) UpdateState.Required(info, UpdateStep.Unsupported) else UpdateState.Unsupported
        }

        is Model.Working ->
            if (isForced(current.info)) {
                UpdateState.Required(current.info, forcedStep(current))
            } else {
                optionalState(current)
            }
    }

    private fun optionalState(working: Model.Working): UpdateState {
        val info = working.info
        return when (val stage = working.stage) {
            Stage.Offered -> UpdateState.Available(info)
            is Stage.Downloading -> UpdateState.Downloading(info, stage.progress, downloadUserInitiated)
            is Stage.WaitingForNetwork -> UpdateState.WaitingForNetwork(info)
            Stage.Ready -> UpdateState.ReadyToInstall(info)
            Stage.PermissionNeeded -> UpdateState.PermissionNeeded(info)
            Stage.Installing -> UpdateState.Installing(info)
            is Stage.Failed -> UpdateState.Failed(stage.reason, info, stage.userInitiated)
        }
    }

    // FR-20: a forced update has no "ready" screen; ready and offered both wait for the user's tap.
    private fun forcedStep(working: Model.Working): UpdateStep = when (val stage = working.stage) {
        Stage.Offered, Stage.Ready -> UpdateStep.WaitingForUser
        is Stage.Downloading -> UpdateStep.Downloading(stage.progress)
        is Stage.WaitingForNetwork -> UpdateStep.WaitingForNetwork(canUseMobileData(working.info))
        Stage.PermissionNeeded -> UpdateStep.PermissionNeeded
        Stage.Installing -> UpdateStep.Installing
        is Stage.Failed -> UpdateStep.Failed(stage.reason)
    }

    private fun emit(effect: Effect) {
        pending += effect
    }

    private fun log(message: String) {
        settings.logger?.log(message, null)
    }

    private fun name(event: EngineEvent): String = event::class.simpleName ?: "event"

    /** What the engine knows, beyond what the public state shows. */
    private sealed interface Model {
        data object Idle : Model

        /** A check runs while no update is known. [before] is restored if it fails in the background. */
        data class Checking(
            val before: Model,
            val userInitiated: Boolean,
        ) : Model

        data object UpToDate : Model

        data class CheckFailed(
            val reason: FailureReason,
            val userInitiated: Boolean,
        ) : Model

        /** The device cannot install. [info] is kept so a forced update still blocks. */
        data class Unsupported(
            val info: UpdateInfo?,
        ) : Model

        /** A newer version is known and somewhere between offered and installed. */
        data class Working(
            val info: UpdateInfo,
            val stage: Stage,
        ) : Model
    }

    /** Where a known update stands. Rendered as an optional state or as a forced update's step. */
    private sealed interface Stage {
        data object Offered : Stage

        data class Downloading(
            val progress: DownloadProgress,
        ) : Stage

        data class WaitingForNetwork(
            val progress: DownloadProgress,
        ) : Stage

        /** Downloaded and verified. */
        data object Ready : Stage

        data object PermissionNeeded : Stage

        data object Installing : Stage

        data class Failed(
            val reason: FailureReason,
            val userInitiated: Boolean,
        ) : Stage
    }
}
