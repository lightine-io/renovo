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
    fun `picks the language and region, ignoring case and the separator`() {
        val regional = ReleaseNotes.of("pt" to "Português", "pt-BR" to "Brasil", "pt_pt" to "Portugal", "en" to "English")

        assertEquals("Brasil", regional.forLanguage("pt-BR"))
        assertEquals("Brasil", regional.forLanguage("pt_BR"))
        assertEquals("Brasil", regional.forLanguage("PT-br"))
        assertEquals("Portugal", regional.forLanguage("pt-PT"))
        assertEquals("Portugal", regional.forLanguage("pt_PT"))
    }

    @Test
    fun `ignores the script and extensions of a full language tag`() {
        val regional = ReleaseNotes.of("en" to "English", "pt-BR" to "Brasil", "sr-RS" to "Srbija")

        assertEquals("Brasil", regional.forLanguage("pt-BR-u-mu-celsius"))
        assertEquals("Srbija", regional.forLanguage("sr-Latn-RS"))
    }

    @Test
    fun `prefers a key that matches the whole tag`() {
        val scripts = ReleaseNotes.of("zh-Hans" to "Simplified", "zh-Hant" to "Traditional")

        assertEquals("Traditional", scripts.forLanguage("zh-Hant"))
    }

    @Test
    fun `falls back from the region to the plain language`() {
        val plain = ReleaseNotes.of("en" to "English", "pt-BR" to "Brasil", "pt" to "Português")

        assertEquals("Português", plain.forLanguage("pt-AO"))
        assertEquals("Português", plain.forLanguage("pt"))
    }

    @Test
    fun `falls back from the plain language to the first regional entry in file order`() {
        val regionalOnly = ReleaseNotes.of("en" to "English", "pt_PT" to "Portugal", "pt-BR" to "Brasil")

        assertEquals("Portugal", regionalOnly.forLanguage("pt"))
        assertEquals("Portugal", regionalOnly.forLanguage("PT"))
        assertEquals("Brasil", regionalOnly.forLanguage("pt-BR"))
        assertEquals("Portugal", regionalOnly.forLanguage("pt-AO"))
    }

    @Test
    fun `falls back to a regional English when there is no plain English`() {
        val regionalEnglish = ReleaseNotes.of("az" to "Azərbaycanca", "en-GB" to "British", "en_US" to "American")

        assertEquals("British", regionalEnglish.forLanguage("fr-FR"))
        assertEquals("American", regionalEnglish.forLanguage("en-US"))
    }

    @Test
    fun `prefers plain English over a regional English`() {
        val both = ReleaseNotes.of("az" to "Azərbaycanca", "en-GB" to "British", "en" to "English")

        assertEquals("English", both.forLanguage("fr"))
    }

    @Test
    fun `falls back to the first entry when no language matches`() {
        val regional = ReleaseNotes.of("pt-BR" to "Brasil", "az" to "Azərbaycanca")

        assertEquals("Brasil", regional.forLanguage("fr-FR"))
        assertEquals("Brasil", regional.forLanguage(""))
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
        assertThrows(IllegalArgumentException::class.java) { ReleaseNotes.of("pt-BR" to "a", "pt_br" to "b") }
    }

    @Test
    fun `keeps the code as written`() {
        val notes = ReleaseNotes.of("pt_BR" to "Brasil", "EN" to "English")

        assertEquals(listOf("pt_BR" to "Brasil", "EN" to "English"), notes.entries)
        assertEquals("ReleaseNotes(languages=[pt_BR, EN])", notes.toString())
    }

    @Test
    fun `equality follows content and order`() {
        assertEquals(ReleaseNotes("a"), ReleaseNotes("a"))
        assertEquals(ReleaseNotes.of("az" to "a", "en" to "b"), ReleaseNotes.of("az" to "a", "en" to "b"))
        assertNotEquals(ReleaseNotes.of("az" to "a", "en" to "b"), ReleaseNotes.of("en" to "b", "az" to "a"))
        assertNotEquals(ReleaseNotes("a"), ReleaseNotes.of("en" to "a"))
    }
}
