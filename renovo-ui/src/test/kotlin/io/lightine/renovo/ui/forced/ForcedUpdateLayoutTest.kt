package io.lightine.renovo.ui.forced

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.lightine.renovo.Network
import io.lightine.renovo.UpdateStep
import io.lightine.renovo.ui.R
import io.lightine.renovo.ui.dialogs.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The sizes of UI specification parts 1 and 3, measured on the laid-out screen at 2 px per dp.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ForcedUpdateLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    private fun Dp.px(): Float = value * DENSITY

    private fun boundsOf(
        text: String,
        unmerged: Boolean = false,
    ): Rect = rule.onNodeWithText(text, useUnmergedTree = unmerged).fetchSemanticsNode().boundsInRoot

    private fun assertDp(
        expected: Dp,
        actualPx: Float,
        message: String,
    ) = assertEquals(message, expected.px(), actualPx, TOLERANCE_PX)

    @Test
    @Config(sdk = [37], qualifiers = "w390dp-h844dp-xhdpi")
    fun compactUsesOneColumnWith24DpPaddingAndA56DpFullWidthButton() {
        rule.setForcedContent(step = UpdateStep.WaitingForUser)
        val headline = boundsOf(str(R.string.renovo_ui_forced_headline))
        assertDp(24.dp, headline.left, "headline starts at the 24 dp padding")
        assertDp(342.dp, headline.width, "column is the window width minus 24 dp each side")
        val button = boundsOf(str(R.string.renovo_ui_update_now))
        assertDp(56.dp, button.height, "compact button height")
        assertDp(342.dp, button.width, "compact button is full width")
    }

    @Test
    @Config(sdk = [37], qualifiers = "w700dp-h960dp-xhdpi")
    fun mediumUsesOneCenteredColumnOfAtMost420DpAndA64DpButton() {
        rule.setForcedContent(step = UpdateStep.WaitingForUser)
        val headline = boundsOf(str(R.string.renovo_ui_forced_headline))
        assertDp(420.dp, headline.width, "content column width")
        assertDp(140.dp, headline.left, "column is centered in 700 dp")
        val button = boundsOf(str(R.string.renovo_ui_update_now))
        assertDp(64.dp, button.height, "medium button height")
        assertDp(420.dp, button.width, "medium button fills the column")
    }

    @Test
    @Config(sdk = [37], qualifiers = "w1000dp-h640dp-xhdpi")
    fun expandedUsesAGroupWithA480DpTextColumnAndAButtonSizedByItsLabel() {
        rule.setForcedContent(step = UpdateStep.WaitingForUser)
        val headline = boundsOf(str(R.string.renovo_ui_forced_headline))
        assertDp(480.dp, headline.width, "text column width")
        // Shape (240) + gap (96) + text (480) = 816 dp, centered in 1000 dp: the text starts at 92 + 336.
        assertDp(428.dp, headline.left, "text column sits on the end side of the centered group")
        assertDp(92.dp, 1000.dp.px() - headline.right, "the group is centered: the end margin equals the start margin")

        val button = boundsOf(str(R.string.renovo_ui_update_now))
        val label = boundsOf(str(R.string.renovo_ui_update_now), unmerged = true)
        assertDp(56.dp, button.height, "expanded button height")
        assertDp(80.dp, button.width - label.width, "label plus 40 dp padding on each side")
        assertDp(0.dp, button.left - headline.left, "the button starts at the start of the text column")
    }

    @Test
    @Config(sdk = [37], qualifiers = "w390dp-h844dp-xhdpi")
    fun theOutlinedMobileDataButtonFollowsTheSameSizing() {
        rule.setForcedContent(step = UpdateStep.WaitingForNetwork(canUseMobileData = true))
        val button = boundsOf(str(R.string.renovo_ui_forced_mobile_data_button, formattedSize(SIZE_BYTES)))
        assertDp(56.dp, button.height, "outlined button height")
        assertDp(342.dp, button.width, "outlined button is full width")
    }

    @Test
    @Config(sdk = [37], qualifiers = "w390dp-h300dp-xhdpi")
    fun theScrollPositionSurvivesARestoreOfTheSavedState() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            ForcedTheme {
                ForcedUpdateScreen(
                    info = forcedInfo(),
                    installedVersionName = INSTALLED_VERSION,
                    step = UpdateStep.WaitingForNetwork(canUseMobileData = true),
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
        val button = str(R.string.renovo_ui_forced_mobile_data_button, formattedSize(SIZE_BYTES))
        rule.onNodeWithText(button).performScrollTo()
        val before = scrollValue()
        assertTrue("The window is short, so the screen must have scrolled", before > 0f)

        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(before, scrollValue(), 0f)
    }

    private fun scrollValue(): Float {
        val node = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNode()
        return node.config[SemanticsProperties.VerticalScrollAxisRange].value()
    }

    private companion object {
        const val DENSITY = 2f
        const val TOLERANCE_PX = 2f
    }
}
