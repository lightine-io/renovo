package io.lightine.renovo.ui.forced

import android.content.Context
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.core.app.ApplicationProvider
import io.lightine.renovo.Network
import io.lightine.renovo.ReleaseNotes
import io.lightine.renovo.ThemeMode
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateStep
import io.lightine.renovo.ui.RenovoTheme

/** Counts the calls of the four callbacks of the forced update screen. */
internal class ForcedCalls {
    var updateNow = 0
    var tryAgain = 0
    var openSettings = 0
    var useMobileData = 0

    val total: Int get() = updateNow + tryAgain + openSettings + useMobileData
}

internal const val INSTALLED_VERSION = "1.6.2"
internal const val REQUIRED_VERSION = "1.8.0"
internal const val NOTE_TEXT = "Sync is faster"
internal const val SIZE_BYTES = 24_000_000L

internal fun forcedInfo(
    releaseNotes: ReleaseNotes? = ReleaseNotes("- $NOTE_TEXT"),
    size: Long? = SIZE_BYTES,
) = UpdateInfo(
    versionCode = 42,
    versionName = REQUIRED_VERSION,
    apkUrl = "https://example.com/app/app-1.8.0.apk",
    size = size,
    mandatory = true,
    releaseNotes = releaseNotes,
)

/** The size as the screen formats it, so a test never repeats a platform-specific "24 MB". */
internal fun formattedSize(bytes: Long): String = Formatter.formatShortFileSize(ApplicationProvider.getApplicationContext<Context>(), bytes)

/** The forced update screen under the baseline light theme. */
internal fun ComposeContentTestRule.setForcedContent(
    calls: ForcedCalls = ForcedCalls(),
    step: UpdateStep,
    info: UpdateInfo = forcedInfo(),
    isOnline: Boolean = true,
    legacyPermissionStep: Boolean = false,
    forcedDownloadNetwork: Network = Network.UnmeteredOnly,
) = setForcedContentOf(calls, { step }, info, isOnline, legacyPermissionStep, forcedDownloadNetwork)

/**
 * Like [setForcedContent], but the step is read on every composition, so a test can change it from
 * a state and check several steps in one test.
 */
internal fun ComposeContentTestRule.setForcedContentOf(
    calls: ForcedCalls = ForcedCalls(),
    step: () -> UpdateStep,
    info: UpdateInfo = forcedInfo(),
    isOnline: Boolean = true,
    legacyPermissionStep: Boolean = false,
    forcedDownloadNetwork: Network = Network.UnmeteredOnly,
) {
    setContent {
        ForcedTheme {
            ForcedUpdateScreen(
                info = info,
                installedVersionName = INSTALLED_VERSION,
                step = step(),
                isOnline = isOnline,
                forcedDownloadNetwork = forcedDownloadNetwork,
                legacyPermissionStep = legacyPermissionStep,
                onUpdateNow = { calls.updateNow++ },
                onTryAgain = { calls.tryAgain++ },
                onOpenSettings = { calls.openSettings++ },
                onUseMobileData = { calls.useMobileData++ },
            )
        }
    }
}

/** The baseline scheme, not dynamic color, so a result is the same on every machine. */
@Composable
internal fun ForcedTheme(content: @Composable () -> Unit) {
    RenovoTheme(themeMode = ThemeMode.Light, dynamicColor = false, colorSchemes = null, inheritHostTheme = false, content = content)
}
