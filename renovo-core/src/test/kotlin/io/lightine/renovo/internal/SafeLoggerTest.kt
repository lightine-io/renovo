package io.lightine.renovo.internal

import io.lightine.renovo.RenovoLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SafeLoggerTest {
    @Test
    fun `passes the message and the error to the host logger`() {
        val seen = mutableListOf<Pair<String, Throwable?>>()
        val error = IllegalStateException("cause")
        val logger = checkNotNull(SafeLogger.wrap(RenovoLogger { message, e -> seen += message to e }))

        logger.log("one", error)
        logger.log("two", null)

        assertEquals(listOf<Pair<String, Throwable?>>("one" to error, "two" to null), seen)
    }

    @Test
    fun `swallows an exception from the host logger`() {
        val logger = checkNotNull(SafeLogger.wrap(RenovoLogger { _, _ -> throw IllegalStateException("the host logger is broken") }))

        logger.log("a message", RuntimeException("an error"))
    }

    @Test
    fun `swallows an error that is not an exception, too`() {
        val logger = checkNotNull(SafeLogger.wrap(RenovoLogger { _, _ -> throw NoClassDefFoundError("android/util/Log") }))

        logger.log("a message", null)
    }

    @Test
    fun `does not hide a dying process`() {
        val logger = checkNotNull(SafeLogger.wrap(RenovoLogger { _, _ -> throw OutOfMemoryError() }))

        assertThrows(OutOfMemoryError::class.java) { logger.log("a message", null) }
    }

    @Test
    fun `keeps working after the host logger threw`() {
        var calls = 0
        val logger =
            checkNotNull(
                SafeLogger.wrap(
                    RenovoLogger { _, _ ->
                        calls++
                        if (calls == 1) error("first call fails")
                    },
                ),
            )

        logger.log("first", null)
        logger.log("second", null)

        assertEquals(2, calls)
    }

    @Test
    fun `no logger stays no logger, and wrapping twice wraps once`() {
        assertNull(SafeLogger.wrap(null))

        val once = checkNotNull(SafeLogger.wrap(RenovoLogger { _, _ -> }))
        assertSame(once, SafeLogger.wrap(once))
    }
}
