package io.lightine.renovo.ui.dialogs

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.lightine.renovo.DownloadProgress
import io.lightine.renovo.FailureReason
import io.lightine.renovo.ReleaseNotes
import io.lightine.renovo.UpdateInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of the five dialogs in the light theme and in English, at one width per subclass.
 *
 * File names: `dialog_<name>_<width>_<theme>_<language>.png`, so the full matrix of UI
 * specification part 4 can add themes and languages without renaming these.
 *
 * Record with `./gradlew :renovo-ui:recordRoborazziDebug`, check with `verifyRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class DialogScreenshotTest(
    private val widthName: String,
    private val keyboardAttached: Boolean,
) {
    @get:Rule
    val rule = createComposeRule()

    private var rootView: View? = null

    private val info =
        UpdateInfo(
            versionCode = 42,
            versionName = "1.8.0",
            apkUrl = "https://example.com/app/app-1.8.0.apk",
            releaseNotes = ReleaseNotes("- Sync is **faster**\n- Fixed a crash when the connection drops"),
        )

    private fun capture(
        name: String,
        dialog: @Composable () -> Unit,
    ) {
        rule.setDialogContent(inline = true, keyboardAttached = keyboardAttached) {
            // The baseline scheme, not dynamic color, so the image is the same on every machine.
            rootView = LocalView.current
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                // The Material scrim over the host app, as in the design images.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA)))
                dialog()
            }
        }
        rule.waitForIdle()
        // A fresh frame for the pixel copy: without it the copy sometimes reads an empty window.
        rule.runOnUiThread { checkNotNull(rootView).invalidate() }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("src/test/screenshots/dialog_${name}_${widthName}_light_en.png")
    }

    @Test
    fun updateAvailable() = capture("update_available") {
        UpdateAvailableDialog(info = info, sizeText = "24 MB", onLater = {}, onUpdate = {})
    }

    @Test
    fun downloading() = capture("downloading") {
        DownloadingDialog(progress = DownloadProgress(15_000_000, 24_000_000), onHide = {})
    }

    @Test
    fun readyToInstall() = capture("ready_to_install") {
        ReadyToInstallDialog(info = info, onNotNow = {}, onInstall = {})
    }

    @Test
    fun permission() = capture("permission") {
        PermissionDialog(legacyStepText = false, dismissible = true, onCancel = {}, onOpenSettings = {})
    }

    @Test
    fun updateFailed() = capture("update_failed") {
        UpdateFailedDialog(reason = FailureReason.HashMismatch, onClose = {}, onTryAgain = {})
    }

    private companion object {
        const val SCRIM_ALPHA = 0.32f
    }
}

@Config(sdk = [37], qualifiers = "w360dp-h720dp-xhdpi")
class CompactDialogScreenshotTest : DialogScreenshotTest(widthName = "compact", keyboardAttached = false)

@Config(sdk = [37], qualifiers = "w700dp-h960dp-xhdpi")
class MediumDialogScreenshotTest : DialogScreenshotTest(widthName = "medium", keyboardAttached = false)

// A hardware keyboard is attached here so the update available dialog shows its keyboard hint, as
// in design image 12.
@Config(sdk = [37], qualifiers = "w1000dp-h640dp-xhdpi")
class ExpandedDialogScreenshotTest : DialogScreenshotTest(widthName = "expanded", keyboardAttached = true)
