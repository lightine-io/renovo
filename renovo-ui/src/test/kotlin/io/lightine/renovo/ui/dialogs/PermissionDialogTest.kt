package io.lightine.renovo.ui.dialogs

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import io.lightine.renovo.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class PermissionDialogTest {
    @get:Rule
    val rule = createComposeRule()

    private var cancel = 0
    private var openSettings = 0

    private fun show(
        legacyStepText: Boolean = false,
        dismissible: Boolean = true,
        android17Step: Boolean = false,
    ) {
        rule.setDialogContent {
            PermissionDialog(
                legacyStepText = legacyStepText,
                android17Step = android17Step,
                dismissible = dismissible,
                onCancel = { cancel++ },
                onOpenSettings = { openSettings++ },
            )
        }
    }

    @Test
    fun showsHeadlineBodyThreeStepsAndTheSwitchLabel() {
        show()
        rule.onNodeWithText(str(R.string.renovo_ui_permission_headline)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_permission_body)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_permission_step_1)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_permission_step_2)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_permission_step_3)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_permission_switch_label)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_cancel)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_open_settings)).assertIsDisplayed()
    }

    @Test
    fun theStepsAreNumberedOneToThree() {
        show()
        listOf("1", "2", "3").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun legacyStepTextReplacesStepTwo() {
        show(legacyStepText = true)
        rule.onNodeWithText(str(R.string.renovo_ui_permission_step_2_legacy)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_permission_step_2)).assertDoesNotExist()
    }

    @Test
    fun theCurrentStepTextIsUsedByDefault() {
        show(legacyStepText = false)
        rule.onNodeWithText(str(R.string.renovo_ui_permission_step_2_legacy)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.renovo_ui_permission_step_2_android17)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.renovo_ui_permission_option_label)).assertDoesNotExist()
    }

    @Test
    fun android17StepShowsTheAlwaysAllowChoiceInsteadOfTheSwitch() {
        show(android17Step = true)
        rule.onNodeWithText(str(R.string.renovo_ui_permission_step_2_android17)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_permission_option_label)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_permission_step_2)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.renovo_ui_permission_step_2_legacy)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.renovo_ui_permission_switch_label)).assertDoesNotExist()
    }

    @Test
    fun withoutTheAndroid17StepTheSwitchIsShown() {
        show(android17Step = false)
        rule.onNodeWithText(str(R.string.renovo_ui_permission_step_2)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_permission_switch_label)).assertIsDisplayed()
    }

    @Test
    fun cancelCallsOnCancelOnly() {
        show()
        rule.onNodeWithText(str(R.string.renovo_ui_cancel)).performClick()
        assertEquals(1 to 0, cancel to openSettings)
    }

    @Test
    fun openSettingsCallsOnOpenSettingsOnly() {
        show()
        rule.onNodeWithText(str(R.string.renovo_ui_open_settings)).performClick()
        assertEquals(0 to 1, cancel to openSettings)
    }

    @Test
    fun theOpenSettingsButtonHasFocusAndEnterOpensSettings() {
        show()
        rule.onNodeWithText(str(R.string.renovo_ui_open_settings)).assertIsFocused()
        rule.onNodeWithText(str(R.string.renovo_ui_open_settings)).performKeyInput { pressKey(Key.Enter) }
        assertEquals(0 to 1, cancel to openSettings)
    }

    @Test
    fun escapeCancelsWhenDismissible() {
        show(dismissible = true)
        rule.onNodeWithText(str(R.string.renovo_ui_open_settings)).performKeyInput { pressKey(Key.Escape) }
        assertEquals(1 to 0, cancel to openSettings)
    }

    @Test
    fun cancelIsAbsentAndEscapeDoesNothingWhenNotDismissible() {
        show(dismissible = false)
        rule.onNodeWithText(str(R.string.renovo_ui_cancel)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.renovo_ui_open_settings)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_open_settings)).performKeyInput { pressKey(Key.Escape) }
        assertEquals(0 to 0, cancel to openSettings)
    }

    @Test
    fun theSwitchIllustrationIsHiddenFromAccessibilityAndTakesNoFocusOrTouch() {
        show()
        val label = rule.onNodeWithText(str(R.string.renovo_ui_permission_switch_label)).fetchSemanticsNode()
        val card = label.selfAndAncestors().first { it.config.contains(SemanticsProperties.HideFromAccessibility) }
        card.selfAndDescendants().forEach { node ->
            assertFalse("A node in the illustration can be focused", node.config.contains(SemanticsProperties.Focused))
            assertFalse("A node in the illustration can be toggled", node.config.contains(SemanticsProperties.ToggleableState))
            assertFalse("A node in the illustration can be clicked", node.config.contains(SemanticsActions.OnClick))
        }
        // The card holds the label and the drawn switch, nothing else.
        assertTrue(card.selfAndDescendants().size > 1)
    }

    @Test
    fun theOptionIllustrationIsHiddenFromAccessibilityAndTakesNoFocusOrTouch() {
        show(android17Step = true)
        val label = rule.onNodeWithText(str(R.string.renovo_ui_permission_option_label)).fetchSemanticsNode()
        val card = label.selfAndAncestors().first { it.config.contains(SemanticsProperties.HideFromAccessibility) }
        card.selfAndDescendants().forEach { node ->
            assertFalse("A node in the illustration can be focused", node.config.contains(SemanticsProperties.Focused))
            assertFalse("A node in the illustration can be selected", node.config.contains(SemanticsProperties.Selected))
            assertFalse("A node in the illustration can be clicked", node.config.contains(SemanticsActions.OnClick))
        }
        // The card holds the label and the drawn radio button, nothing else.
        assertTrue(card.selfAndDescendants().size > 1)
    }
}
