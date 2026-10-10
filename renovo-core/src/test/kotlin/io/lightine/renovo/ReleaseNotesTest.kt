package io.lightine.renovo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReleaseNotesTest {
    private val notes = ReleaseNotes.of("az" to "Azərbaycanca", "en" to "English", "de" to "Deutsch")

    @Test
    fun `picks the exact language`() {
        assertEquals("Azərbaycanca", notes.forLanguage("az"))
        assertEquals("Deutsch", notes.forLanguage("de"))
    }

    @Test
    fun `matches the language ignoring case`() {
        assertEquals("Azərbaycanca", notes.forLanguage("AZ"))
    }

    @Test
    fun `falls back to English`() {
        assertEquals("English", notes.forLanguage("fr"))
    }

    @Test
    fun `falls back to the first entry when English is missing`() {
        val noEnglish = ReleaseNotes.of("de" to "Deutsch", "az" to "Azərbaycanca")

        assertEquals("Deutsch", noEnglish.forLanguage("fr"))
    }

    @Test
    fun `single text is returned for every language`() {
        val single = ReleaseNotes("Bug fixes")

        assertEquals("Bug fixes", single.forLanguage("az"))
        assertEquals("Bug fixes", single.forLanguage("en"))
    }

    @Test
    fun `rejects no entries, blank codes, and duplicate codes`() {
        assertThrows(IllegalArgumentException::class.java) { ReleaseNotes.of() }
        assertThrows(IllegalArgumentException::class.java) { ReleaseNotes.of(" " to "x") }
        assertThrows(IllegalArgumentException::class.java) { ReleaseNotes.of("en" to "a", "EN" to "b") }
    }

    @Test
    fun `equality follows content and order`() {
        assertEquals(ReleaseNotes("a"), ReleaseNotes("a"))
        assertEquals(ReleaseNotes.of("az" to "a", "en" to "b"), ReleaseNotes.of("az" to "a", "en" to "b"))
        assertNotEquals(ReleaseNotes.of("az" to "a", "en" to "b"), ReleaseNotes.of("en" to "b", "az" to "a"))
        assertNotEquals(ReleaseNotes("a"), ReleaseNotes.of("en" to "a"))
    }
}
