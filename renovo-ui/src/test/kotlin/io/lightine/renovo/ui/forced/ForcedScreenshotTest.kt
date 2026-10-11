package io.lightine.renovo.ui.forced

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.lightine.renovo.DownloadProgress
import io.lightine.renovo.FailureReason
import io.lightine.renovo.ReleaseNotes
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of the seven states of the forced update screen in the light theme and in English, at
 * one width per subclass.
 *
 * File names: `forced_<state>_<width>_<theme>_<language>.png`, so the full matrix of UI
 * specification part 4 can add themes and languages without renaming these.
 *
 * Record with `./gradlew :renovo-ui:recordRoborazziDebug`, check with `verifyRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ForcedScreenshotTest(
    private val widthName: String,
) {
    @get:Rule
    val rule = createComposeRule()

    private var rootView: View? = null

    private val info =
        UpdateInfo(
            versionCode = 42,
            versionName = "1.8.0",
            apkUrl = "https://example.com/app/app-1.8.0.apk",
            size = 24_000_000,
            mandatory = true,
            releaseNotes = ReleaseNotes("- Sync is **faster**\n- Fixed a crash when the connection drops"),
        )

    private fun capture(
        name: String,
        step: UpdateStep,
        isOnline: Boolean = true,
    ) {
        rule.setContent {
            ForcedTheme { Stage(step = step, isOnline = isOnline) }
        }
        rule.waitForIdle()
        // A fresh frame for the pixel copy: without it the copy sometimes reads an empty window.
        rule.runOnUiThread { checkNotNull(rootView).invalidate() }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("src/test/screenshots/forced_${name}_${widthName}_light_en.png")
    }

    @Composable
    private fun Stage(
        step: UpdateStep,
        isOnline: Boolean,
    ) {
        rootView = LocalView.current
        ForcedUpdateScreen(
            info = info,
            installedVersionName = "1.6.2",
            step = step,
            isOnline = isOnline,
            legacyPermissionStep = false,
            onUpdateNow = {},
            onTryAgain = {},
            onOpenSettings = {},
            onUseMobileData = {},
        )
    }

    // Offline, as in design images 05, 11, and 13, so the offline note is part of the picture.
    @Test
    fun waitingForUser() = capture("waiting_for_user", UpdateStep.WaitingForUser, isOnline = false)

    @Test
    fun downloading() = capture("downloading", UpdateStep.Downloading(DownloadProgress(15_000_000, 24_000_000)))

    @Test
    fun waitingForNetwork() = capture("waiting_for_network", UpdateStep.WaitingForNetwork(canUseMobileData = true))

    @Test
    fun permissionNeeded() = capture("permission_needed", UpdateStep.PermissionNeeded)

    @Test
    fun installing() = capture("installing", UpdateStep.Installing)

    @Test
    fun failed() = capture("failed", UpdateStep.Failed(FailureReason.HashMismatch))

    @Test
    fun unsupported() = capture("unsupported", UpdateStep.Unsupported)
}

// The window sizes of the design images: 390 x 844 dp phone, 700 x 960 dp tablet in portrait, and a
// wide landscape window.
@Config(sdk = [37], qualifiers = "w390dp-h844dp-xhdpi")
class CompactForcedScreenshotTest : ForcedScreenshotTest(widthName = "compact")

@Config(sdk = [37], qualifiers = "w700dp-h960dp-xhdpi")
class MediumForcedScreenshotTest : ForcedScreenshotTest(widthName = "medium")

@Config(sdk = [37], qualifiers = "w1000dp-h640dp-xhdpi")
class ExpandedForcedScreenshotTest : ForcedScreenshotTest(widthName = "expanded")
