package io.lightine.renovo.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The fallback below API 28 reads the process name from `/proc/self/cmdline`. */
class MainProcessTest {
    private fun cmdline(vararg tokens: String): ByteArray = tokens.joinToString(separator = "\u0000", postfix = "\u0000").toByteArray()

    @Test
    fun `the name is the first token`() {
        assertEquals("com.example.app", processNameFromCmdline(cmdline("com.example.app")))
        assertEquals("com.example.app:remote", processNameFromCmdline(cmdline("com.example.app:remote", "--flag")))
    }

    @Test
    fun `padding after the name is ignored`() {
        // Older kernels and zygote leave the rest of the original argument area filled with NUL bytes.
        assertEquals("com.example.app", processNameFromCmdline("com.example.app".toByteArray() + ByteArray(64)))
    }

    @Test
    fun `a name without a terminating NUL is read whole`() {
        assertEquals("com.example.app", processNameFromCmdline("com.example.app".toByteArray()))
    }

    @Test
    fun `an empty file gives no name`() {
        assertNull(processNameFromCmdline(ByteArray(0)))
        assertNull(processNameFromCmdline(ByteArray(8)))
    }
}
