package io.lightine.renovo.internal.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressThrottleTest {
    private val clock = ManualClock()
    private val throttle = ProgressThrottle(clock)

    @Test
    fun `the first report always passes`() {
        assertTrue(throttle.shouldSend(0, 1000))
    }

    @Test
    fun `time alone is not enough`() {
        throttle.shouldSend(0, 1000)
        clock.now += 10_000

        // Still 0 percent.
        assertFalse(throttle.shouldSend(5, 1000))
    }

    @Test
    fun `percent alone is not enough`() {
        throttle.shouldSend(0, 1000)
        clock.now += 499

        assertFalse(throttle.shouldSend(500, 1000))
    }

    @Test
    fun `both together pass`() {
        throttle.shouldSend(0, 1000)
        clock.now += 500

        assertTrue(throttle.shouldSend(10, 1000))
    }

    @Test
    fun `an unknown total is limited by time only`() {
        assertTrue(throttle.shouldSend(10, null))
        clock.now += 100
        assertFalse(throttle.shouldSend(20, null))
        clock.now += 400
        assertTrue(throttle.shouldSend(30, null))
    }

    @Test
    fun `a report that is dropped does not restart the clock`() {
        throttle.shouldSend(0, 1000)
        clock.now += 400
        assertFalse(throttle.shouldSend(50, 1000))
        clock.now += 100

        assertTrue(throttle.shouldSend(60, 1000))
    }

    @Test
    fun `a clock that goes backwards does not silence progress`() {
        throttle.shouldSend(0, 1000)
        clock.now -= 60_000

        assertTrue(throttle.shouldSend(100, 1000))
    }
}
