package io.lightine.renovo.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A stand-in for an activity: an owner with a lifecycle that the test moves by hand. */
private class FakeActivity : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle get() = registry

    fun moveTo(state: Lifecycle.State) {
        registry.currentState = state
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class HostRegistryTest {
    @get:Rule
    val rule = createComposeRule()

    private val registry = HostRegistry()

    @Test
    fun theLastHostToResumeIsTheActiveOne() {
        val a = Any()
        val b = Any()
        registry.resumed(a)
        assertTrue(registry.isActive(a))

        registry.resumed(b)
        assertTrue(registry.isActive(b))
        assertFalse(registry.isActive(a))

        registry.resumed(a)
        assertTrue(registry.isActive(a))
        assertFalse(registry.isActive(b))
    }

    @Test
    fun whenTheActiveHostPausesTheOneThatResumedBeforeItTakesOver() {
        val a = Any()
        val b = Any()
        registry.resumed(a)
        registry.resumed(b)
        registry.paused(b)
        assertTrue(registry.isActive(a))
        registry.paused(a)
        assertFalse(registry.isActive(a))
        assertFalse(registry.isActive(b))
    }

    @Test
    fun aHostThatNeverResumedIsNotActive() {
        assertFalse(registry.isActive(Any()))
    }

    @Composable
    private fun Gate(
        owner: FakeActivity,
        text: String,
    ) {
        if (rememberIsActiveHost(owner, registry)) Text(text)
    }

    private fun showTwoHosts(
        a: FakeActivity,
        b: FakeActivity,
    ) {
        rule.setContent {
            Gate(a, "screen of A")
            Gate(b, "screen of B")
        }
        rule.waitForIdle()
    }

    private fun assertOnly(shown: String?) {
        for (name in listOf("screen of A", "screen of B")) {
            if (name == shown) {
                rule.onNodeWithText(name).assertIsDisplayed()
            } else {
                rule.onNodeWithText(name).assertDoesNotExist()
            }
        }
    }

    @Test
    fun onlyTheMostRecentlyResumedHostRendersAndTheOtherTakesOverWhenItResumes() {
        val a = FakeActivity()
        val b = FakeActivity()
        a.moveTo(Lifecycle.State.RESUMED)
        showTwoHosts(a, b)
        assertOnly("screen of A")

        // B comes to the front: A pauses on its way out, as an activity does.
        rule.runOnIdle {
            a.moveTo(Lifecycle.State.STARTED)
            b.moveTo(Lifecycle.State.RESUMED)
        }
        rule.waitForIdle()
        assertOnly("screen of B")

        rule.runOnIdle {
            b.moveTo(Lifecycle.State.STARTED)
            a.moveTo(Lifecycle.State.RESUMED)
        }
        rule.waitForIdle()
        assertOnly("screen of A")
    }

    @Test
    fun withTwoResumedHostsOnlyTheNewerOneRenders() {
        // Several windows can be resumed at once (split screen on Android 10 and later).
        val a = FakeActivity()
        val b = FakeActivity()
        a.moveTo(Lifecycle.State.RESUMED)
        showTwoHosts(a, b)
        rule.runOnIdle { b.moveTo(Lifecycle.State.RESUMED) }
        rule.waitForIdle()
        assertOnly("screen of B")

        // Resuming A again makes it the newest.
        rule.runOnIdle {
            a.moveTo(Lifecycle.State.STARTED)
            a.moveTo(Lifecycle.State.RESUMED)
        }
        rule.waitForIdle()
        assertOnly("screen of A")
    }

    @Test
    fun noHostRendersWhileNoneIsResumed() {
        val a = FakeActivity()
        val b = FakeActivity()
        showTwoHosts(a, b)
        assertOnly(null)
    }

    @Test
    fun aHostThatLeavesTheCompositionIsNoLongerRegistered() {
        val a = FakeActivity()
        a.moveTo(Lifecycle.State.RESUMED)
        var showHost by mutableStateOf(true)
        rule.setContent { if (showHost) Gate(a, "screen of A") }
        rule.waitForIdle()
        assertTrue(registry.isActive(a))

        rule.runOnIdle { showHost = false }
        rule.waitForIdle()
        assertFalse(registry.isActive(a))
    }
}
