package io.lightine.renovo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import io.lightine.renovo.Renovo
import io.lightine.renovo.UpdateState
import io.lightine.renovo.ui.dialogs.LocalRenovoDialogInline
import io.lightine.renovo.ui.dialogs.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

// Native graphics, because two tests read a pixel of the "Update" button to see which colors the dialog got.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w390dp-h844dp-xhdpi")
class RenovoUpdateHostTest {
    @get:Rule
    val rule = createComposeRule()

    private val hostPrimary = Color(0xFF00695C)
    private val givenPrimary = Color(0xFF8E24AA)

    @Before
    fun startRenovo() {
        DismissedUpdates.process.clear()
        rule.startRenovoWithAvailableUpdate()
    }

    private fun updateButtonColor(): Color {
        val image = rule.onNodeWithText(str(R.string.renovo_ui_update)).captureToImage()
        // Near the left end and in the middle of the button, where the rounded button is plain container color.
        return image.toPixelMap()[BUTTON_SAMPLE_X, image.height / 2]
    }

    private fun assertSameColor(
        expected: Color,
        actual: Color,
    ) {
        val close = listOf(expected.red to actual.red, expected.green to actual.green, expected.blue to actual.blue)
            .all { (a, b) -> abs(a - b) < COLOR_TOLERANCE }
        assertTrue("Expected $expected but was $actual", close)
    }

    // The dialog is drawn in place, so it can be captured together with the screen.
    @Composable
    private fun InPlace(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalRenovoDialogInline provides true, content = content)
    }

    @Test
    fun rendersTheContentAndTheDialogOverIt() {
        rule.setContent {
            RenovoUpdateHost { Text("host content") }
        }
        rule.waitForIdle()
        rule.onNodeWithText("host content").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_available_headline)).assertIsDisplayed()
    }

    @Test
    fun theDialogDrawnOverTheContentFollowsTheStateOfRenovo() {
        rule.setContent {
            RenovoUpdateHost { Text("host content") }
        }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.renovo_ui_later)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("host content").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_available_headline)).assertDoesNotExist()
        assertTrue(Renovo.state.value is UpdateState.Available)
    }

    @Test
    fun inheritsThePrimaryColorOfTheHostThemeWhenNoColorSchemesAreGiven() {
        rule.setContent {
            InPlace {
                MaterialTheme(colorScheme = lightColorScheme(primary = hostPrimary)) {
                    RenovoUpdateHost(colorSchemes = null) { Text("host content") }
                }
            }
        }
        rule.waitForIdle()
        assertSameColor(hostPrimary, updateButtonColor())
    }

    @Test
    fun usesTheGivenColorSchemesInsteadOfTheHostTheme() {
        val schemes =
            RenovoColorSchemes(
                light = lightColorScheme(primary = givenPrimary),
                dark = darkColorScheme(primary = givenPrimary),
            )
        rule.setContent {
            InPlace {
                MaterialTheme(colorScheme = lightColorScheme(primary = hostPrimary)) {
                    RenovoUpdateHost(colorSchemes = schemes) { Text("host content") }
                }
            }
        }
        rule.waitForIdle()
        assertSameColor(givenPrimary, updateButtonColor())
    }

    @Test
    fun usesItsOwnBaselineColorsWithoutAHostTheme() {
        rule.setContent {
            InPlace {
                RenovoUpdateHost { Text("host content") }
            }
        }
        rule.waitForIdle()
        assertSameColor(lightColorScheme().primary, updateButtonColor())
    }

    @Test
    fun theContentFillsTheWindowJustAsItWouldWithoutTheHost() {
        var contentSize = IntSize.Zero
        rule.setContent {
            // In place, so the dialog window does not add a second root.
            InPlace {
                RenovoUpdateHost {
                    Box(Modifier.fillMaxSize().onSizeChanged { contentSize = it })
                }
            }
        }
        rule.waitForIdle()
        assertEquals(rule.onRoot().fetchSemanticsNode().size, contentSize)
    }

    private companion object {
        const val BUTTON_SAMPLE_X = 6
        const val COLOR_TOLERANCE = 2f / 255f
    }
}
