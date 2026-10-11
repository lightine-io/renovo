package io.lightine.renovo.ui.dialogs

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import io.lightine.renovo.ui.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ReadyToInstallDialogTest {
    @get:Rule
    val rule = createComposeRule()

    private var notNow = 0
    private var install = 0

    private fun show() {
        rule.setDialogContent {
            ReadyToInstallDialog(info = sampleInfo(), onNotNow = { notNow++ }, onInstall = { install++ })
        }
    }

    @Test
    fun showsHeadlineBodyVerifiedLineAndBothButtons() {
        show()
        rule.onNodeWithText(str(R.string.renovo_ui_ready_headline)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_ready_body, "1.8.0")).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_ready_verified)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_not_now)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_install)).assertIsDisplayed()
    }

    @Test
    fun notNowCallsOnNotNowOnly() {
        show()
        rule.onNodeWithText(str(R.string.renovo_ui_not_now)).performClick()
        assertEquals(1 to 0, notNow to install)
    }

    @Test
    fun installCallsOnInstallOnly() {
        show()
        rule.onNodeWithText(str(R.string.renovo_ui_install)).performClick()
        assertEquals(0 to 1, notNow to install)
    }

    @Test
    fun theInstallButtonHasFocusAndEnterInstalls() {
        show()
        rule.onNodeWithText(str(R.string.renovo_ui_install)).assertIsFocused()
        rule.onNodeWithText(str(R.string.renovo_ui_install)).performKeyInput { pressKey(Key.Enter) }
        assertEquals(0 to 1, notNow to install)
    }

    @Test
    fun escapeMeansNotNow() {
        show()
        rule.onNodeWithText(str(R.string.renovo_ui_install)).performKeyInput { pressKey(Key.Escape) }
        assertEquals(1 to 0, notNow to install)
    }
}
