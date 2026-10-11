package io.lightine.renovo.ui.dialogs

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import io.lightine.renovo.ReleaseNotes
import io.lightine.renovo.ui.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class UpdateAvailableDialogTest {
    @get:Rule
    val rule = createComposeRule()

    private var later = 0
    private var update = 0

    private fun show(
        releaseNotes: ReleaseNotes? = ReleaseNotes("- Sync is faster"),
        sizeText: String? = "24 MB",
        keyboardAttached: Boolean = false,
    ) {
        rule.setDialogContent(keyboardAttached = keyboardAttached) {
            UpdateAvailableDialog(
                info = sampleInfo(releaseNotes),
                sizeText = sizeText,
                onLater = { later++ },
                onUpdate = { update++ },
            )
        }
    }

    @Test
    fun showsHeadlineBodyAndBothButtons() {
        show()
        rule.onNodeWithText(str(R.string.renovo_ui_available_headline)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_available_body, "1.8.0")).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_later)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_update)).assertIsDisplayed()
    }

    @Test
    fun showsTheReleaseNotesCardWithTheVersionAndTheSize() {
        show()
        rule.onNodeWithText(str(R.string.renovo_ui_whats_new)).assertIsDisplayed()
        rule.onNodeWithText("Sync is faster").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_version_line, "1.8.0", "24 MB")).assertIsDisplayed()
    }

    @Test
    fun hidesTheReleaseNotesCardWhenThereAreNoNotes() {
        show(releaseNotes = null)
        rule.onNodeWithText(str(R.string.renovo_ui_available_headline)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_whats_new)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.renovo_ui_version_line, "1.8.0", "24 MB")).assertDoesNotExist()
    }

    @Test
    fun omitsTheSizeWhenItIsNotKnown() {
        show(sizeText = null)
        rule.onNodeWithText(str(R.string.renovo_ui_whats_new)).assertIsDisplayed()
        rule.onNodeWithText("24 MB", substring = true).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.renovo_ui_version_line, "1.8.0", ""), substring = true).assertDoesNotExist()
    }

    @Test
    fun laterCallsOnLaterOnly() {
        show()
        rule.onNodeWithText(str(R.string.renovo_ui_later)).performClick()
        assertEquals(1 to 0, later to update)
    }

    @Test
    fun updateCallsOnUpdateOnly() {
        show()
        rule.onNodeWithText(str(R.string.renovo_ui_update)).performClick()
        assertEquals(0 to 1, later to update)
    }

    @Test
    fun thePrimaryButtonHasFocusWhenTheDialogOpens() {
        show()
        rule.onNodeWithText(str(R.string.renovo_ui_update)).assertIsFocused()
    }

    @Test
    fun enterTriggersUpdate() {
        show()
        rule.onNodeWithText(str(R.string.renovo_ui_update)).performKeyInput { pressKey(Key.Enter) }
        assertEquals(0 to 1, later to update)
    }

    @Test
    fun escapeTriggersLater() {
        show()
        rule.onNodeWithText(str(R.string.renovo_ui_update)).performKeyInput { pressKey(Key.Escape) }
        assertEquals(1 to 0, later to update)
    }

    @Test
    fun enterOnAFocusedLaterButtonMeansLater() {
        show()
        // A keyboard user who tabbed to Later has focus there. Enter must then mean Later.
        rule.onNodeWithText(str(R.string.renovo_ui_later)).requestFocus()
        rule.onNodeWithText(str(R.string.renovo_ui_later)).performKeyInput { pressKey(Key.Enter) }
        assertEquals(1 to 0, later to update)
    }

    @Test
    @Config(sdk = [37], qualifiers = "w1000dp-h700dp")
    fun theKeyboardHintShowsOnAnExpandedWindowWithAKeyboard() {
        show(keyboardAttached = true)
        rule.onNodeWithText(str(R.string.renovo_ui_keyboard_hint)).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [37], qualifiers = "w1000dp-h700dp")
    fun theKeyboardHintIsAbsentOnAnExpandedWindowWithoutAKeyboard() {
        show(keyboardAttached = false)
        rule.onNodeWithText(str(R.string.renovo_ui_keyboard_hint)).assertDoesNotExist()
    }

    @Test
    @Config(sdk = [37], qualifiers = "w411dp-h891dp")
    fun theKeyboardHintIsAbsentOnACompactWindowEvenWithAKeyboard() {
        show(keyboardAttached = true)
        rule.onNodeWithText(str(R.string.renovo_ui_keyboard_hint)).assertDoesNotExist()
    }
}
