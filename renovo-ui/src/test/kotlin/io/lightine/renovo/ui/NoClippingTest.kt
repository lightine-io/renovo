package io.lightine.renovo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import io.lightine.renovo.DownloadProgress
import io.lightine.renovo.FailureReason
import io.lightine.renovo.ReleaseNotes
import io.lightine.renovo.ThemeMode
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateState
import io.lightine.renovo.UpdateStep
import io.lightine.renovo.ui.dialogs.selfAndAncestors
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

// Content is never clipped (UI specification part 3) and the UI respects a font scale of 200 percent
// (part 5). Every screen is drawn through UpdateUi, in its real window, with long values, and every
// text is checked to be laid out without overflow and to be reachable in full by scrolling.
// Native graphics, so text is measured with real fonts.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoClippingTest {
    @get:Rule
    val rule = createComposeRule()

    private var state by mutableStateOf<UpdateState>(UpdateState.Idle)

    private fun info(
        size: Long? = 24_000_000L,
        notes: ReleaseNotes? = ReleaseNotes(LONG_NOTES),
    ) = UpdateInfo(
        versionCode = 42,
        versionName = LONG_VERSION,
        apkUrl = "https://example.com/app/app.apk",
        size = size,
        mandatory = true,
        releaseNotes = notes,
    )

    private fun states(): List<UpdateState> {
        val info = info()
        val progress = DownloadProgress(bytesDownloaded = 15_000_000L, totalBytes = 24_000_000L)
        return listOf(
            UpdateState.Available(info),
            UpdateState.Available(info(size = null, notes = null)),
            UpdateState.Downloading(info, progress, userInitiated = true),
            UpdateState.ReadyToInstall(info),
            UpdateState.PermissionNeeded(info),
            UpdateState.Failed(FailureReason.Parse, info, userInitiated = true),
            UpdateState.Required(info, UpdateStep.WaitingForUser),
            UpdateState.Required(info, UpdateStep.Downloading(progress)),
            UpdateState.Required(info, UpdateStep.WaitingForNetwork(canUseMobileData = true)),
            UpdateState.Required(info, UpdateStep.Installing),
            UpdateState.Required(info, UpdateStep.Failed(FailureReason.Parse)),
            UpdateState.Required(info, UpdateStep.PermissionNeeded),
            UpdateState.Required(info, UpdateStep.Unsupported),
        )
    }

    private fun checkEveryState() {
        rule.setContent {
            RenovoTheme(themeMode = ThemeMode.Light, dynamicColor = false, colorSchemes = null, inheritHostTheme = false) {
                UpdateUi(
                    state = state,
                    canInstallNow = { true },
                    actions = RecordedActions().actions,
                    isOnline = false,
                    installedVersionName = LONG_VERSION,
                    legacyPermissionStep = false,
                    dismissed = DismissedUpdates(),
                )
            }
        }
        for (next in states()) {
            rule.runOnIdle { state = next }
            rule.waitForIdle()
            checkNothingIsClipped(next)
        }
    }

    private fun checkNothingIsClipped(state: UpdateState) {
        val texts = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("$state draws text", texts.isNotEmpty())
        for (text in texts) {
            val layouts = mutableListOf<TextLayoutResult>()
            text.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            for (layout in layouts) {
                // didOverflowWidth compares with the width the paragraph was offered, which a wrapping
                // text never fills, so the widths of the drawn lines are compared instead.
                val widest = (0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) } ?: 0f
                val cut = layout.didOverflowHeight || layout.multiParagraph.didExceedMaxLines || widest > layout.size.width + 1f
                if (cut) fail("$state: \"${layout.layoutInput.text}\" overflows its box: size ${layout.size}, constraints ${layout.layoutInput.constraints}, multiParagraph ${layout.multiParagraph.height} ${layout.multiParagraph.width}")
            }
            val node = rule.onNode(SemanticsMatcher("node ${text.id}") { it.id == text.id }, useUnmergedTree = true)
            // The release notes scroll inside the scrolling dialog body. Each scrolling area is
            // brought into its outer area first, then the text into the innermost one.
            val scrollers = text.selfAndAncestors().drop(1).filter { SemanticsActions.ScrollBy in it.config }
            for (scroller in scrollers) {
                if (scroller.selfAndAncestors().drop(1).any { SemanticsActions.ScrollBy in it.config }) {
                    rule.onNode(SemanticsMatcher("scroller ${scroller.id}") { it.id == scroller.id }, useUnmergedTree = true).performScrollTo()
                }
            }
            if (scrollers.isNotEmpty()) node.performScrollTo()
            rule.waitForIdle()
            val now = node.fetchSemanticsNode()
            // boundsInRoot is cut by every clipping ancestor; size is not. Equal means fully visible.
            val visible = now.boundsInRoot
            val label = layouts.firstOrNull()?.layoutInput?.text ?: now.id
            if (abs(visible.width - now.size.width) > 1f || abs(visible.height - now.size.height) > 1f) {
                fail("$state: \"$label\" is cut off: ${now.size} shown as $visible")
            }
        }
        val minTarget = 48 * rule.density.density
        for (button in rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            assertTrue("$state: a button is ${button.size.height} px tall", button.size.height + 1 >= minTarget)
        }
    }

    @Test
    @Config(sdk = [37], qualifiers = "w844dp-h390dp-land-xhdpi")
    fun phoneLandscape() = checkEveryState()

    @Test
    @Config(sdk = [37], qualifiers = "w390dp-h844dp-xhdpi", fontScale = 2f)
    fun phonePortraitAtTwiceTheFontSize() = checkEveryState()

    @Test
    @Config(sdk = [37], qualifiers = "az-w360dp-h640dp-xhdpi", fontScale = 2f)
    fun smallPhoneInAzerbaijaniAtTwiceTheFontSize() = checkEveryState()

    @Test
    @Config(sdk = [37], qualifiers = "w844dp-h390dp-land-xhdpi", fontScale = 2f)
    fun phoneLandscapeAtTwiceTheFontSize() = checkEveryState()

    @Test
    @Config(sdk = [37], qualifiers = "w700dp-h960dp-xhdpi", fontScale = 2f)
    fun tabletAtTwiceTheFontSize() = checkEveryState()

    private companion object {
        // 20 characters, the longest version name the review asks for.
        const val LONG_VERSION = "10.20.300-beta.4+567"
        val LONG_NOTES = (1..30).joinToString("\n") { "- Change number $it with a **long** description that wraps" }
    }
}
