package io.lightine.renovo.ui.dialogs

import android.content.Context
import android.text.format.Formatter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import io.lightine.renovo.DownloadProgress
import io.lightine.renovo.ui.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class DownloadingDialogTest {
    @get:Rule
    val rule = createComposeRule()

    private var hidden = 0

    private fun show(progress: DownloadProgress) {
        rule.setDialogContent {
            DownloadingDialog(progress = progress, onHide = { hidden++ })
        }
    }

    private fun shortSize(bytes: Long): String = Formatter.formatShortFileSize(ApplicationProvider.getApplicationContext<Context>(), bytes)

    @Test
    fun showsHeadlineBodyAndHide() {
        show(DownloadProgress(15_000_000, 24_000_000))
        rule.onNodeWithText(str(R.string.renovo_ui_downloading_headline)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_downloading_body)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_hide)).assertIsDisplayed()
    }

    @Test
    fun showsThePercentAtTheStartAndTheSizesAtTheEnd() {
        show(DownloadProgress(15_000_000, 24_000_000))
        // 15 of 24 is 62.5 percent, which is shown rounded down, as the indicator announces it.
        rule.onNodeWithText("62%").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.renovo_ui_download_size, shortSize(15_000_000), shortSize(24_000_000)))
            .assertIsDisplayed()
    }

    @Test
    fun theIndicatorCarriesTheValue() {
        show(DownloadProgress(15_000_000, 24_000_000))
        val info =
            rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                .fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        assertEquals(15_000_000f / 24_000_000f, info?.current)
    }

    @Test
    fun anUnknownSizeShowsAnIndeterminateIndicatorWithoutPercentOrSize() {
        show(DownloadProgress(15_000_000, null))
        val info =
            rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                .fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        assertEquals(ProgressBarRangeInfo.Indeterminate, info)
        rule.onNodeWithText("%", substring = true).assertDoesNotExist()
        rule.onNodeWithText("MB", substring = true).assertDoesNotExist()
    }

    @Test
    fun aFinishedDownloadShowsOneHundredPercent() {
        show(DownloadProgress(24_000_000, 24_000_000))
        rule.onNodeWithText("100%").assertIsDisplayed()
    }

    @Test
    fun hideCallsOnHide() {
        show(DownloadProgress(1, 10))
        rule.onNodeWithText(str(R.string.renovo_ui_hide)).performClick()
        assertEquals(1, hidden)
    }

    @Test
    fun hideHasFocusAndEnterAndEscapeBothHide() {
        show(DownloadProgress(1, 10))
        rule.onNodeWithText(str(R.string.renovo_ui_hide)).assertIsFocused()
        rule.onNodeWithText(str(R.string.renovo_ui_hide)).performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, hidden)
        rule.onNodeWithText(str(R.string.renovo_ui_hide)).performKeyInput { pressKey(Key.Escape) }
        assertEquals(2, hidden)
    }
}
