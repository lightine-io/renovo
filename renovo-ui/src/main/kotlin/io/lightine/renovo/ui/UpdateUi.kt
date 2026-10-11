package io.lightine.renovo.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.lightine.renovo.DownloadProgress
import io.lightine.renovo.FailureReason
import io.lightine.renovo.Network
import io.lightine.renovo.Renovo
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateState
import io.lightine.renovo.UpdateStep
import io.lightine.renovo.ui.dialogs.DownloadingDialog
import io.lightine.renovo.ui.dialogs.PermissionDialog
import io.lightine.renovo.ui.dialogs.ReadyToInstallDialog
import io.lightine.renovo.ui.dialogs.UpdateAvailableDialog
import io.lightine.renovo.ui.dialogs.UpdateFailedDialog
import io.lightine.renovo.ui.forced.ForcedUpdateScreen
import io.lightine.renovo.ui.forced.ForcedUpdateWindow

/**
 * What the update UI can ask `Renovo` to do. A parameter instead of a direct call, so a test can
 * pass its own actions and record the calls without starting `Renovo`.
 */
internal class UpdateUiActions(
    val startDownload: () -> Unit,
    val checkNow: () -> Unit,
    val installIfReady: () -> Unit,
    val useMobileDataOnce: () -> Unit,
) {
    internal companion object {
        /** The actions of the real `Renovo`. Each call is forwarded when the button is pressed, not before. */
        val Default = UpdateUiActions(
            startDownload = { Renovo.startDownload() },
            checkNow = { Renovo.checkNow() },
            installIfReady = { Renovo.installIfReady() },
            useMobileDataOnce = { Renovo.useMobileDataOnce() },
        )
    }
}

/**
 * Draws the screen that belongs to [state], or nothing (UI specification part 2).
 *
 * | State | Screen |
 * |---|---|
 * | `Available` | the update available dialog, until "Later" |
 * | `Downloading`, user started | the downloading dialog, until "Hide" |
 * | `ReadyToInstall`, [canInstallNow] true | the ready to install dialog, until "Not now" |
 * | `PermissionNeeded` | the permission dialog, until "Cancel" |
 * | `Failed`, user started | the failed dialog, until "Close" |
 * | `Required` | the forced update screen, which cannot be dismissed |
 * | everything else | nothing |
 *
 * "Later", "Not now", and "Cancel" last until the next app start, so [dismissed] keeps them for
 * the process. "Hide" and "Close" last for their download or failure, and [dismissed] keeps them
 * for the process too, so a second activity does not show them again. Every choice is also saved
 * with the screen, so a rotation, a resize, or a fold never brings back what the user put away
 * (technical specification section 8). The saved values sit in the branch of their state, so they
 * are dropped when the state moves on: a second download or a second failure shows its dialog
 * again. "Hide" is the exception: it is kept while the same download waits for a network and goes
 * on, and dropped when it ends.
 *
 * All the branches stay in the composition while [active] is false, and only the screens are left
 * out. A host that is paused, because another window took over or a system screen is in front,
 * therefore keeps what the user chose.
 *
 * ```
 * UpdateUi(state = state, canInstallNow = { true }, actions = UpdateUiActions.Default)
 * ```
 *
 * @param canInstallNow The host app's setting, only for `ReadyToInstall`. Asked when the dialog is about to
 *   show and again whenever the activity resumes, so a change to true shows the dialog on the next resume.
 * @param active False when another host is the one to draw (see [HostRegistry]).
 * @param isOnline Whether the device has a network, for the forced screen. Null asks the system.
 * @param forcedDownloadNetwork The host app's `forcedDownloadNetwork`, for the waiting note of the forced screen.
 * @param installedVersionName The version name of the installed app, for the forced screen. Null asks the system.
 * @param legacyPermissionStep True on Android 7.0 and 7.1, where the permission setting is device-wide.
 * @param dismissed The choices that last until the next app start.
 */
