package io.lightine.renovo.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Checks that need Compose: the window width, the decorative tiles, and the links of the release notes.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ComposeFoundationsTest {
    @get:Rule
    val rule = createComposeRule()

    private fun widthUnder(): WindowWidth {
        var width: WindowWidth? = null
        rule.setContent { width = rememberWindowWidth() }
        rule.waitForIdle()
        return checkNotNull(width)
    }

    @Test
    @Config(sdk = [37], qualifiers = "w411dp-h891dp")
    fun aPhoneWindowIsCompact() {
        assertEquals(WindowWidth.Compact, widthUnder())
    }

    @Test
    @Config(sdk = [37], qualifiers = "w700dp-h1000dp")
    fun aTabletPortraitWindowIsMedium() {
        assertEquals(WindowWidth.Medium, widthUnder())
    }

    @Test
    @Config(sdk = [37], qualifiers = "w1280dp-h800dp")
    fun aTabletLandscapeWindowIsExpanded() {
        assertEquals(WindowWidth.Expanded, widthUnder())
    }

    @Test
    fun theIconTileIsHiddenFromAccessibility() {
        rule.setContent { IconTile(icon = R.drawable.renovo_ic_download, modifier = Modifier.testTag("tile")) }
        val config = rule.onNodeWithTag("tile").fetchSemanticsNode().config
        assertTrue(config.contains(SemanticsProperties.HideFromAccessibility))
    }

    @Test
    fun theForcedUpdateShapeIsHiddenFromAccessibility() {
        rule.setContent { ForcedUpdateShape(size = 120.dp, modifier = Modifier.testTag("shape")) }
        val config = rule.onNodeWithTag("shape").fetchSemanticsNode().config
        assertTrue(config.contains(SemanticsProperties.HideFromAccessibility))
    }

    @Test
    fun theStepTileShowsItsNumber() {
        rule.setContent { StepTile(number = 2) }
        rule.onNodeWithText("2").assertIsDisplayed()
    }

    @Test
    fun aLinkOpensThroughTheUriHandler() {
        val opened = mutableListOf<String>()
        val handler =
            object : UriHandler {
                override fun openUri(uri: String) {
                    opened += uri
                }
            }
        rule.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                ReleaseNotesText(markdown = "[read more](https://example.com/notes)")
            }
        }
        rule.onNodeWithText("read more").performClick()
        assertEquals(listOf("https://example.com/notes"), opened)
    }

    @Test
    fun anHttpLinkIsShownAsTypedAndHasNoLink() {
        rule.setContent { ReleaseNotesText(markdown = "[read more](http://example.com)") }
        val node = rule.onNodeWithText("[read more](http://example.com)").fetchSemanticsNode()
        assertNotNull(node)
        val text = node.config.getOrNull(SemanticsProperties.Text)?.single()
        assertEquals(0, text?.getLinkAnnotations(0, text.length)?.size)
    }
}
