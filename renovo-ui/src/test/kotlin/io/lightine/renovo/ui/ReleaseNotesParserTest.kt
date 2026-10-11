package io.lightine.renovo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesParserTest {
    private fun paragraph(vararg spans: Span) = Block.Paragraph(spans.toList())

    private fun bullet(vararg spans: Span) = Block.Bullet(spans.toList())

    @Test
    fun emptyInputGivesNoBlocks() {
        assertEquals(emptyList<Block>(), parseReleaseNotes(""))
        assertEquals(emptyList<Block>(), parseReleaseNotes("\n\n  \n"))
    }

    @Test
    fun plainTextIsOneParagraph() {
        assertEquals(listOf(paragraph(Span("Bug fixes"))), parseReleaseNotes("Bug fixes"))
    }

    @Test
    fun boldText() {
        assertEquals(
            listOf(paragraph(Span("a "), Span("b", bold = true), Span(" c"))),
            parseReleaseNotes("a **b** c"),
        )
    }

    @Test
    fun italicText() {
        assertEquals(
            listOf(paragraph(Span("a "), Span("b", italic = true), Span(" c"))),
            parseReleaseNotes("a *b* c"),
        )
    }

    @Test
    fun italicInsideBoldAndBoldInsideItalic() {
        assertEquals(
            listOf(paragraph(Span("a ", bold = true), Span("b", bold = true, italic = true), Span(" c", bold = true))),
            parseReleaseNotes("**a *b* c**"),
        )
        assertEquals(
            listOf(paragraph(Span("a ", italic = true), Span("b", bold = true, italic = true), Span(" c", italic = true))),
            parseReleaseNotes("*a **b** c*"),
        )
    }

    @Test
    fun linesStartingWithDashAndSpaceAreBullets() {
        assertEquals(
            listOf(bullet(Span("one")), bullet(Span("two"))),
            parseReleaseNotes("- one\n- two"),
        )
    }

    @Test
    fun boldInsideABullet() {
        assertEquals(
            listOf(bullet(Span("Sync is "), Span("faster", bold = true)), bullet(Span("Bug fixes"))),
            parseReleaseNotes("- Sync is **faster**\n- Bug fixes"),
        )
    }

    @Test
    fun theAzerbaijaniExampleOfTheVersionFile() {
        assertEquals(
            listOf(
                bullet(Span("Sinxronizasiya "), Span("sürətləndirildi", bold = true)),
                bullet(Span("Xətalar aradan qaldırıldı")),
            ),
            parseReleaseNotes("- Sinxronizasiya **sürətləndirildi**\n- Xətalar aradan qaldırıldı"),
        )
    }

    @Test
    fun anHttpsLinkKeepsItsLabelAndUrl() {
        assertEquals(
            listOf(paragraph(Span("See "), Span("the notes", url = "https://example.com/notes?a=1"), Span("."))),
            parseReleaseNotes("See [the notes](https://example.com/notes?a=1)."),
        )
    }

    @Test
    fun asteriskInsideAUrlIsNotEmphasis() {
        assertEquals(
            listOf(paragraph(Span("x", url = "https://example.com/a*b*c"))),
            parseReleaseNotes("[x](https://example.com/a*b*c)"),
        )
    }

    @Test
    fun boldLinkKeepsBothStyles() {
        assertEquals(
            listOf(paragraph(Span("x", bold = true, url = "https://example.com"))),
            parseReleaseNotes("**[x](https://example.com)**"),
        )
    }

    @Test
    fun anHttpLinkIsShownAsTyped() {
        assertEquals(
            listOf(paragraph(Span("Open [site](http://example.com) now"))),
            parseReleaseNotes("Open [site](http://example.com) now"),
        )
    }

    @Test
    fun otherSchemesAreShownAsTyped() {
        for (link in listOf("[x](javascript:alert(1))", "[x](intent://open)", "[x](file:///sdcard/a)", "[x](//example.com)")) {
            assertEquals(listOf(paragraph(Span(link))), parseReleaseNotes(link))
        }
    }

    @Test
    fun anHttpsUrlWithoutAHostOrWithSpacesIsShownAsTyped() {
        for (link in listOf("[x](https://)", "[x](https:///path)", "[x](https://exa mple.com)")) {
            assertEquals(listOf(paragraph(Span(link))), parseReleaseNotes(link))
        }
    }

    @Test
    fun unmatchedMarkersAreShownAsTyped() {
        for (text in listOf(
            "**bold",
            "bold**",
            "*italic",
            "a ** b",
            "2 * 3 * 4",
            "** spaced **",
            "****",
            "[no link",
            "[label]",
            "[label] (https://example.com)",
            "[a](https://example.com",
            "[](https://example.com)",
        )) {
            assertEquals(text, listOf(paragraph(Span(text))), parseReleaseNotes(text))
        }
    }

    @Test
    fun otherMarkdownAndHtmlAreShownAsTyped() {
        for (text in listOf("# Heading", "1. first", "`code`", "<b>bold</b>", "> quote")) {
            assertEquals(text, listOf(paragraph(Span(text))), parseReleaseNotes(text))
        }
    }

    @Test
    fun aDashWithoutASpaceOrAfterIndentIsNotABullet() {
        assertEquals(listOf(paragraph(Span("-item"))), parseReleaseNotes("-item"))
        assertEquals(listOf(paragraph(Span("-"))), parseReleaseNotes("-"))
        assertEquals(listOf(paragraph(Span("  - item"))), parseReleaseNotes("  - item"))
    }

    @Test
    fun lineBreaksSeparateBlocks() {
        assertEquals(
            listOf(paragraph(Span("one")), paragraph(Span("two"))),
            parseReleaseNotes("one\ntwo"),
        )
    }

    @Test
    fun crlfAndLoneCrAreLineBreaks() {
        val expected = listOf(bullet(Span("a")), bullet(Span("b")), paragraph(Span("plain")))
        assertEquals(expected, parseReleaseNotes("- a\r\n- b\r\nplain"))
        assertEquals(expected, parseReleaseNotes("- a\r- b\rplain"))
    }

    @Test
    fun aRunOfEmptyLinesIsOneGapAndTheEdgesAreTrimmed() {
        assertEquals(
            listOf(paragraph(Span("a")), Block.Gap, paragraph(Span("b"))),
            parseReleaseNotes("\n\na\n\r\n\n\nb\n\n"),
        )
    }
}