@Composable
internal fun UpdateUi(
    state: UpdateState,
    canInstallNow: () -> Boolean,
    actions: UpdateUiActions,
    active: Boolean = true,
    isOnline: Boolean? = null,
    forcedDownloadNetwork: Network = Network.UnmeteredOnly,
    installedVersionName: String? = null,
    legacyPermissionStep: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O,
    dismissed: DismissedUpdates = DismissedUpdates.process,
) {
    // "Hide" lasts for the whole download. A download can wait for a network halfway and then go
    // on, so the choice is kept above the branches, under the version of the running download. It
    // is dropped when no download runs, so the next download shows its dialog again.
    var downloadHidden by rememberSaveable(state.downloadVersionCode()) { mutableStateOf(false) }
    // "Hide" and "Close" are also kept for the process, until their download or failure is over.
    SideEffect { dismissed.follow(state) }

    // No else branch: a new state does not compile until someone decides what it shows.
    when (state) {
        is UpdateState.Available -> AvailableUi(state.info, active, actions, dismissed)

        is UpdateState.Downloading -> DownloadingUi(
            progress = state.progress,
            userInitiated = state.userInitiated,
            active = active,
            hidden = downloadHidden || dismissed.isDismissed(Dismissal.Hidden, state.info.versionCode),
            onHide = {
                downloadHidden = true
                dismissed.dismiss(Dismissal.Hidden, state.info.versionCode)
            },
        )

        is UpdateState.ReadyToInstall -> ReadyToInstallUi(state.info, canInstallNow, active, actions, dismissed)

        is UpdateState.PermissionNeeded -> PermissionUi(state.info, legacyPermissionStep, active, actions, dismissed)

        is UpdateState.Failed -> FailedUi(state.reason, state.info, state.userInitiated, active, actions, dismissed)

        is UpdateState.Required -> if (active) {
            RequiredUi(state.info, state.step, isOnline, forcedDownloadNetwork, installedVersionName, legacyPermissionStep, actions)
        }

        // The optional flow shows nothing in these. A forced update never leaves Required while it
        // checks, downloads, waits, or installs; the forced screen draws those steps itself.
        UpdateState.Idle,
        UpdateState.Checking,
        UpdateState.UpToDate,
        UpdateState.Unsupported,
        is UpdateState.Installing,
        is UpdateState.WaitingForNetwork,
        -> Unit
    }
}

@Composable
private fun AvailableUi(
    info: UpdateInfo,
    active: Boolean,
    actions: UpdateUiActions,
    dismissed: DismissedUpdates,
) {
    val code = info.versionCode
    var later by rememberSaveable(code) { mutableStateOf(false) }
    if (!active || later || dismissed.isDismissed(Dismissal.Later, code)) return
    UpdateAvailableDialog(
        info = info,
        sizeText = rememberSizeText(info.size),
        onLater = {
            later = true
            dismissed.dismiss(Dismissal.Later, code)
        },
        onUpdate = actions.startDownload,
    )
}

@Composable
private fun DownloadingUi(
    progress: DownloadProgress,
    userInitiated: Boolean,
    active: Boolean,
    hidden: Boolean,
    onHide: () -> Unit,
) {
    // A download that no user action started shows nothing (UI specification 2.2).
    if (!userInitiated || !active || hidden) return
    DownloadingDialog(progress = progress, onHide = onHide)
}

/** The version of the download that runs or waits for a network, or null when no download is under way. */
internal fun UpdateState.downloadVersionCode(): Long? = when (this) {
    is UpdateState.Downloading -> info.versionCode
    is UpdateState.WaitingForNetwork -> info.versionCode
    else -> null
}

