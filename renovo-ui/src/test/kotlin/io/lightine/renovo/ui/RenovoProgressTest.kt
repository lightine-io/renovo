package io.lightine.renovo.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class RenovoProgressTest {
    @get:Rule
    val rule = createComposeRule()

    private fun config(progress: Float?) = run {
        rule.setContent { RenovoProgress(progress = progress, modifier = Modifier.testTag("progress")) }
        rule.onNodeWithTag("progress").fetchSemanticsNode().config
    }

    @Test
    fun determinateProgressCarriesItsValue() {
        val info = config(0.42f).getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        assertEquals(ProgressBarRangeInfo(0.42f, 0f..1f), info)
    }

    @Test
    fun determinateProgressDescribesItselfInWholePercent() {
        val description = config(0.426f).getOrNull(SemanticsProperties.ContentDescription)
        assertEquals(listOf("Download progress, 42 percent"), description)
    }

    @Test
    fun almostCompleteProgressDoesNotReadAsHundredPercent() {
        val description = config(0.996f).getOrNull(SemanticsProperties.ContentDescription)
        assertEquals(listOf("Download progress, 99 percent"), description)
    }

    @Test
    fun valuesOutsideZeroToOneAreClamped() {
        val info = config(1.7f).getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        assertEquals(ProgressBarRangeInfo(1f, 0f..1f), info)
    }

    @Test
    fun indeterminateProgressCarriesTheIndeterminateRange() {
        val config = config(null)
        assertEquals(ProgressBarRangeInfo.Indeterminate, config.getOrNull(SemanticsProperties.ProgressBarRangeInfo))
        assertNull(config.getOrNull(SemanticsProperties.ContentDescription))
    }

    @Test
    fun percentIsRoundedDown() {
        assertEquals(0, percentOf(0f))
        assertEquals(99, percentOf(0.999f))
        assertEquals(100, percentOf(1f))
    }
}
