package io.lightine.renovo.ui.dialogs

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import io.lightine.renovo.FailureReason
import io.lightine.renovo.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class UpdateFailedDialogTest {
    @get:Rule
    val rule = createComposeRule()

    private var close = 0
    private var tryAgain = 0

    private fun show(reason: FailureReason) {
        rule.setDialogContent {
            UpdateFailedDialog(reason = reason, onClose = { close++ }, onTryAgain = { tryAgain++ })
        }
    }

    // Written out from UI specification 2.6, not derived from the code under test.
    private val expectedReason =
        mapOf(
            FailureReason.Network to R.string.renovo_ui_failed_reason_network,
            FailureReason.Parse to R.string.renovo_ui_failed_reason_parse,
            FailureReason.HashMismatch to R.string.renovo_ui_failed_reason_hash,
            FailureReason.SignatureMismatch to R.string.renovo_ui_failed_reason_signature,
            FailureReason.NotNewer to R.string.renovo_ui_failed_reason_not_newer,
            FailureReason.Storage to R.string.renovo_ui_failed_reason_storage,
            FailureReason.InstallRejected to R.string.renovo_ui_failed_reason_rejected,
            FailureReason.Cancelled to R.string.renovo_ui_failed_reason_cancelled,
        )

    private val verificationReasons = setOf(FailureReason.HashMismatch, FailureReason.SignatureMismatch, FailureReason.NotNewer)

    @Test
    fun theTableCoversEveryFailureReason() {
        assertEquals(FailureReason.entries.toSet(), expectedReason.keys)
    }

    @Test
    fun showsHeadlineAndBothButtons() {
        show(FailureReason.Network)
        rule.onNodeWithText(str(R.string.renovo_ui_failed_headline)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_close)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_try_again)).assertIsDisplayed()
    }

    // One composition, every reason in turn: a rule sets its content only once.
    private fun forEveryReason(check: (FailureReason) -> Unit) {
        val current = mutableStateOf(FailureReason.entries.first())
        rule.setDialogContent {
            UpdateFailedDialog(reason = current.value, onClose = { close++ }, onTryAgain = { tryAgain++ })
        }
        FailureReason.entries.forEach { reason ->
            current.value = reason
            rule.waitForIdle()
            check(reason)
        }
    }

    @Test
    fun theReasonLineMatchesEveryFailureReason() {
        forEveryReason { reason ->
            val expected = str(R.string.renovo_ui_failed_reason_label) + " " + str(expectedReason.getValue(reason))
            rule.onNodeWithText(expected).assertIsDisplayed()
        }
    }

    @Test
    fun theBodyIsTheVerificationTextOnlyForHashSignatureAndNotNewer() {
        forEveryReason { reason ->
            val shown = if (reason in verificationReasons) R.string.renovo_ui_failed_body_verification else R.string.renovo_ui_failed_body_general
            val hidden = if (reason in verificationReasons) R.string.renovo_ui_failed_body_general else R.string.renovo_ui_failed_body_verification
            rule.onNodeWithText(str(shown)).assertIsDisplayed()
            rule.onNodeWithText(str(hidden)).assertDoesNotExist()
        }
    }

    @Test
    fun theVerificationBodyIsShownForAHashMismatch() {
        show(FailureReason.HashMismatch)
        rule.onNodeWithText(str(R.string.renovo_ui_failed_body_verification)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_failed_body_general)).assertDoesNotExist()
    }

    @Test
    fun theGeneralBodyIsShownForANetworkFailure() {
        show(FailureReason.Network)
        rule.onNodeWithText(str(R.string.renovo_ui_failed_body_general)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_failed_body_verification)).assertDoesNotExist()
    }

    @Test
    fun closeCallsOnCloseOnly() {
        show(FailureReason.Storage)
        rule.onNodeWithText(str(R.string.renovo_ui_close)).performClick()
        assertEquals(1 to 0, close to tryAgain)
    }

    @Test
    fun tryAgainCallsOnTryAgainOnly() {
        show(FailureReason.Storage)
        rule.onNodeWithText(str(R.string.renovo_ui_try_again)).performClick()
        assertEquals(0 to 1, close to tryAgain)
    }

    @Test
    fun theTryAgainButtonHasFocusAndEnterTriesAgain() {
        show(FailureReason.Cancelled)
        rule.onNodeWithText(str(R.string.renovo_ui_try_again)).assertIsFocused()
        rule.onNodeWithText(str(R.string.renovo_ui_try_again)).performKeyInput { pressKey(Key.Enter) }
        assertEquals(0 to 1, close to tryAgain)
    }

    @Test
    fun escapeCloses() {
        show(FailureReason.Cancelled)
        rule.onNodeWithText(str(R.string.renovo_ui_try_again)).performKeyInput { pressKey(Key.Escape) }
        assertEquals(1 to 0, close to tryAgain)
    }

    @Test
    fun theHeadlineIsAHeading() {
        show(FailureReason.Network)
        val config = rule.onNodeWithText(str(R.string.renovo_ui_failed_headline)).fetchSemanticsNode().config
        assertTrue(config.contains(SemanticsProperties.Heading))
    }
}