@Composable
private fun ReadyToInstallUi(
    info: UpdateInfo,
    canInstallNow: () -> Boolean,
    active: Boolean,
    actions: UpdateUiActions,
    dismissed: DismissedUpdates,
) {
    val code = info.versionCode
    var notNow by rememberSaveable(code) { mutableStateOf(false) }
    // The host's answer can change while the activity is in the background, and nothing in Compose
    // would notice. Reading the lifecycle state recomposes this branch at every resume, so the host
    // is asked again then. A stopped activity shows no dialog, so the host is not asked while stopped.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val started = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    // The host's check goes last, so it runs only when the dialog could be drawn.
    if (!active || !started || notNow || dismissed.isDismissed(Dismissal.NotNow, code) || !canInstallNow()) return
    ReadyToInstallDialog(
        info = info,
        onNotNow = {
            notNow = true
            dismissed.dismiss(Dismissal.NotNow, code)
        },
        onInstall = actions.installIfReady,
    )
}

@Composable
private fun PermissionUi(
    info: UpdateInfo,
    legacyPermissionStep: Boolean,
    active: Boolean,
    actions: UpdateUiActions,
    dismissed: DismissedUpdates,
) {
    val code = info.versionCode
    var cancelled by rememberSaveable(code) { mutableStateOf(false) }
    if (!active || cancelled || dismissed.isDismissed(Dismissal.PermissionCancelled, code)) return
    PermissionDialog(
        legacyStepText = legacyPermissionStep,
        dismissible = true,
        onCancel = {
            cancelled = true
            dismissed.dismiss(Dismissal.PermissionCancelled, code)
        },
        // In this state the engine answers installIfReady by opening the system setting.
        onOpenSettings = actions.installIfReady,
    )
}

@Composable
private fun FailedUi(
    reason: FailureReason,
    info: UpdateInfo?,
    userInitiated: Boolean,
    active: Boolean,
    actions: UpdateUiActions,
    dismissed: DismissedUpdates,
) {
    val code = info?.versionCode ?: DismissedUpdates.NO_VERSION
    // A different failure is a new message, so it opens the dialog again even in the same branch.
    var closed by rememberSaveable(reason, info?.versionCode) { mutableStateOf(false) }
    // A failed background check shows nothing (UI specification 2.6).
    if (!userInitiated || !active || closed || dismissed.isDismissed(Dismissal.Closed, code)) return
    UpdateFailedDialog(
        reason = reason,
        onClose = {
            closed = true
            dismissed.dismiss(Dismissal.Closed, code)
        },
        // Without version information the failure came from a check, so the retry is a check.
        onTryAgain = if (info != null) actions.startDownload else actions.checkNow,
    )
}

@Composable
private fun RequiredUi(
    info: UpdateInfo,
    step: UpdateStep,
    isOnline: Boolean?,
    forcedDownloadNetwork: Network,
    installedVersionName: String?,
    legacyPermissionStep: Boolean,
    actions: UpdateUiActions,
) {
    // Its own window, so neither the keyboard focus nor a screen reader can reach the host app underneath.
    ForcedUpdateWindow {
        ForcedUpdateScreen(
            info = info,
            installedVersionName = installedVersionName ?: rememberInstalledVersionName(),
            step = step,
            isOnline = isOnline ?: rememberIsOnline(),
            forcedDownloadNetwork = forcedDownloadNetwork,
            legacyPermissionStep = legacyPermissionStep,
            onUpdateNow = actions.startDownload,
            onTryAgain = actions.startDownload,
            onOpenSettings = actions.installIfReady,
            onUseMobileData = actions.useMobileDataOnce,
        )
    }
}

// formatShortFileSize turns bytes into "24 MB" in the device's language.
@Composable
private fun rememberSizeText(sizeBytes: Long?): String? {
    val context = LocalContext.current
    return remember(sizeBytes, context) { sizeBytes?.let { Formatter.formatShortFileSize(context, it) } }
}

@Composable
private fun rememberInstalledVersionName(): String {
    val context = LocalContext.current
    return remember(context) { installedVersionName(context) }
}

// The int-flags overload is not deprecated (the PackageInfoFlags overload only adds room for more
// flags), so no branch by API level is needed to read the name.
private fun installedVersionName(context: Context): String {
    val name =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    // The screen needs some text for the installed version even when the app declares no name.
    return name ?: UNKNOWN_VERSION_NAME
}

private const val UNKNOWN_VERSION_NAME = "-"
