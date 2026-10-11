package io.lightine.renovo.internal.notify

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.lang.ref.WeakReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 37])
class ActivityForegroundTrackerTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val tracker = ActivityForegroundTracker(application)
    private var foregroundCalls = 0

    init {
        tracker.onForeground { foregroundCalls++ }
    }

    private fun newActivity(): ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).create()

    @Test
    fun `starts in the background`() {
        assertFalse(tracker.isInForeground)
        assertNull(tracker.resumedActivity)
        assertEquals(0, foregroundCalls)
    }

    @Test
    fun `one activity goes through start resume pause stop`() {
        val controller = newActivity()

        controller.start()
        assertTrue(tracker.isInForeground)
        assertNull(tracker.resumedActivity)

        controller.resume()
        assertTrue(tracker.isInForeground)
        assertSame(controller.get(), tracker.resumedActivity)

        controller.pause()
        assertTrue("A paused activity is still started", tracker.isInForeground)
        assertNull(tracker.resumedActivity)

        controller.stop()
        assertFalse(tracker.isInForeground)
        assertEquals(1, foregroundCalls)
    }

    @Test
    fun `a pause alone does not leave the foreground`() {
        // A dialog or a translucent activity over the host app pauses it and does not stop it.
        val controller = newActivity().start().resume()

        controller.pause()

        assertTrue(tracker.isInForeground)
    }

    @Test
    fun `the latest resumed activity wins across two activities`() {
        val first = newActivity().start().resume()
        val second = newActivity()

        // The real order when the user opens a second screen.
        first.pause()
        second.start().resume()
        first.stop()

        assertSame(second.get(), tracker.resumedActivity)
        assertTrue(tracker.isInForeground)

        second.pause()
        assertNull(tracker.resumedActivity)
        second.stop()
        assertFalse(tracker.isInForeground)
    }

    @Test
    fun `going back resumes the earlier activity again`() {
        val first = newActivity().start().resume()
        val second = newActivity()
        first.pause()
        second.start().resume()
        first.stop()

        second.pause()
        first.restart().start().resume()
        second.stop()

        assertSame(first.get(), tracker.resumedActivity)
        assertTrue(tracker.isInForeground)
    }

    @Test
    fun `a late pause of the old activity keeps the new reference`() {
        val first = newActivity().start().resume()
        val second = newActivity().start().resume()

        first.pause()

        assertSame(second.get(), tracker.resumedActivity)
    }

    @Test
    fun `the foreground callback fires on zero to one only`() {
        val first = newActivity().start().resume()
        assertEquals(1, foregroundCalls)

        // A second activity starts before the first stops: the count goes 1, 2, 1. No new call.
        val second = newActivity()
        first.pause()
        second.start().resume()
        first.stop()
        assertEquals(1, foregroundCalls)

        second.pause().stop()
        assertFalse(tracker.isInForeground)
        assertEquals(1, foregroundCalls)

        // The app comes back to the front: zero to one again.
        first.restart().start().resume()
        assertEquals(2, foregroundCalls)
    }

    @Test
    fun `every registered callback runs and a failing one does not block the others`() {
        var second = 0
        tracker.onForeground { error("callback failure") }
        tracker.onForeground { second++ }

        newActivity().start()

        assertEquals(1, foregroundCalls)
        assertEquals(1, second)
    }

    @Test
    fun `a configuration change does not count as leaving and returning`() {
        val controller = newActivity().start().resume()
        assertEquals(1, foregroundCalls)

        // The old activity is stopped and destroyed, then a new instance starts.
        controller.configurationChange()

        assertTrue(tracker.isInForeground)
        assertEquals(1, foregroundCalls)
    }

    @Test
    fun `destroy clears the resumed activity`() {
        val controller = newActivity().start().resume()

        controller.pause().stop().destroy()

        assertNull(tracker.resumedActivity)
        assertFalse(tracker.isInForeground)
    }

    @Test
    fun `destroy while still resumed clears the resumed activity too`() {
        val controller = newActivity().start().resume()

        controller.destroy()

        assertNull(tracker.resumedActivity)
    }

    @Test
    fun `the tracker keeps no strong reference to a destroyed activity`() {
        var controller: ActivityController<Activity>? = newActivity().start().resume()
        val reference = WeakReference(controller?.get())
        // Leave the activity resumed, so only the weak reference could hold it.
        controller?.destroy()
        controller = null

        repeat(10) {
            if (reference.get() != null) {
                System.gc()
                Thread.sleep(20)
            }
        }

        assertNull("The activity leaked", reference.get())
        assertNull(tracker.resumedActivity)
    }

    @Test
    fun `registration from another thread is safe`() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val threads =
            List(8) {
                Thread { repeat(50) { tracker.onForeground { calls.incrementAndGet() } } }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        newActivity().start()

        assertEquals(8 * 50, calls.get())
    }
}
