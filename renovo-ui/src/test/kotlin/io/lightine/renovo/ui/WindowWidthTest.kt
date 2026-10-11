package io.lightine.renovo.ui

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowWidthTest {
    @Test
    fun belowSixHundredIsCompact() {
        assertEquals(WindowWidth.Compact, windowWidthFor(0))
        assertEquals(WindowWidth.Compact, windowWidthFor(599))
    }

    @Test
    fun sixHundredToEightThirtyNineIsMedium() {
        assertEquals(WindowWidth.Medium, windowWidthFor(600))
        assertEquals(WindowWidth.Medium, windowWidthFor(839))
    }

    @Test
    fun eightFortyAndWiderIsExpanded() {
        assertEquals(WindowWidth.Expanded, windowWidthFor(840))
    }

    @Test
    fun largeAndExtraLargeWindowsCountAsExpanded() {
        assertEquals(WindowWidth.Expanded, windowWidthFor(1200))
        assertEquals(WindowWidth.Expanded, windowWidthFor(1600))
    }

    @Test
    fun aDeviceWithoutKeysHasNoHardwareKeyboard() {
        assertFalse(hasHardwareKeyboard(Configuration.KEYBOARD_NOKEYS, Configuration.HARDKEYBOARDHIDDEN_YES))
        assertFalse(hasHardwareKeyboard(Configuration.KEYBOARD_NOKEYS, Configuration.HARDKEYBOARDHIDDEN_NO))
    }

    @Test
    fun anAttachedKeyboardCounts() {
        assertTrue(hasHardwareKeyboard(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_NO))
    }

    @Test
    fun aClosedSlideOutKeyboardDoesNotCount() {
        assertFalse(hasHardwareKeyboard(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_YES))
    }
}
