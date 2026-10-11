package io.lightine.renovo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.espresso.Espresso
import io.lightine.renovo.FailureReason
import io.lightine.renovo.ThemeMode
import io.lightine.renovo.UpdateState
import io.lightine.renovo.UpdateStep
import io.lightine.renovo.ui.RecordedActions.Companion.INSTALL_IF_READY
import io.lightine.renovo.ui.RecordedActions.Companion.START_DOWNLOAD
import io.lightine.renovo.ui.dialogs.str
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The whole update flow with a keyboard only (AC-8, technical specification section 8): Tab moves
 * the focus, Enter does the primary action, Escape does the dismiss action, and the forced update
 * screen ignores Escape and Back.
 *
 * Nothing here taps or clicks, and no test asks a node for focus. The dialogs are real dialog
 * windows, so the keys travel the way they do on a device. Every key goes to whatever has focus,
 * which is the point: the focus must already sit where a keyboard user expects it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w390dp-h844dp-xhdpi")
class KeyboardFlowTest {
    @get:Rule
    val rule = createComposeRule()

    private val recorded = RecordedActions()
    private val dismissed = DismissedUpdates()

    private var state by mutableStateOf<UpdateState>(UpdateState.Idle)

    private fun show(initial: UpdateState) {
        state = initial
        rule.setContent {
            RenovoTheme(themeMode = ThemeMode.Light, dynamicColor = false, colorSchemes = null, inheritHostTheme = false) {
                UpdateUi(
                    state = state,
                    canInstallNow = { true },
                    actions = recorded.actions,
                    isOnline = true,
                    installedVersionName = "1.6.2",
                    legacyPermissionStep = false,
                    android17PermissionStep = false,
                    dismissed = dismissed,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun change(next: UpdateState) {
        rule.runOnIdle { state = next }
        rule.waitForIdle()
    }

    /**
     * Presses [key] in the window of [screen]. The headline only says which window gets the key. The
     * window passes it on to the focused element.
     */
    private fun press(
        screen: Screen,
        key: Key,
    ) {
        rule.onAllNodesWithText(str(screen.headlineRes)).onFirst().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun assertFocused(res: Int) = rule.onNodeWithText(str(res)).assertIsFocused()

    // ---- The flow, state by state -------------------------------------------------------------

    @Test
    fun enterOnTheAvailableDialogStartsTheUpdate() {
        show(UpdateState.Available(uiInfo()))
        assertFocused(R.string.renovo_ui_update)
        press(Screen.UpdateAvailable, Key.Enter)
        assertEquals(listOf(START_DOWNLOAD), recorded.calls)
    }

    @Test
    fun escapeOnTheAvailableDialogPutsItOffWithoutAnAction() {
        show(UpdateState.Available(uiInfo()))
        press(Screen.UpdateAvailable, Key.Escape)
        assertEquals(emptySet<Screen>(), rule.screensShown())
        assertEquals(emptyList<String>(), recorded.calls)
    }

    @Test
    fun escapeOnTheDownloadDialogHidesItAndTheDownloadGoesOn() {
        show(UpdateState.Downloading(uiInfo(), someProgress(), userInitiated = true))
        press(Screen.Downloading, Key.Escape)
        assertEquals(emptySet<Screen>(), rule.screensShown())
        assertEquals(emptyList<String>(), recorded.calls)
    }

    @Test
    fun enterOnTheReadyDialogInstalls() {
        show(UpdateState.ReadyToInstall(uiInfo()))
        assertFocused(R.string.renovo_ui_install)
        press(Screen.ReadyToInstall, Key.Enter)
        assertEquals(listOf(INSTALL_IF_READY), recorded.calls)
    }

    @Test
    fun escapeOnTheReadyDialogPutsItOffWithoutAnAction() {
        show(UpdateState.ReadyToInstall(uiInfo()))
        press(Screen.ReadyToInstall, Key.Escape)
        assertEquals(emptySet<Screen>(), rule.screensShown())
        assertEquals(emptyList<String>(), recorded.calls)
    }

    @Test
    fun tabThenEnterOnThePermissionDialogCancelsWithoutAnAction() {
        show(UpdateState.PermissionNeeded(uiInfo()))
        press(Screen.Permission, Key.Tab)
        assertFocused(R.string.renovo_ui_cancel)
        press(Screen.Permission, Key.Enter)
        assertEquals(emptySet<Screen>(), rule.screensShown())
        assertEquals(emptyList<String>(), recorded.calls)
    }

    @Test
    fun enterOnOpenSettingsAsksForTheInstallBecauseTheEngineOpensTheSetting() {
        show(UpdateState.PermissionNeeded(uiInfo()))
        assertFocused(R.string.renovo_ui_open_settings)
        press(Screen.Permission, Key.Enter)
        assertEquals(listOf(INSTALL_IF_READY), recorded.calls)
    }

    @Test
    fun escapeOnThePermissionDialogCancelsWithoutAnAction() {
        show(UpdateState.PermissionNeeded(uiInfo()))
        press(Screen.Permission, Key.Escape)
        assertEquals(emptySet<Screen>(), rule.screensShown())
        assertEquals(emptyList<String>(), recorded.calls)
    }

    @Test
    fun enterOnTheFailedDialogTriesAgain() {
        show(UpdateState.Failed(FailureReason.Network, uiInfo(), userInitiated = true))
        assertFocused(R.string.renovo_ui_try_again)
        press(Screen.UpdateFailed, Key.Enter)
        assertEquals(listOf(START_DOWNLOAD), recorded.calls)
    }

    @Test
    fun escapeOnTheFailedDialogClosesItWithoutAnAction() {
        show(UpdateState.Failed(FailureReason.Network, uiInfo(), userInitiated = true))
        press(Screen.UpdateFailed, Key.Escape)
        assertEquals(emptySet<Screen>(), rule.screensShown())
        assertEquals(emptyList<String>(), recorded.calls)
    }

    // ---- Tab order ----------------------------------------------------------------------------

    // The focus starts on the primary button. One Tab reaches the dismiss button, and one more
    // comes back to the primary button, so a keyboard user can reach both from the start.
    private fun assertTabOrder(
        screen: Screen,
        primary: Int,
        dismiss: Int,
    ) {
        assertFocused(primary)
        press(screen, Key.Tab)
        assertFocused(dismiss)
        press(screen, Key.Tab)
        assertFocused(primary)
        assertEquals(emptyList<String>(), recorded.calls)
    }

    @Test
    fun tabOrderOfTheAvailableDialog() {
        show(UpdateState.Available(uiInfo()))
        assertTabOrder(Screen.UpdateAvailable, R.string.renovo_ui_update, R.string.renovo_ui_later)
    }

    @Test
    fun tabOrderOfTheReadyDialog() {
        show(UpdateState.ReadyToInstall(uiInfo()))
        assertTabOrder(Screen.ReadyToInstall, R.string.renovo_ui_install, R.string.renovo_ui_not_now)
    }

    @Test
    fun tabOrderOfThePermissionDialog() {
        show(UpdateState.PermissionNeeded(uiInfo()))
        assertTabOrder(Screen.Permission, R.string.renovo_ui_open_settings, R.string.renovo_ui_cancel)
    }

    @Test
    fun tabOrderOfTheFailedDialog() {
        show(UpdateState.Failed(FailureReason.Network, uiInfo(), userInitiated = true))
        assertTabOrder(Screen.UpdateFailed, R.string.renovo_ui_try_again, R.string.renovo_ui_close)
    }

    // "Hide" is the only button, so it is both the primary and the dismiss button.
    @Test
    fun theDownloadDialogKeepsTheFocusOnHideWhenTabIsPressed() {
        show(UpdateState.Downloading(uiInfo(), someProgress(), userInitiated = true))
        assertFocused(R.string.renovo_ui_hide)
        press(Screen.Downloading, Key.Tab)
        assertFocused(R.string.renovo_ui_hide)
    }

    // ---- The forced update screen -------------------------------------------------------------

    @Test
    fun escapeAndBackChangeNothingOnTheForcedScreenInEveryStep() {
        val info = uiInfo(mandatory = true)
        val steps: List<UpdateStep> =
            listOf(
                UpdateStep.WaitingForUser,
                UpdateStep.Downloading(someProgress()),
                UpdateStep.WaitingForNetwork(canUseMobileData = true),
                UpdateStep.PermissionNeeded,
                UpdateStep.Installing,
                UpdateStep.Failed(FailureReason.Network),
                UpdateStep.Unsupported,
            )
        show(UpdateState.Idle)
        for (step in steps) {
            change(UpdateState.Required(info, step))
            press(Screen.Forced, Key.Escape)
            Espresso.pressBack()
            rule.waitForIdle()
            assertEquals("Screens in $step", setOf(Screen.Forced), rule.screensShown())
        }
        assertEquals(emptyList<String>(), recorded.calls)
    }
}
