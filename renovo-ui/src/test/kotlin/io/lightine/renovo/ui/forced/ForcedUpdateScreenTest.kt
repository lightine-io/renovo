package io.lightine.renovo.ui.forced

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.test.espresso.Espresso
import io.lightine.renovo.DownloadProgress
import io.lightine.renovo.FailureReason
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateStep
import io.lightine.renovo.ui.R
import io.lightine.renovo.ui.dialogs.selfAndAncestors
import io.lightine.renovo.ui.dialogs.selfAndDescendants
import io.lightine.renovo.ui.dialogs.str
import io.lightine.renovo.ui.reasonRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A phone-sized window tall enough for every state, so "displayed" means "on screen without scrolling".
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w390dp-h844dp-xhdpi")
class ForcedUpdateScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = ForcedCalls()

    private fun show(
        step: UpdateStep,
        info: UpdateInfo = forcedInfo(),
        isOnline: Boolean = true,
        legacyPermissionStep: Boolean = false,
    ) = rule.setForcedContent(calls, step, info, isOnline, legacyPermissionStep)

    private fun assertTextShown(text: String) = rule.onNodeWithText(text).assertIsDisplayed()

    private fun assertTextAbsent(text: String) = rule.onNodeWithText(text).assertDoesNotExist()

    private fun assertNoButtons() = rule.onAllNodes(hasClickAction()).assertCountEquals(0)

    private fun assertNoProgress() = rule
        .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        .assertCountEquals(0)

    private fun assertCommonParts(body: Int = R.string.renovo_ui_forced_body) {
        assertTextShown(str(R.string.renovo_ui_forced_headline))
        assertTextShown(str(body))
        // The installed and required versions, each with its label.
        rule.onNodeWithText("${str(R.string.renovo_ui_forced_installed)} $INSTALLED_VERSION").assertIsDisplayed()
        rule.onNodeWithText("${str(R.string.renovo_ui_forced_required)} $REQUIRED_VERSION").assertIsDisplayed()
    }

    private fun assertOnlyPrimaryTextsOf(vararg keep: Int) {
        val buttonTexts =
            listOf(
                R.string.renovo_ui_update_now,
                R.string.renovo_ui_try_again,
                R.string.renovo_ui_open_settings,
                R.string.renovo_ui_cancel,
            )
        buttonTexts.filter { it !in keep }.forEach { assertTextAbsent(str(it)) }
    }

    // Waiting for the user

    @Test
    fun waitingForUserShowsTheScreenNotesAndUpdateNow() {
        show(UpdateStep.WaitingForUser)
        assertCommonParts()
        assertTextShown(str(R.string.renovo_ui_whats_new))
        assertTextShown(NOTE_TEXT)
        assertTextShown(str(R.string.renovo_ui_update_now))
        assertOnlyPrimaryTextsOf(R.string.renovo_ui_update_now)
        assertTextAbsent(str(R.string.renovo_ui_forced_mobile_data_button, formattedSize(SIZE_BYTES)))
        assertTextAbsent(str(R.string.renovo_ui_forced_installing_label))
        assertTextAbsent(str(R.string.renovo_ui_forced_progress_label))
        assertNoProgress()
    }

    @Test
    fun updateNowCallsOnUpdateNowOnly() {
        show(UpdateStep.WaitingForUser)
        rule.onNodeWithText(str(R.string.renovo_ui_update_now)).performClick()
        assertEquals(1, calls.updateNow)
        assertEquals(1, calls.total)
    }

    @Test
    fun theReleaseNotesCardIsHiddenWithoutNotes() {
        show(UpdateStep.WaitingForUser, info = forcedInfo(releaseNotes = null))
        assertTextAbsent(str(R.string.renovo_ui_whats_new))
        assertTextShown(str(R.string.renovo_ui_update_now))
    }

    @Test
    fun theOfflineNoteAppearsOnlyWhenTheDeviceIsOffline() {
        show(UpdateStep.WaitingForUser, isOnline = false)
        assertTextShown(str(R.string.renovo_ui_forced_offline_note))
    }

    @Test
    fun theOfflineNoteIsAbsentWhenOnline() {
        show(UpdateStep.WaitingForUser, isOnline = true)
        assertTextAbsent(str(R.string.renovo_ui_forced_offline_note))
    }

    @Test
    fun theUpdateNowButtonHasFocusAndEnterPressesIt() {
        show(UpdateStep.WaitingForUser)
        rule.onNodeWithText(str(R.string.renovo_ui_update_now)).assertIsFocused()
        rule.onNodeWithText(str(R.string.renovo_ui_update_now)).performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, calls.updateNow)
    }

    @Test
    fun theHeadlineIsAHeading() {
        show(UpdateStep.WaitingForUser)
        val node = rule.onNodeWithText(str(R.string.renovo_ui_forced_headline)).fetchSemanticsNode()
        assertTrue(node.config.contains(SemanticsProperties.Heading))
    }

    // Downloading

    @Test
    fun downloadingShowsLabelProgressPercentAndSizeAndNoButton() {
        show(UpdateStep.Downloading(DownloadProgress(15_000_000, 24_000_000)))
        assertCommonParts()
        assertTextShown(str(R.string.renovo_ui_forced_progress_label))
        // 15 of 24 is 62.5 percent, rounded down like the indicator's description.
        assertTextShown("62%")
        assertTextShown(str(R.string.renovo_ui_download_size, formattedSize(15_000_000), formattedSize(24_000_000)))
        val range = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNode()
        assertEquals(0.625f, range.config[SemanticsProperties.ProgressBarRangeInfo].current, 0.001f)
        assertNoButtons()
        assertTextAbsent(str(R.string.renovo_ui_whats_new))
        assertTextAbsent(str(R.string.renovo_ui_forced_installing_label))
    }

    @Test
    fun downloadingWithUnknownSizeIsIndeterminateWithoutNumbers() {
        show(UpdateStep.Downloading(DownloadProgress(15_000_000, null)))
        assertTextShown(str(R.string.renovo_ui_forced_progress_label))
        val range = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNode()
        assertEquals(ProgressBarRangeInfo.Indeterminate, range.config[SemanticsProperties.ProgressBarRangeInfo])
        rule.onAllNodes(
            SemanticsMatcher("shows a percent") { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains("%") } == true
            },
        ).assertCountEquals(0)
        rule.onAllNodes(
            SemanticsMatcher("shows a size") { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(" MB") } == true
            },
        ).assertCountEquals(0)
        assertNoButtons()
    }

    // Waiting for a network

    @Test
    fun waitingForNetworkWithMobileDataShowsTheNoteAndTheMobileDataButton() {
        show(UpdateStep.WaitingForNetwork(canUseMobileData = true))
        assertCommonParts()
        assertTextShown(str(R.string.renovo_ui_forced_waiting_note))
        assertTextShown(str(R.string.renovo_ui_forced_mobile_data_button, formattedSize(SIZE_BYTES)))
        assertOnlyPrimaryTextsOf()
        assertTextAbsent(str(R.string.renovo_ui_whats_new))
        assertNoProgress()
    }

    @Test
    fun theMobileDataButtonCallsOnUseMobileDataOnly() {
        show(UpdateStep.WaitingForNetwork(canUseMobileData = true))
        rule.onNodeWithText(str(R.string.renovo_ui_forced_mobile_data_button, formattedSize(SIZE_BYTES))).performClick()
        assertEquals(1, calls.useMobileData)
        assertEquals(1, calls.total)
    }

    @Test
    fun theMobileDataButtonIsAbsentWhenMobileDataIsNotAllowed() {
        show(UpdateStep.WaitingForNetwork(canUseMobileData = false))
        assertTextShown(str(R.string.renovo_ui_forced_waiting_note))
        assertNoButtons()
    }

    @Test
    fun theMobileDataButtonDropsTheParenthesesWhenTheSizeIsUnknown() {
        show(UpdateStep.WaitingForNetwork(canUseMobileData = true), info = forcedInfo(size = null))
        // An exact match: nothing follows "mobile data", so there is no empty "()" and no placeholder.
        assertTextShown(str(R.string.renovo_ui_forced_mobile_data_button_no_size))
        assertTextShown("Download with mobile data")
        rule.onAllNodes(
            SemanticsMatcher("has parentheses") { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains("(") } == true
            },
        ).assertCountEquals(0)
    }

    // Permission needed

    @Test
    fun permissionNeededShowsTheStepsTheSwitchAndOpenSettings() {
        show(UpdateStep.PermissionNeeded)
        assertCommonParts()
        assertTextShown(str(R.string.renovo_ui_permission_body))
        assertTextShown(str(R.string.renovo_ui_permission_step_1))
        assertTextShown(str(R.string.renovo_ui_permission_step_2))
        assertTextShown(str(R.string.renovo_ui_permission_step_3))
        assertTextShown(str(R.string.renovo_ui_permission_switch_label))
        assertTextShown(str(R.string.renovo_ui_open_settings))
        // A forced update cannot be postponed, so there is no "Cancel", and the notes card is replaced.
        assertOnlyPrimaryTextsOf(R.string.renovo_ui_open_settings)
        assertTextAbsent(str(R.string.renovo_ui_whats_new))
        assertTextAbsent(str(R.string.renovo_ui_permission_headline))
    }

    @Test
    fun legacyPermissionStepReplacesStepTwo() {
        show(UpdateStep.PermissionNeeded, legacyPermissionStep = true)
        assertTextShown(str(R.string.renovo_ui_permission_step_2_legacy))
        assertTextAbsent(str(R.string.renovo_ui_permission_step_2))
    }

    @Test
    fun openSettingsCallsOnOpenSettingsOnly() {
        show(UpdateStep.PermissionNeeded)
        rule.onNodeWithText(str(R.string.renovo_ui_open_settings)).performClick()
        assertEquals(1, calls.openSettings)
        assertEquals(1, calls.total)
    }

    @Test
    fun theSwitchIllustrationIsHiddenFromAccessibilityAndTakesNoFocusOrTouch() {
        show(UpdateStep.PermissionNeeded)
        val label = rule.onNodeWithText(str(R.string.renovo_ui_permission_switch_label)).fetchSemanticsNode()
        val card = label.selfAndAncestors().first { it.config.contains(SemanticsProperties.HideFromAccessibility) }
        card.selfAndDescendants().forEach { node ->
            assertFalse("A node in the illustration can be focused", node.config.contains(SemanticsProperties.Focused))
            assertFalse("A node in the illustration can be toggled", node.config.contains(SemanticsProperties.ToggleableState))
            assertFalse("A node in the illustration can be clicked", node.config.contains(SemanticsActions.OnClick))
        }
        assertTrue(card.selfAndDescendants().size > 1)
    }

    // Installing

    @Test
    fun installingShowsTheLabelAndAnIndeterminateIndicatorWithoutNumbersOrButtons() {
        show(UpdateStep.Installing)
        assertCommonParts()
        assertTextShown(str(R.string.renovo_ui_forced_installing_label))
        val range = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNode()
        assertEquals(ProgressBarRangeInfo.Indeterminate, range.config[SemanticsProperties.ProgressBarRangeInfo])
        assertNoButtons()
        assertTextAbsent(str(R.string.renovo_ui_forced_progress_label))
        assertTextAbsent("0%")
    }

    // Failed

    @Test
    fun failedShowsTheReasonAndTryAgain() {
        show(UpdateStep.Failed(FailureReason.HashMismatch))
        assertCommonParts()
        assertTextShown("${str(R.string.renovo_ui_failed_reason_label)} ${str(R.string.renovo_ui_failed_reason_hash)}")
        assertTextShown(str(R.string.renovo_ui_try_again))
        assertOnlyPrimaryTextsOf(R.string.renovo_ui_try_again)
        assertTextAbsent(str(R.string.renovo_ui_whats_new))
    }

    @Test
    fun everyFailureReasonShowsItsOwnText() {
        val step = mutableStateOf<UpdateStep>(UpdateStep.WaitingForUser)
        rule.setForcedContentOf(calls, { step.value })
        FailureReason.entries.forEach { reason ->
            rule.runOnIdle { step.value = UpdateStep.Failed(reason) }
            rule.onNodeWithText("${str(R.string.renovo_ui_failed_reason_label)} ${str(reason.reasonRes())}").assertIsDisplayed()
        }
    }

    @Test
    fun tryAgainCallsOnTryAgainOnly() {
        show(UpdateStep.Failed(FailureReason.Network))
        rule.onNodeWithText(str(R.string.renovo_ui_try_again)).performClick()
        assertEquals(1, calls.tryAgain)
        assertEquals(1, calls.total)
    }

    // Unsupported

    @Test
    fun unsupportedShowsItsBodyInsteadOfTheGeneralOneAndNoButton() {
        show(UpdateStep.Unsupported)
        assertCommonParts(body = R.string.renovo_ui_unsupported_body)
        assertTextAbsent(str(R.string.renovo_ui_forced_body))
        assertTextAbsent(str(R.string.renovo_ui_whats_new))
        assertNoButtons()
        assertNoProgress()
    }

    // Back and Escape

    @Test
    fun backDoesNothing() {
        show(UpdateStep.WaitingForUser)
        Espresso.pressBack()
        rule.waitForIdle()
        assertTextShown(str(R.string.renovo_ui_forced_headline))
        assertTextShown(str(R.string.renovo_ui_update_now))
        assertEquals(0, calls.total)
    }

    @Test
    fun backDoesNothingInEveryState() {
        val step = mutableStateOf<UpdateStep>(UpdateStep.WaitingForUser)
        rule.setForcedContentOf(calls, { step.value })
        listOf(
            UpdateStep.Downloading(DownloadProgress(1, 2)),
            UpdateStep.WaitingForNetwork(true),
            UpdateStep.PermissionNeeded,
            UpdateStep.Installing,
            UpdateStep.Failed(FailureReason.Storage),
            UpdateStep.Unsupported,
        ).forEach { next ->
            rule.runOnIdle { step.value = next }
            Espresso.pressBack()
            rule.waitForIdle()
            rule.onNodeWithText(str(R.string.renovo_ui_forced_headline)).assertIsDisplayed()
        }
        assertEquals(0, calls.total)
    }

    @Test
    fun escapeDoesNothingWhileAButtonHasFocus() {
        show(UpdateStep.WaitingForUser)
        rule.onNodeWithText(str(R.string.renovo_ui_update_now)).performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertTextShown(str(R.string.renovo_ui_forced_headline))
        assertEquals(0, calls.total)
    }

    @Test
    fun escapeDoesNothingOnTheFailedScreen() {
        show(UpdateStep.Failed(FailureReason.Cancelled))
        rule.onNodeWithText(str(R.string.renovo_ui_try_again)).performKeyInput { pressKey(Key.Escape) }
        assertTextShown(str(R.string.renovo_ui_try_again))
        assertEquals(0, calls.total)
    }

    // Fit and scroll

    @Test
    fun thePermissionStateFitsWithoutScrollingOnACompactPhone() {
        show(UpdateStep.PermissionNeeded)
        assertNoScrollLeft()
        assertTextShown(str(R.string.renovo_ui_open_settings))
    }

    @Test
    @Config(sdk = [37], qualifiers = "w700dp-h960dp-xhdpi")
    fun thePermissionStateFitsWithoutScrollingOnAMediumTablet() {
        show(UpdateStep.PermissionNeeded)
        assertNoScrollLeft()
        assertTextShown(str(R.string.renovo_ui_open_settings))
    }

    @Test
    @Config(sdk = [37], qualifiers = "w1000dp-h640dp-xhdpi")
    fun thePermissionStateFitsWithoutScrollingOnAnExpandedWindow() {
        show(UpdateStep.PermissionNeeded)
        assertNoScrollLeft()
        assertTextShown(str(R.string.renovo_ui_open_settings))
    }

    @Test
    @Config(sdk = [37], qualifiers = "w390dp-h300dp-xhdpi")
    fun aShortWindowScrollsSoNothingIsClipped() {
        show(UpdateStep.WaitingForNetwork(canUseMobileData = true))
        val button = str(R.string.renovo_ui_forced_mobile_data_button, formattedSize(SIZE_BYTES))
        rule.onNodeWithText(button).assertIsNotDisplayed()
        rule.onNodeWithText(button).performScrollTo().assertIsDisplayed()
    }

    private fun assertNoScrollLeft() {
        val range = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNode()
        assertEquals(0f, range.config[SemanticsProperties.VerticalScrollAxisRange].maxValue(), 0f)
    }
}
