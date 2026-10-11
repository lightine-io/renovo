package io.lightine.renovo.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import io.lightine.renovo.Renovo
import io.lightine.renovo.ui.dialogs.str
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w390dp-h844dp-xhdpi")
class AttachUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun startRenovo() {
        DismissedUpdates.process.clear()
        rule.startRenovoWithAvailableUpdate()
    }

    private fun attach() {
        rule.runOnUiThread { Renovo.attachUi(rule.activity) }
        rule.waitForIdle()
    }

    private fun attachedViews(): Int = rule.activity.window.decorView.countViewsWithId(R.id.renovo_ui_host)

    private fun View.countViewsWithId(id: Int): Int {
        val own = if (this.id == id) 1 else 0
        val group = this as? ViewGroup ?: return own
        return own + (0 until group.childCount).sumOf { group.getChildAt(it).countViewsWithId(id) }
    }

    @Test
    fun addsOneViewAndShowsTheUpdateAvailableDialog() {
        assertEquals(0, attachedViews())
        attach()
        assertEquals(1, attachedViews())
        rule.onNodeWithText(str(R.string.renovo_ui_available_headline)).assertIsDisplayed()
    }

    @Test
    fun aSecondCallForTheSameActivityAddsNothing() {
        attach()
        attach()
        assertEquals(1, attachedViews())
        rule.onNodeWithText(str(R.string.renovo_ui_available_headline)).assertIsDisplayed()
    }

    @Test
    fun theUpdateUiCoversTheWholeContentArea() {
        attach()
        val view = rule.activity.window.decorView.findViewById<View>(R.id.renovo_ui_host)
        val content = rule.activity.findViewById<View>(android.R.id.content)
        assertEquals(content.width, view.width)
        assertEquals(content.height, view.height)
    }

    @Test
    fun theButtonsReachRenovo() {
        attach()
        rule.onNodeWithText(str(R.string.renovo_ui_later)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.renovo_ui_available_headline)).assertDoesNotExist()
    }

    @Test
    fun theUpdateUiLeavesWhileTheActivityIsPausedAndReturnsWhenItResumes() {
        attach()
        rule.onNodeWithText(str(R.string.renovo_ui_available_headline)).assertIsDisplayed()

        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.renovo_ui_available_headline)).assertDoesNotExist()

        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.renovo_ui_available_headline)).assertIsDisplayed()
    }

    @Test
    fun aDismissedDialogStaysDismissedWhenTheActivityIsRecreated() {
        attach()
        rule.onNodeWithText(str(R.string.renovo_ui_later)).performClick()
        rule.waitForIdle()
        // Only the saved state of the view can keep the dialog away now: it has the same id after the rotation.
        DismissedUpdates.process.clear()

        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        attach()
        assertEquals(1, attachedViews())
        rule.onNodeWithText(str(R.string.renovo_ui_available_headline)).assertDoesNotExist()
    }
}
