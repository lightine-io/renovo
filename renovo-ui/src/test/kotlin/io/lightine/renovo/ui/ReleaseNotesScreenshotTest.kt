package io.lightine.renovo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.lightine.renovo.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot spike: proves that Material 3, Compose, Roborazzi, and Robolectric render together.
 * The full screenshot grid of the screens comes with the screens.
 *
 * Record with `./gradlew :renovo-ui:recordRoborazziDebug`, check with `verifyRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w411dp-h700dp-xxhdpi")
class ReleaseNotesScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun releaseNotesWithEveryElementInTheBaselineScheme() {
        rule.setContent {
            // The baseline scheme, not dynamic color, so the image is the same on every machine.
            RenovoTheme(ThemeMode.Light, dynamicColor = false, colorSchemes = null, inheritHostTheme = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.width(360.dp).padding(16.dp)) {
                        ReleaseNotesText(markdown = SCREENSHOT_NOTES)
                    }
                }
            }
        }
        rule.onRoot().captureRoboImage("src/test/screenshots/release_notes_baseline_light.png")
    }

    private companion object {
        const val SCREENSHOT_NOTES =
            "- Sinxronizasiya **sürətləndirildi**\n" +
                "- Xətalar aradan qaldırıldı\n" +
                "\n" +
                "*Italic* text and a [link](https://example.com/changes).\n" +
                "- A long bullet that runs over more than one line, so the second line stays under the first " +
                "line's text"
    }
}
