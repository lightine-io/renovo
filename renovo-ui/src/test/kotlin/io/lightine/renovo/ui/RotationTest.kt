package io.lightine.renovo.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import io.lightine.renovo.DownloadProgress
import io.lightine.renovo.ReleaseNotes
import io.lightine.renovo.ThemeMode
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateState
import io.lightine.renovo.ui.dialogs.LocalRenovoDialogInline
import io.lightine.renovo.ui.dialogs.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a rotation does to the dialogs (AC-7, UI specification part 3): the saved screen state is
 * restored, as when the system recreates the activity after a configuration change. The host tests
 * already cover the dismissals, so these two cover what only the screens hold: the scroll position
 * of the release notes, and a hidden download dialog that stays hidden while the progress goes on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w390dp-h844dp-xhdpi")
class RotationTest {
    @get:Rule
    val rule = createComposeRule()

    private val recorded = RecordedActions()

    private var state by mutableStateOf<UpdateState>(UpdateState.Idle)

    // Not the holder of the process, and emptied on every rotation, so only the saved state of the
    // screens can explain what is kept.
    private val dismissed = DismissedUpdates()

    private val tester = StateRestorationTester(rule)

    // A dialog window keeps its saved state in the saved state registry of the activity, which
    // StateRestorationTester does not emulate. [inline] draws the dialog in the same window, where the
    // tester does save and restore it. ActivityRecreationTest covers the real dialog window.
    private fun show(
        initial: UpdateState,
        inline: Boolean,
    ) {
        state = initial
        tester.setContent {
            RenovoTheme(themeMode = ThemeMode.Light, dynamicColor = false, colorSchemes = null, inheritHostTheme = false) {
                CompositionLocalProvider(LocalRenovoDialogInline provides inline) {
                    UpdateUi(
                        state = state,
                        canInstallNow = { true },
                        actions = recorded.actions,
                        isOnline = true,
                        installedVersionName = "1.6.2",
                        legacyPermissionStep = false,
                        dismissed = dismissed,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun rotate() {
        // The holder of the dismissals is emptied too, so a restored value cannot come from there.
        dismissed.clear()
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
    }

    @Test
    fun theReleaseNotesKeepTheirScrollPositionWhenTheScreenIsRestored() {
        show(UpdateState.Available(longNotesInfo()), inline = true)
        assertEquals(0f, rule.notesScrollPosition(), 0f)

        rule.onNode(scrolledNotes).performTouchInput { swipeUp() }
        rule.waitForIdle()
        val before = rule.notesScrollPosition()
        assertTrue("The notes should have scrolled, but the position is $before", before > 0f)

        rotate()

        assertEquals(before, rule.notesScrollPosition(), 0f)
        assertEquals(setOf(Screen.UpdateAvailable), rule.screensShown())
        assertEquals(emptyList<String>(), recorded.calls)
    }

    @Test
    fun aHiddenDownloadDialogStaysHiddenAfterTheRestoreWhileTheProgressGoesOn() {
        val info = uiInfo()
        show(UpdateState.Downloading(info, someProgress(), userInitiated = true), inline = false)
        rule.onNodeWithText(str(R.string.renovo_ui_hide)).performClick()
        rule.waitForIdle()
        assertEquals(emptySet<Screen>(), rule.screensShown())

        rotate()
        assertEquals(emptySet<Screen>(), rule.screensShown())

        // The download goes on in the engine. More progress is no reason to bring the dialog back.
        rule.runOnIdle { state = UpdateState.Downloading(info, DownloadProgress(bytesDownloaded = 12_000_000L, totalBytes = 24_000_000L), userInitiated = true) }
        rule.waitForIdle()
        assertEquals(emptySet<Screen>(), rule.screensShown())
        assertEquals(emptyList<String>(), recorded.calls)
    }
}

/**
 * The same check with the real thing: the activity is destroyed and made again, and the dialog is a
 * real dialog window. The saved state of a dialog window goes through the saved state registry of the
 * activity, so this is the test that a rotation keeps the scroll position of a real dialog.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w390dp-h844dp-xhdpi")
class ActivityRecreationTest {
    // The empty rule starts no activity. The test starts one, so it can recreate that one.
    @get:Rule
    val rule = createEmptyComposeRule()

    private val dismissed = DismissedUpdates()
    private val recorded = RecordedActions()

    private val state = UpdateState.Available(longNotesInfo())

    private fun ComponentActivity.showUpdateUi() = setContent {
        RenovoTheme(themeMode = ThemeMode.Light, dynamicColor = false, colorSchemes = null, inheritHostTheme = false) {
            UpdateUi(
                state = state,
                canInstallNow = { true },
                actions = recorded.actions,
                isOnline = true,
                installedVersionName = "1.6.2",
                legacyPermissionStep = false,
                dismissed = dismissed,
            )
        }
    }

    @Test
    fun theReleaseNotesKeepTheirScrollPositionWhenTheActivityIsRecreated() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.showUpdateUi() }
            rule.waitForIdle()
            rule.onNode(scrolledNotes).performTouchInput { swipeUp() }
            rule.waitForIdle()
            val before = rule.notesScrollPosition()
            assertTrue("The notes should have scrolled, but the position is $before", before > 0f)

            scenario.recreate()
            scenario.onActivity { it.showUpdateUi() }
            rule.waitForIdle()

            assertEquals(before, rule.notesScrollPosition(), 0f)
            assertEquals(setOf(Screen.UpdateAvailable), rule.screensShown())
            assertEquals(emptyList<String>(), recorded.calls)
        }
    }
}

// Forty lines are more than the 192 dp that the notes area shows, so the area scrolls.
private fun longNotesInfo() = UpdateInfo(
    versionCode = 42,
    versionName = "1.8.0",
    apkUrl = "https://example.com/app/app-1.8.0.apk",
    releaseNotes = ReleaseNotes((1..40).joinToString("\n") { "- Change number $it" }),
)

// The release notes area is the only vertical scroll area in the dialog with room left to scroll.
private val scrolledNotes =
    SemanticsMatcher("a vertical scroll area with room to scroll") { node: SemanticsNode ->
        node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { it.maxValue() > 0f } == true
    }

private fun ComposeTestRule.notesScrollPosition(): Float = checkNotNull(
    onNode(scrolledNotes).fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange),
).value()
