package io.lightine.renovo.ui.dialogs

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import io.lightine.renovo.DownloadProgress
import io.lightine.renovo.FailureReason
import io.lightine.renovo.ReleaseNotes
import io.lightine.renovo.ThemeMode
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.ui.RenovoTheme

// The previews sit in src/debug so they never ship in the release library. Every one is listed in
// light and dark, English and Azerbaijani, and at three widths (see DialogVariants).

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
        releaseNotes = sampleNotes,
    )

private val sampleInfoWithoutNotes =
    UpdateInfo(
        versionCode = 42,
        versionName = "1.8.0",
        apkUrl = "https://example.com/app/app-1.8.0.apk",
    )

/**
 * The stage of every dialog preview: the baseline scheme following the preview's light or dark
 * setting, a stand-in for the host app, and the Material scrim over it.
 *
 * The dialog is drawn in place (see [LocalRenovoDialogInline]) because a preview does not show a
 * second window. [keyboardAttached] pretends a hardware keyboard, for the keyboard hint.
 */
@Composable
internal fun DialogPreviewStage(
    keyboardAttached: Boolean = false,
    content: @Composable () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val withKeyboard =
        remember(configuration, keyboardAttached) {
            Configuration(configuration).apply {
                if (keyboardAttached) keyboard = Configuration.KEYBOARD_QWERTY
            }
        }
    RenovoTheme(themeMode = ThemeMode.System, dynamicColor = false, colorSchemes = null, inheritHostTheme = false) {
        CompositionLocalProvider(
            LocalRenovoDialogInline provides true,
            LocalConfiguration provides withKeyboard,
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                // The scrim role at the Material scrim opacity, over the host app stand-in.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA)))
                content()
            }
        }
    }
}

private const val SCRIM_ALPHA = 0.32f

@DialogVariants
@Composable
internal fun UpdateAvailableDialogPreview() {
    DialogPreviewStage(keyboardAttached = true) {
        UpdateAvailableDialog(info = sampleInfo, sizeText = "24 MB", onLater = {}, onUpdate = {})
    }
}

@DialogVariants
@Composable
internal fun UpdateAvailableDialogWithoutNotesPreview() {
    DialogPreviewStage {
        UpdateAvailableDialog(info = sampleInfoWithoutNotes, sizeText = null, onLater = {}, onUpdate = {})
    }
}

@DialogVariants
@Composable
internal fun DownloadingDialogPreview() {
    DialogPreviewStage {
        DownloadingDialog(progress = DownloadProgress(15_000_000, 24_000_000), onHide = {})
    }
}

@DialogVariants
@Composable
internal fun DownloadingDialogUnknownSizePreview() {
    DialogPreviewStage {
        DownloadingDialog(progress = DownloadProgress(15_000_000, null), onHide = {})
    }
}

@DialogVariants
@Composable
internal fun ReadyToInstallDialogPreview() {
    DialogPreviewStage {
        ReadyToInstallDialog(info = sampleInfo, onNotNow = {}, onInstall = {})
    }
}

@DialogVariants
@Composable
internal fun PermissionDialogPreview() {
    DialogPreviewStage {
        PermissionDialog(legacyStepText = false, android17Step = false, dismissible = true, onCancel = {}, onOpenSettings = {})
    }
}

@DialogVariants
@Composable
internal fun PermissionDialogWithoutCancelPreview() {
    DialogPreviewStage {
        PermissionDialog(legacyStepText = false, android17Step = false, dismissible = false, onCancel = {}, onOpenSettings = {})
    }
}

@DialogVariants
@Composable
internal fun PermissionDialogLegacyStepPreview() {
    DialogPreviewStage {
        PermissionDialog(legacyStepText = true, android17Step = false, dismissible = true, onCancel = {}, onOpenSettings = {})
    }
}

@DialogVariants
@Composable
internal fun PermissionDialogAndroid17StepPreview() {
    DialogPreviewStage {
        PermissionDialog(legacyStepText = false, android17Step = true, dismissible = true, onCancel = {}, onOpenSettings = {})
    }
}

@DialogVariants
@Composable
internal fun UpdateFailedDialogVerificationPreview() {
    DialogPreviewStage {
        UpdateFailedDialog(reason = FailureReason.HashMismatch, onClose = {}, onTryAgain = {})
    }
}

@DialogVariants
@Composable
internal fun UpdateFailedDialogGeneralPreview() {
    DialogPreviewStage {
        UpdateFailedDialog(reason = FailureReason.Network, onClose = {}, onTryAgain = {})
    }
}
