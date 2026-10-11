package io.lightine.renovo.ui

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import io.lightine.renovo.DownloadProgress
import io.lightine.renovo.ReleaseNotes
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.ui.dialogs.str

/** The screens the update UI can draw. Each has one headline that no other screen shares. */
internal enum class Screen(
    val headlineRes: Int,
) {
    UpdateAvailable(R.string.renovo_ui_available_headline),
    Downloading(R.string.renovo_ui_downloading_headline),
    ReadyToInstall(R.string.renovo_ui_ready_headline),
    Permission(R.string.renovo_ui_permission_headline),
    UpdateFailed(R.string.renovo_ui_failed_headline),
    Forced(R.string.renovo_ui_forced_headline),
}

/**
 * The screens that are on the screen right now, found by their headline text. The forced screen
 * shows "Downloading update" as a progress label too, so the downloading dialog is told apart by
 * its "Hide" button.
 */
internal fun ComposeTestRule.screensShown(): Set<Screen> = Screen.entries
    .filter { screen ->
        when (screen) {
            Screen.Downloading -> hasNodes(screen.headlineRes) && hasNodes(R.string.renovo_ui_hide)
            else -> hasNodes(screen.headlineRes)
        }
    }.toSet()

private fun ComposeTestRule.hasNodes(res: Int): Boolean = onAllNodesWithText(str(res)).fetchSemanticsNodes().isNotEmpty()

/** The calls that the update UI made, in order. */
internal class RecordedActions {
    val calls = mutableListOf<String>()

    val actions =
        UpdateUiActions(
            startDownload = { calls += START_DOWNLOAD },
            checkNow = { calls += CHECK_NOW },
            installIfReady = { calls += INSTALL_IF_READY },
            useMobileDataOnce = { calls += USE_MOBILE_DATA_ONCE },
        )

    companion object {
        const val START_DOWNLOAD = "startDownload"
        const val CHECK_NOW = "checkNow"
        const val INSTALL_IF_READY = "installIfReady"
        const val USE_MOBILE_DATA_ONCE = "useMobileDataOnce"
    }
}

internal fun uiInfo(
    versionCode: Long = 42,
    mandatory: Boolean = false,
    size: Long? = 24_000_000L,
) = UpdateInfo(
    versionCode = versionCode,
    versionName = "1.8.$versionCode",
    apkUrl = "https://example.com/app/app-1.8.$versionCode.apk",
    size = size,
    mandatory = mandatory,
    releaseNotes = ReleaseNotes("- Sync is faster"),
)

internal fun someProgress() = DownloadProgress(bytesDownloaded = 6_000_000L, totalBytes = 24_000_000L)
