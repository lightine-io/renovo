package io.lightine.renovo.ui.forced

import androidx.compose.runtime.Composable
import io.lightine.renovo.DownloadProgress
import io.lightine.renovo.FailureReason
import io.lightine.renovo.Network
import io.lightine.renovo.ReleaseNotes
import io.lightine.renovo.ThemeMode
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateStep
import io.lightine.renovo.ui.RenovoTheme

// The previews sit in src/debug so they never ship in the release library. Every one is listed in
// light and dark, English and Azerbaijani, and at three widths (see ForcedVariants).

private val sampleNotes =
    ReleaseNotes.of(
        "en" to "- Sync is **faster**\n- Fixed a crash when the connection drops",
        "az" to "- Sinxronizasiya **sürətləndirildi**\n- Xətalar aradan qaldırıldı",
    )

private val sampleInfo =
    UpdateInfo(
        versionCode = 42,
        versionName = "1.8.0",
        apkUrl = "https://example.com/app/app-1.8.0.apk",
        size = 24_000_000,
        mandatory = true,
        releaseNotes = sampleNotes,
    )

private val sampleInfoWithoutSize =
    UpdateInfo(
        versionCode = 42,
        versionName = "1.8.0",
        apkUrl = "https://example.com/app/app-1.8.0.apk",
        mandatory = true,
        releaseNotes = sampleNotes,
    )

// The baseline scheme following the preview's light or dark setting, as in the dialog previews.
@Composable
private fun ForcedStage(
    info: UpdateInfo = sampleInfo,
    step: UpdateStep,
    isOnline: Boolean = true,
    forcedDownloadNetwork: Network = Network.UnmeteredOnly,
    legacyPermissionStep: Boolean = false,
    android17PermissionStep: Boolean = false,
) {
    RenovoTheme(themeMode = ThemeMode.System, dynamicColor = false, colorSchemes = null, inheritHostTheme = false) {
        ForcedUpdateScreen(
            info = info,
            installedVersionName = "1.6.2",
            step = step,
            isOnline = isOnline,
            forcedDownloadNetwork = forcedDownloadNetwork,
            legacyPermissionStep = legacyPermissionStep,
            android17PermissionStep = android17PermissionStep,
            onUpdateNow = {},
            onTryAgain = {},
            onOpenSettings = {},
            onUseMobileData = {},
        )
    }
}

@ForcedVariants
@Composable
internal fun ForcedWaitingForUserPreview() {
    ForcedStage(step = UpdateStep.WaitingForUser)
}

@ForcedVariants
@Composable
internal fun ForcedWaitingForUserOfflinePreview() {
    ForcedStage(step = UpdateStep.WaitingForUser, isOnline = false)
}

@ForcedVariants
@Composable
internal fun ForcedDownloadingPreview() {
    ForcedStage(step = UpdateStep.Downloading(DownloadProgress(15_000_000, 24_000_000)))
}

@ForcedVariants
@Composable
internal fun ForcedDownloadingUnknownSizePreview() {
    ForcedStage(info = sampleInfoWithoutSize, step = UpdateStep.Downloading(DownloadProgress(15_000_000, null)))
}

@ForcedVariants
@Composable
internal fun ForcedWaitingForNetworkWithMobileDataPreview() {
    ForcedStage(step = UpdateStep.WaitingForNetwork(canUseMobileData = true))
}

@ForcedVariants
@Composable
internal fun ForcedWaitingForNetworkWithMobileDataUnknownSizePreview() {
    ForcedStage(info = sampleInfoWithoutSize, step = UpdateStep.WaitingForNetwork(canUseMobileData = true))
}

@ForcedVariants
@Composable
internal fun ForcedWaitingForNetworkWithoutMobileDataPreview() {
    ForcedStage(step = UpdateStep.WaitingForNetwork(canUseMobileData = false))
}

@ForcedVariants
@Composable
internal fun ForcedPermissionNeededPreview() {
    ForcedStage(step = UpdateStep.PermissionNeeded)
}

@ForcedVariants
@Composable
internal fun ForcedPermissionNeededLegacyPreview() {
    ForcedStage(step = UpdateStep.PermissionNeeded, legacyPermissionStep = true)
}

@ForcedVariants
@Composable
internal fun ForcedPermissionNeededAndroid17Preview() {
    ForcedStage(step = UpdateStep.PermissionNeeded, android17PermissionStep = true)
}

@ForcedVariants
@Composable
internal fun ForcedInstallingPreview() {
    ForcedStage(step = UpdateStep.Installing)
}

@ForcedVariants
@Composable
internal fun ForcedFailedPreview() {
    ForcedStage(step = UpdateStep.Failed(FailureReason.HashMismatch))
}

@ForcedVariants
@Composable
internal fun ForcedUnsupportedPreview() {
    ForcedStage(step = UpdateStep.Unsupported)
}
