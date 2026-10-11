package io.lightine.renovo.sample

import io.lightine.renovo.DownloadProgress
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateState
import io.lightine.renovo.UpdateStep

/**
 * One plain line (or two) per state and forced step. Both activities show it, so a tester reads the
 * same words on the Compose and the Views screen. An exhaustive `when` makes a new library state a
 * compile error here instead of a silent gap in the test harness.
 */
fun UpdateState.describe(): String = when (this) {
    UpdateState.Idle -> "Idle: nothing known yet"

    UpdateState.Checking -> "Checking for an update"

    UpdateState.UpToDate -> "Up to date"

    is UpdateState.Available -> "Available: ${info.describe()}"

    is UpdateState.Downloading ->
        "Downloading: ${info.describe()}\n${progress.describe()}\nStarted by the user: $userInitiated"

    is UpdateState.WaitingForNetwork -> "Waiting for a network: ${info.describe()}"

    is UpdateState.ReadyToInstall -> "Ready to install: ${info.describe()}"

    is UpdateState.PermissionNeeded -> "Permission needed to install: ${info.describe()}"

    is UpdateState.Installing -> "Installing: ${info.describe()}"

    is UpdateState.Failed ->
        "Failed: $reason\n${info?.describe() ?: "no version info"}\nStarted by the user: $userInitiated"

    UpdateState.Unsupported -> "Unsupported: this device does not allow the install"

    is UpdateState.Required -> "Forced update: ${info.describe()}\nStep: ${step.describe()}"
}

private fun UpdateStep.describe(): String = when (this) {
    UpdateStep.WaitingForUser -> "waiting for the user"
    is UpdateStep.Downloading -> "downloading, ${progress.describe()}"
    is UpdateStep.WaitingForNetwork -> "waiting for a network (mobile data can be used: $canUseMobileData)"
    UpdateStep.PermissionNeeded -> "permission needed to install"
    UpdateStep.Installing -> "installing"
    is UpdateStep.Failed -> "failed, $reason"
    UpdateStep.Unsupported -> "unsupported"
}

private fun UpdateInfo.describe(): String = "$versionName (code $versionCode)"

private fun DownloadProgress.describe(): String {
    val total = totalBytes ?: return "$bytesDownloaded bytes (total unknown)"
    val percent = bytesDownloaded * PERCENT / total
    return "$bytesDownloaded of $total bytes ($percent%)"
}

private const val PERCENT = 100
