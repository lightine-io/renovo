package io.lightine.renovo.internal.runtime

import android.os.Looper
import io.lightine.renovo.UpdateListener
import io.lightine.renovo.UpdateState
import io.lightine.renovo.internal.FetchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

internal class DefaultRuntimeListenerTest : RuntimeTestBase() {
    private class Recording : UpdateListener {
        val states = CopyOnWriteArrayList<UpdateState>()
        val onMainThread = CopyOnWriteArrayList<Boolean>()

        override fun onStateChanged(state: UpdateState) {
            states += state
            onMainThread += Looper.myLooper() === Looper.getMainLooper()
        }
    }

    @Test
    fun `a new listener gets the current state, then every change`() {
        val h = harness()
        val listener = Recording()

        h.runtime.addListener(listener)
        h.settle()
        assertEquals(listOf<UpdateState>(UpdateState.Idle), listener.states.toList())

        h.startWithUpdate()

        assertEquals(UpdateState.Idle, listener.states.first())
        assertEquals(h.state::class, listener.states.last()::class)
        assertTrue(h.state is UpdateState.Downloading)
        // What the listener saw is a subsequence of what the state was; it may skip a state, like any StateFlow collector.
        assertTrue(listener.states.all { seen -> h.states.any { it == seen } })
    }

    @Test
    fun `a listener added later gets the state at that moment`() {
        val h = harness()
        h.startWithUpdate()
        val listener = Recording()

        h.runtime.addListener(listener)
        h.settle()

        assertEquals(1, listener.states.size)
        assertTrue(listener.states.single() is UpdateState.Downloading)
    }

    @Test
    fun `a listener is called on the main thread, also when the change comes from another thread`() {
        val h = harness(realThreads = true)
        h.fetcher.result = FetchResult.Found(testInfo(), null)
        val listener = Recording()

        // Added from the test thread, which is the main thread under Robolectric: the current state arrives at once.
        h.runtime.addListener(listener)
        assertEquals(1, listener.states.size)

        // The loop runs on Dispatchers.Default, so these changes start on another thread.
        h.start()
        waitUntil { listener.states.lastOrNull() is UpdateState.Downloading }

        assertTrue(listener.states.size >= 2)
        assertTrue(listener.onMainThread.all { it })
    }

    @Test
    fun `a removed listener gets nothing more`() {
        val h = harness()
        val kept = Recording()
        val removed = Recording()
        h.runtime.addListener(kept)
        h.runtime.addListener(removed)
        h.settle()

        h.runtime.removeListener(removed)
        h.startWithUpdate()

        assertEquals(1, removed.states.size)
        assertTrue(kept.states.size > 1)
    }

    @Test
    fun `a listener removed before it got anything is never called`() {
        val h = harness()
        val listener = Recording()

        h.runtime.addListener(listener)
        h.runtime.removeListener(listener)
        h.startWithUpdate()

        assertTrue(listener.states.isEmpty())
    }

    @Test
    fun `adding the same listener twice calls it once per change`() {
        val h = harness()
        val listener = Recording()

        h.runtime.addListener(listener)
        h.runtime.addListener(listener)
        h.settle()

        assertEquals(1, listener.states.size)
    }

    @Test
    fun `a listener that throws does not stop the others or itself`() {
        val h = harness()
        val calls = CopyOnWriteArrayList<UpdateState>()
        val other = Recording()
        h.runtime.addListener {
            calls += it
            throw FakeFailure()
        }
        h.runtime.addListener(other)

        h.startWithUpdate()

        assertTrue(calls.size > 1)
        assertTrue(other.states.size > 1)
        assertTrue(h.uncaught.isEmpty())
        assertFalse(h.logs.none { it.contains("listener") })
    }
}
