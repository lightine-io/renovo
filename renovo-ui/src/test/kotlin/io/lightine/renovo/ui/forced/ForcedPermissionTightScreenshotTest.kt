package io.lightine.renovo.ui.forced

import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.captureRoboImage
import io.lightine.renovo.Network
import io.lightine.renovo.UpdateStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The tight variant of the forced permission state (UI specification part 4, state 11): a 411 x 731
 * dp phone loses about 72 dp to its bars, which leaves a 411 x 659 dp window. The coverage matrix
 * has only the 390 x 844 dp phone, which keeps the full layout, so this shows the short one.
 *
 * Record with `./gradlew :renovo-ui:recordRoborazziDebug`, check with `verifyRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class ForcedPermissionTightScreenshotTest {
    // Like the coverage matrix: the empty rule starts no activity, so the qualifiers reach the one launched below.
    @get:Rule
    val rule = createEmptyComposeRule()

    private var rootView: View? = null

    @Test
    fun theShortCompactPermissionStateInEnglish() = capture("en-w411dp-h659dp-xhdpi", "forced_permission_needed_tight_en")

    @Test
    fun theShortCompactPermissionStateInAzerbaijani() = capture("az-w411dp-h659dp-xhdpi", "forced_permission_needed_tight_az")

    private fun capture(
        qualifiers: String,
        name: String,
    ) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { Stage() } }
            rule.waitForIdle()
            // A fresh frame for the pixel copy: without it the copy sometimes reads an empty window.
            rule.runOnUiThread { checkNotNull(rootView).invalidate() }
            rule.waitForIdle()
            rule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
        }
    }

    @Composable
    private fun Stage() {
        rootView = LocalView.current
        ForcedTheme {
            ForcedUpdateScreen(
                info = forcedInfo(),
                installedVersionName = INSTALLED_VERSION,
                step = UpdateStep.PermissionNeeded,
                isOnline = true,
                forcedDownloadNetwork = Network.UnmeteredOnly,
                legacyPermissionStep = false,
                onUpdateNow = {},
                onTryAgain = {},
                onOpenSettings = {},
                onUseMobileData = {},
            )
        }
    }
}
