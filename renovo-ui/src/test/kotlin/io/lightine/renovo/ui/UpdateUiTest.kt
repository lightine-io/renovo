package io.lightine.renovo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.lightine.renovo.FailureReason
import io.lightine.renovo.ThemeMode
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateState
import io.lightine.renovo.UpdateStep
import io.lightine.renovo.ui.RecordedActions.Companion.CHECK_NOW
import io.lightine.renovo.ui.RecordedActions.Companion.INSTALL_IF_READY
import io.lightine.renovo.ui.RecordedActions.Companion.START_DOWNLOAD
import io.lightine.renovo.ui.RecordedActions.Companion.USE_MOBILE_DATA_ONCE
import io.lightine.renovo.ui.dialogs.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A stand-in for the activity, resumed from the start, with a lifecycle the test moves by hand. */
private class TestActivityLifecycle : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle get() = registry

    fun moveTo(state: Lifecycle.State) {
        registry.currentState = state
    }
}

// A phone-sized window tall enough for every screen, so a button is on screen without scrolling.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w390dp-h844dp-xhdpi")
class UpdateUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val recorded = RecordedActions()
    private val dismissed = DismissedUpdates()

    private var state by mutableStateOf<UpdateState>(UpdateState.Idle)
    private var canInstall by mutableStateOf(true)
    private var active by mutableStateOf(true)
    private var generation by mutableIntStateOf(0)

    private val content: @Composable () -> Unit = {
        RenovoTheme(themeMode = ThemeMode.Light, dynamicColor = false, colorSchemes = null, inheritHostTheme = false) {
            // A new generation builds the whole tree again, like a new activity in the same process.
            key(generation) {
                UpdateUi(
                    state = state,
                    canInstallNow = { canInstall },
                    actions = recorded.actions,
                    active = active,
                    isOnline = true,
                    installedVersionName = "1.6.2",
                    legacyPermissionStep = false,
                    dismissed = dismissed,
                )
            }
        }
    }

    private fun show(initial: UpdateState) {
        state = initial
        rule.setContent(content)
        rule.waitForIdle()
    }

    private fun change(next: UpdateState) {
        rule.runOnIdle { state = next }
        rule.waitForIdle()
    }

    private fun assertShown(vararg screens: Screen) = assertEquals(screens.toSet(), rule.screensShown())

    private fun click(res: Int) {
        rule.onNodeWithText(str(res)).performClick()
        rule.waitForIdle()
    }

    // ---- Mapping ------------------------------------------------------------------------------

    @Test
    fun everyStateShowsItsScreenOrNothing() {
        val info = uiInfo()
        val cases: List<Pair<UpdateState, Set<Screen>>> =
            listOf(
                UpdateState.Idle to emptySet(),
                UpdateState.Checking to emptySet(),
                UpdateState.UpToDate to emptySet(),
                UpdateState.Unsupported to emptySet(),
                UpdateState.Available(info) to setOf(Screen.UpdateAvailable),
                UpdateState.Downloading(info, someProgress(), userInitiated = true) to setOf(Screen.Downloading),
                UpdateState.Downloading(info, someProgress(), userInitiated = false) to emptySet(),
                UpdateState.WaitingForNetwork(info) to emptySet(),
                UpdateState.ReadyToInstall(info) to setOf(Screen.ReadyToInstall),
                UpdateState.PermissionNeeded(info) to setOf(Screen.Permission),
                UpdateState.Installing(info) to emptySet(),
                UpdateState.Failed(FailureReason.Network, info, userInitiated = true) to setOf(Screen.UpdateFailed),
                UpdateState.Failed(FailureReason.Network, null, userInitiated = true) to setOf(Screen.UpdateFailed),
                UpdateState.Failed(FailureReason.Network, info, userInitiated = false) to emptySet(),
                UpdateState.Failed(FailureReason.Network, null, userInitiated = false) to emptySet(),
            )
        show(UpdateState.Idle)
        for ((case, expected) in cases) {
            change(case)
            assertEquals("Screens for $case", expected, rule.screensShown())
        }
    }

    @Test
    fun everyStepOfARequiredUpdateShowsTheForcedScreenOnly() {
        val info = uiInfo(mandatory = true)
        val steps: List<UpdateStep> =
            listOf(
                UpdateStep.WaitingForUser,
                UpdateStep.Downloading(someProgress()),
                UpdateStep.WaitingForNetwork(canUseMobileData = true),
                UpdateStep.WaitingForNetwork(canUseMobileData = false),
                UpdateStep.PermissionNeeded,
                UpdateStep.Installing,
                UpdateStep.Failed(FailureReason.Network),
                UpdateStep.Failed(FailureReason.HashMismatch),
                UpdateStep.Unsupported,
            )
        show(UpdateState.Idle)
        for (step in steps) {
            change(UpdateState.Required(info, step))
            assertEquals("Screens for step $step", setOf(Screen.Forced), rule.screensShown())
        }
    }

    @Test
    fun theReadyDialogIsHiddenWhileTheHostAppCannotInstallNow() {
        canInstall = false
        show(UpdateState.ReadyToInstall(uiInfo()))
        assertShown()

        rule.runOnIdle { canInstall = true }
        rule.waitForIdle()
        assertShown(Screen.ReadyToInstall)
    }

    @Test
    fun theReadyDialogAsksTheHostAppAgainWhenTheActivityResumes() {
        val activity = TestActivityLifecycle()
        // A plain flag, not Compose state, like most host settings: only a new composition reads it again.
        var hostAllows = false
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides activity) {
                RenovoTheme(themeMode = ThemeMode.Light, dynamicColor = false, colorSchemes = null, inheritHostTheme = false) {
                    UpdateUi(
                        state = UpdateState.ReadyToInstall(uiInfo()),
                        canInstallNow = { hostAllows },
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
        assertShown()

        hostAllows = true
        rule.waitForIdle()
        assertShown()

        // The user leaves the app and comes back.
        rule.runOnIdle { activity.moveTo(Lifecycle.State.STARTED) }
        rule.waitForIdle()
        rule.runOnIdle { activity.moveTo(Lifecycle.State.RESUMED) }
        rule.waitForIdle()
        assertShown(Screen.ReadyToInstall)
    }

    @Test
    fun theReadyDialogIsNotHiddenForTheOtherStatesWhenInstallingIsNotAllowed() {
        canInstall = false
        show(UpdateState.Available(uiInfo()))
        assertShown(Screen.UpdateAvailable)
    }

    @Test
    fun aHostThatIsNotActiveDrawsNothingAndDrawsAgainWhenItIsActive() {
        active = false
        val info = uiInfo()
        val states: List<UpdateState> =
            listOf(
                UpdateState.Available(info),
                UpdateState.Downloading(info, someProgress(), userInitiated = true),
                UpdateState.ReadyToInstall(info),
                UpdateState.PermissionNeeded(info),
                UpdateState.Failed(FailureReason.Network, info, userInitiated = true),
                UpdateState.Required(uiInfo(mandatory = true), UpdateStep.WaitingForUser),
            )
        show(UpdateState.Idle)
        for (candidate in states) {
            change(candidate)
            assertEquals("Screens for $candidate while inactive", emptySet<Screen>(), rule.screensShown())
        }
        change(UpdateState.Available(info))
        rule.runOnIdle { active = true }
        rule.waitForIdle()
        assertShown(Screen.UpdateAvailable)
    }

    @Test
    fun theForcedScreenNeverShowsADismissButton() {
        show(UpdateState.Required(uiInfo(mandatory = true), UpdateStep.PermissionNeeded))
        assertShown(Screen.Forced)
        rule.onNodeWithText(str(R.string.renovo_ui_cancel)).assertDoesNotExist()
    }

    // ---- Actions ------------------------------------------------------------------------------

    @Test
    fun updateStartsTheDownload() {
        show(UpdateState.Available(uiInfo()))
        click(R.string.renovo_ui_update)
        assertEquals(listOf(START_DOWNLOAD), recorded.calls)
    }

    @Test
    fun installAsksForTheInstall() {
        show(UpdateState.ReadyToInstall(uiInfo()))
        click(R.string.renovo_ui_install)
        assertEquals(listOf(INSTALL_IF_READY), recorded.calls)
    }

    @Test
    fun openSettingsAsksForTheInstallBecauseTheEngineOpensTheSetting() {
        show(UpdateState.PermissionNeeded(uiInfo()))
        click(R.string.renovo_ui_open_settings)
        assertEquals(listOf(INSTALL_IF_READY), recorded.calls)
    }

    @Test
    fun tryAgainStartsTheDownloadWhenTheFailureHasVersionInformation() {
        show(UpdateState.Failed(FailureReason.Network, uiInfo(), userInitiated = true))
        click(R.string.renovo_ui_try_again)
        assertEquals(listOf(START_DOWNLOAD), recorded.calls)
    }

    @Test
    fun tryAgainChecksAgainWhenTheFailureHasNoVersionInformation() {
        show(UpdateState.Failed(FailureReason.Network, null, userInitiated = true))
        click(R.string.renovo_ui_try_again)
        assertEquals(listOf(CHECK_NOW), recorded.calls)
    }

    @Test
    fun hideAndTheOtherDismissButtonsCallNoAction() {
        val info = uiInfo()
        show(UpdateState.Downloading(info, someProgress(), userInitiated = true))
        click(R.string.renovo_ui_hide)
        change(UpdateState.Available(info))
        click(R.string.renovo_ui_later)
        change(UpdateState.ReadyToInstall(info))
        click(R.string.renovo_ui_not_now)
        change(UpdateState.PermissionNeeded(info))
        click(R.string.renovo_ui_cancel)
        change(UpdateState.Failed(FailureReason.Network, info, userInitiated = true))
        click(R.string.renovo_ui_close)
        assertEquals(emptyList<String>(), recorded.calls)
    }

    @Test
    fun theForcedScreenButtonsReachTheirActions() {
        val info = uiInfo(mandatory = true)
        show(UpdateState.Required(info, UpdateStep.WaitingForUser))
        click(R.string.renovo_ui_update_now)

        change(UpdateState.Required(info, UpdateStep.Failed(FailureReason.Network)))
        click(R.string.renovo_ui_try_again)

        change(UpdateState.Required(info, UpdateStep.PermissionNeeded))
        click(R.string.renovo_ui_open_settings)

        change(UpdateState.Required(uiInfo(mandatory = true, size = null), UpdateStep.WaitingForNetwork(canUseMobileData = true)))
        click(R.string.renovo_ui_forced_mobile_data_button_no_size)

        assertEquals(listOf(START_DOWNLOAD, START_DOWNLOAD, INSTALL_IF_READY, USE_MOBILE_DATA_ONCE), recorded.calls)
    }

    // ---- Dismissals ---------------------------------------------------------------------------

    @Test
    fun laterHidesTheDialogForThatVersionOnly() {
        show(UpdateState.Available(uiInfo(versionCode = 42)))
        click(R.string.renovo_ui_later)
        assertShown()

        // The same version stays away even after the state went elsewhere and came back.
        change(UpdateState.Idle)
        change(UpdateState.Available(uiInfo(versionCode = 42)))
        assertShown()

        change(UpdateState.Available(uiInfo(versionCode = 43)))
        assertShown(Screen.UpdateAvailable)
    }

    @Test
    fun laterIsKeptForTheProcessWhenTheWholeTreeIsBuiltAgain() {
        show(UpdateState.Available(uiInfo()))
        click(R.string.renovo_ui_later)
        assertShown()

        // Like a second activity in the same process: nothing is saved with the new tree.
        rule.runOnIdle { generation++ }
        rule.waitForIdle()
        assertShown()
        assertTrue(dismissed.isDismissed(Dismissal.Later, 42))
    }

    @Test
    fun hideIsKeptForTheProcessWhenTheWholeTreeIsBuiltAgain() {
        val info = uiInfo()
        show(UpdateState.Downloading(info, someProgress(), userInitiated = true))
        click(R.string.renovo_ui_hide)

        // Like a second activity in the same process: nothing is saved with the new tree.
        rule.runOnIdle { generation++ }
        rule.waitForIdle()
        assertShown()
        assertTrue(dismissed.isDismissed(Dismissal.Hidden, 42))

        // The next download is a new one, in every window.
        change(UpdateState.Failed(FailureReason.Network, info, userInitiated = false))
        rule.runOnIdle { generation++ }
        change(UpdateState.Downloading(info, someProgress(), userInitiated = true))
        assertShown(Screen.Downloading)
    }

    @Test
    fun closeIsKeptForTheProcessWhenTheWholeTreeIsBuiltAgain() {
        val info = uiInfo()
        show(UpdateState.Failed(FailureReason.Network, info, userInitiated = true))
        click(R.string.renovo_ui_close)

        // Like a second activity in the same process: nothing is saved with the new tree.
        rule.runOnIdle { generation++ }
        rule.waitForIdle()
        assertShown()
        assertTrue(dismissed.isDismissed(Dismissal.Closed, 42))

        // A different failure is a new message, in every window.
        rule.runOnIdle { generation++ }
        change(UpdateState.Failed(FailureReason.Storage, info, userInitiated = true))
        assertShown(Screen.UpdateFailed)
    }

    @Test
    fun aFreshProcessAsksAgain() {
        show(UpdateState.Available(uiInfo()))
        click(R.string.renovo_ui_later)
        dismissed.clear()
        rule.runOnIdle { generation++ }
        rule.waitForIdle()
        assertShown(Screen.UpdateAvailable)
    }

    @Test
    fun notNowHidesTheReadyDialogForThatVersionOnly() {
        show(UpdateState.ReadyToInstall(uiInfo(versionCode = 42)))
        click(R.string.renovo_ui_not_now)
        assertShown()
        rule.runOnIdle { generation++ }
        rule.waitForIdle()
        assertShown()

        change(UpdateState.ReadyToInstall(uiInfo(versionCode = 43)))
        assertShown(Screen.ReadyToInstall)
    }

    @Test
    fun cancelHidesThePermissionDialogForThatVersionOnly() {
        show(UpdateState.PermissionNeeded(uiInfo(versionCode = 42)))
        click(R.string.renovo_ui_cancel)
        assertShown()
        rule.runOnIdle { generation++ }
        rule.waitForIdle()
        assertShown()

        change(UpdateState.PermissionNeeded(uiInfo(versionCode = 43)))
        assertShown(Screen.Permission)
    }

    @Test
    fun theThreeDismissalsDoNotHideEachOther() {
        val info = uiInfo()
        show(UpdateState.Available(info))
        click(R.string.renovo_ui_later)

        change(UpdateState.ReadyToInstall(info))
        assertShown(Screen.ReadyToInstall)
        click(R.string.renovo_ui_not_now)

        change(UpdateState.PermissionNeeded(info))
        assertShown(Screen.Permission)
    }

    @Test
    fun hideHidesTheDownloadDialogForThatVersionOnly() {
        show(UpdateState.Downloading(uiInfo(versionCode = 42), someProgress(), userInitiated = true))
        click(R.string.renovo_ui_hide)
        assertShown()

        // Progress goes on without bringing the dialog back.
        change(UpdateState.Downloading(uiInfo(versionCode = 42), someProgress(), userInitiated = true))
        assertShown()

        change(UpdateState.Downloading(uiInfo(versionCode = 43), someProgress(), userInitiated = true))
        assertShown(Screen.Downloading)
    }

    @Test
    fun aHiddenDownloadStaysHiddenWhenItWaitsForANetworkAndGoesOn() {
        val info = uiInfo()
        show(UpdateState.Downloading(info, someProgress(), userInitiated = true))
        click(R.string.renovo_ui_hide)

        // The connection drops and comes back: the same download goes on.
        change(UpdateState.WaitingForNetwork(info))
        change(UpdateState.Downloading(info, someProgress(), userInitiated = true))
        assertShown()

        // A new download after a failure is a new one, so its dialog shows.
        change(UpdateState.Failed(FailureReason.Network, info, userInitiated = false))
        change(UpdateState.Downloading(info, someProgress(), userInitiated = true))
        assertShown(Screen.Downloading)
    }

    @Test
    fun closeHidesTheFailureUntilTheNextOne() {
        val info = uiInfo()
        show(UpdateState.Failed(FailureReason.Network, info, userInitiated = true))
        click(R.string.renovo_ui_close)
        assertShown()

        change(UpdateState.Failed(FailureReason.Storage, info, userInitiated = true))
        assertShown(Screen.UpdateFailed)
        click(R.string.renovo_ui_close)
        assertShown()

        // A new try that fails the same way is a new failure.
        change(UpdateState.Downloading(info, someProgress(), userInitiated = true))
        change(UpdateState.Failed(FailureReason.Storage, info, userInitiated = true))
        assertShown(Screen.UpdateFailed)
    }

    @Test
    fun aRestoredCompositionKeepsEveryDismissal() {
        val tester = StateRestorationTester(rule)
        val info = uiInfo()
        state = UpdateState.Available(info)
        tester.setContent(content)
        rule.waitForIdle()

        val steps: List<Pair<UpdateState, Int>> =
            listOf(
                UpdateState.Available(info) to R.string.renovo_ui_later,
                UpdateState.Downloading(info, someProgress(), userInitiated = true) to R.string.renovo_ui_hide,
                UpdateState.ReadyToInstall(info) to R.string.renovo_ui_not_now,
                UpdateState.PermissionNeeded(info) to R.string.renovo_ui_cancel,
                UpdateState.Failed(FailureReason.Network, info, userInitiated = true) to R.string.renovo_ui_close,
            )
        for ((candidate, button) in steps) {
            change(candidate)
            assertEquals("Screens for $candidate", 1, rule.screensShown().size)
            click(button)
            assertShown()

            // The process-wide holder is emptied first, so only the saved state can keep the screen away.
            dismissed.clear()
            tester.emulateSavedInstanceStateRestore()
            rule.waitForIdle()
            assertEquals("Screens for $candidate after the restore", emptySet<Screen>(), rule.screensShown())
        }
    }

    @Test
    fun aRestoredCompositionStillShowsAScreenNobodyDismissed() {
        val tester = StateRestorationTester(rule)
        state = UpdateState.Available(uiInfo())
        tester.setContent(content)
        rule.waitForIdle()
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertShown(Screen.UpdateAvailable)
        assertEquals(emptyList<String>(), recorded.calls)
    }

    @Test
    fun aHostThatWasInactiveKeepsWhatTheUserChose() {
        val info = uiInfo()
        show(UpdateState.Downloading(info, someProgress(), userInitiated = true))
        click(R.string.renovo_ui_hide)

        rule.runOnIdle { active = false }
        rule.waitForIdle()
        rule.runOnIdle { active = true }
        rule.waitForIdle()
        assertShown()
    }

    @Test
    fun theInstalledVersionIsOnTheForcedScreen() {
        val info: UpdateInfo = uiInfo(mandatory = true)
        show(UpdateState.Required(info, UpdateStep.WaitingForUser))
        rule.onNodeWithText("1.6.2", substring = true).assertExists()
    }
}
