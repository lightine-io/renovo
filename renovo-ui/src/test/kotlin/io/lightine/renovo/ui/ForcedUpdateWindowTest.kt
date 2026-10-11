package io.lightine.renovo.ui

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.espresso.Espresso
import io.lightine.renovo.ThemeMode
import io.lightine.renovo.UpdateState
import io.lightine.renovo.UpdateStep
import io.lightine.renovo.ui.dialogs.selfAndAncestors
import io.lightine.renovo.ui.dialogs.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

// The forced update screen must leave nothing of the host app reachable: not by touch, not by the
// keyboard focus, and not by a screen reader. A window of its own does that for both entry points.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w390dp-h844dp-xhdpi")
class ForcedUpdateWindowTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val forced = UpdateState.Required(uiInfo(mandatory = true), UpdateStep.WaitingForUser)

    @Composable
    private fun ForcedUpdateUi() {
        RenovoTheme(themeMode = ThemeMode.Light, dynamicColor = false, colorSchemes = null, inheritHostTheme = false) {
            UpdateUi(
                state = forced,
                canInstallNow = { true },
                actions = RecordedActions().actions,
                isOnline = true,
                installedVersionName = "1.6.2",
                legacyPermissionStep = false,
                dismissed = DismissedUpdates(),
            )
        }
    }

    private fun headline() = rule.onNodeWithText(str(R.string.renovo_ui_forced_headline))

    private fun assertForcedScreenIsInAModalWindowOfItsOwn() {
        headline().assertIsDisplayed()
        // The screen sits in a dialog window, and the window covers the whole activity.
        headline().assert(hasAnyAncestor(isDialog()))
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("The forced screen opens a window", dialog)
        assertTrue(dialog.isShowing)
        val window = dialog.window!!
        // A window that can take focus and is touch-modal keeps both the keys and the screen reader.
        val flags = window.attributes.flags
        assertEquals(0, flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        assertEquals(0, flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        val content = rule.activity.findViewById<View>(android.R.id.content)
        val screen = window.decorView
        assertTrue("The window is as wide as the activity", screen.width >= content.width)
        assertTrue("The window is as tall as the activity", screen.height >= content.height)
    }

    private fun assertBackDoesNothing() {
        Espresso.pressBackUnconditionally()
        rule.waitForIdle()
        headline().assertIsDisplayed()
        assertTrue(ShadowDialog.getLatestDialog().isShowing)
        assertFalse(rule.activity.isFinishing)
    }

    @Test
    fun aComposeHostKeepsItsContentOutOfTheForcedScreensWindow() {
        rule.setContent {
            Box {
                TextButton(onClick = {}) { Text(HOST_BUTTON) }
                ForcedUpdateUi()
            }
        }
        rule.waitForIdle()

        assertForcedScreenIsInAModalWindowOfItsOwn()
        // The host's button is in the host window only, so a screen reader in the forced window cannot reach it.
        rule.onNodeWithText(HOST_BUTTON).assert(!hasAnyAncestor(isDialog()))
        assertFalse(sameRoot(rule.onNodeWithText(HOST_BUTTON).fetchSemanticsNode(), headline().fetchSemanticsNode()))
        assertBackDoesNothing()
    }

    @Test
    fun aViewHostLikeAttachUiKeepsItsViewsOutOfTheForcedScreensWindow() {
        rule.runOnUiThread {
            val activity = rule.activity
            val hostButton = Button(activity).apply { text = HOST_BUTTON }
            activity.setContentView(FrameLayout(activity).apply { addView(hostButton) })
            // The same view that attachUi adds, with the update UI in it.
            val view = ComposeView(activity).apply { setContent { ForcedUpdateUi() } }
            activity.addContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        rule.waitForIdle()

        assertForcedScreenIsInAModalWindowOfItsOwn()
        // Nothing of the forced screen is drawn in the activity's window, so its views stay below the modal window.
        val hostWindowViews = rule.activity.window.decorView
        assertTrue(ShadowDialog.getLatestDialog().window!!.decorView !== hostWindowViews)
        assertBackDoesNothing()
    }

    private fun sameRoot(
        a: SemanticsNode,
        b: SemanticsNode,
    ): Boolean = a.selfAndAncestors().last().id == b.selfAndAncestors().last().id

    private companion object {
        const val HOST_BUTTON = "host button"
    }
}
