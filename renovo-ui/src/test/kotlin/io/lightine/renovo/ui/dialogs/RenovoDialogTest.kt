package io.lightine.renovo.ui.dialogs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The frame behind all five dialogs: widths, short windows, and the keys.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class RenovoDialogTest {
    @get:Rule
    val rule = createComposeRule()

    private var dismissed = 0
    private var primary = 0

    private fun show(
        dismissible: Boolean = true,
        inline: Boolean = true,
        lines: Int = 1,
    ) {
        rule.setDialogContent(inline = inline) {
            RenovoDialog(
                onDismissRequest = { dismissed++ },
                dismissible = dismissible,
                onPrimaryAction = { primary++ },
                modifier = Modifier.testTag(DIALOG),
                contentAlignment = Alignment.Start,
                actions = { focus ->
                    DialogTextButton(text = "Other", onClick = {})
                    DialogPrimaryButton(text = "Go", onClick = { primary++ }, modifier = Modifier.focusRequester(focus))
                },
            ) {
                repeat(lines) { Text("Line $it") }
            }
        }
    }

    @Test
    @Config(sdk = [37], qualifiers = "w360dp-h640dp")
    fun aCompactDialogIsTheWindowWidthMinusTheMargins() {
        show()
        rule.onNodeWithTag(DIALOG).assertWidthIsEqualTo(360.dp - 48.dp)
    }

    @Test
    @Config(sdk = [37], qualifiers = "w700dp-h960dp")
    fun aMediumDialogIs440DpWide() {
        show()
        rule.onNodeWithTag(DIALOG).assertWidthIsEqualTo(440.dp)
    }

    @Test
    @Config(sdk = [37], qualifiers = "w1000dp-h640dp")
    fun anExpandedDialogIs560DpWide() {
        show()
        rule.onNodeWithTag(DIALOG).assertWidthIsEqualTo(560.dp)
    }

    @Test
    @Config(sdk = [37], qualifiers = "w640dp-h320dp")
    fun aShortWindowKeepsTheButtonsVisibleAndTheDialogInsideTheWindow() {
        show(lines = 40)
        val bounds = rule.onNodeWithTag(DIALOG).getBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top <= 320.dp)
        rule.onNodeWithText("Go").assertIsDisplayed()
        rule.onNodeWithText("Other").assertIsDisplayed()
        rule.onNodeWithText("Line 0").assertIsDisplayed()
    }

    @Test
    fun escapeDismissesOnceWhenDismissible() {
        show(dismissible = true)
        rule.onNodeWithText("Go").performKeyInput { pressKey(Key.Escape) }
        assertEquals(1, dismissed)
    }

    @Test
    fun escapeDoesNothingWhenNotDismissible() {
        show(dismissible = false)
        rule.onNodeWithText("Go").performKeyInput { pressKey(Key.Escape) }
        assertEquals(0, dismissed)
        assertEquals(0, primary)
    }

    @Test
    fun enterOnThePrimaryButtonActsOnceNotTwice() {
        show()
        rule.onNodeWithText("Go").performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, primary)
    }

    @Test
    fun theKeysAlsoWorkInADialogWindow() {
        show(inline = false)
        rule.onNodeWithText("Go").performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, primary)
        rule.onNodeWithText("Go").performKeyInput { pressKey(Key.Escape) }
        assertEquals(1, dismissed)
    }

    @Test
    fun aNonDismissibleDialogWindowIgnoresEscapeToo() {
        show(dismissible = false, inline = false)
        rule.onNodeWithText("Go").performKeyInput { pressKey(Key.Escape) }
        assertEquals(0, dismissed)
    }

    private companion object {
        const val DIALOG = "dialog"
    }
}
