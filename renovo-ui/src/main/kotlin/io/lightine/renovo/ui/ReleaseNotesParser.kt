package io.lightine.renovo.ui

import java.net.URI
import java.net.URISyntaxException

/**
 * One line of release notes, or the gap between two groups of lines.
 *
 * The parser is a pure function so that a unit test runs it without Compose.
 */
internal sealed interface Block {
    /** A plain line. */
    data class Paragraph(
        val spans: List<Span>,
    ) : Block

    /** A line that started with "- ". */
    data class Bullet(
        val spans: List<Span>,
    ) : Block

    /** One or more empty lines between two other blocks. */
    data object Gap : Block
}

/**
 * A run of text with one style. [url] is set for a link and is always an https URL.
 */
internal data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val url: String? = null,
)

/**
 * Parses the small part of Markdown that release notes may use: `**bold**`, `*italic*`, lines
 * starting with "- " as bullets, `[text](https://...)` links, and line breaks. Everything else is
 * kept exactly as typed, so release notes can never fail to show.
 *
 * Empty lines at the start and end are dropped, and a run of empty lines becomes one [Block.Gap].
 */
internal fun parseReleaseNotes(markdown: String): List<Block> {
    val blocks = mutableListOf<Block>()
    for (line in markdown.split(LINE_BREAK)) {
        when {
            line.startsWith(BULLET_PREFIX) -> blocks += Block.Bullet(parseInline(line.substring(BULLET_PREFIX.length)))
            line.isBlank() -> if (blocks.isNotEmpty() && blocks.last() != Block.Gap) blocks += Block.Gap
            else -> blocks += Block.Paragraph(parseInline(line))
        }
    }
    if (blocks.lastOrNull() == Block.Gap) blocks.removeAt(blocks.lastIndex)
    return blocks
}

private val LINE_BREAK = Regex("\r\n|\n|\r")
private const val BULLET_PREFIX = "- "
private const val BOLD_MARK = "**"

private fun parseInline(text: String): List<Span> {
    val spans = mutableListOf<Span>()
    parseInto(text, bold = false, italic = false, spans = spans)
    return spans
}

private fun parseInto(
    text: String,
    bold: Boolean,
    italic: Boolean,
    spans: MutableList<Span>,
) {
    val literal = StringBuilder()

    fun flush() {
        if (literal.isNotEmpty()) {
            spans.addStyled(literal.toString(), bold, italic, url = null)
            literal.setLength(0)
        }
    }

    var i = 0
    while (i < text.length) {
        val emphasisEnd = emphasisEnd(text, i)
        if (emphasisEnd != null) {
            flush()
            val isBold = text.startsWith(BOLD_MARK, i)
            val markLength = if (isBold) BOLD_MARK.length else 1
            parseInto(
                text.substring(i + markLength, emphasisEnd),
                bold = bold || isBold,
                italic = italic || !isBold,
                spans = spans,
            )
            i = emphasisEnd + markLength
            continue
        }
        val link = if (text[i] == '[') linkAt(text, i) else null
        if (link != null) {
            flush()
            if (link.url != null) {
                spans.addStyled(link.label, bold, italic, link.url)
            } else {
                // A link that is not https is shown as typed. Showing only its label would hide
                // that the notes pointed somewhere the UI refuses to open.
                spans.addStyled(text.substring(i, link.end), bold, italic, url = null)
            }
            i = link.end
            continue
        }
        literal.append(text[i])
        i++
    }
    flush()
}

/**
 * Where the closing mark is, when a bold or italic run opens at [start]; null when none opens
 * there. A run needs text between its marks, and that text must not start or end with a space,
 * so "2 * 3 * 4" stays literal.
 */
private fun emphasisEnd(
    text: String,
    start: Int,
): Int? {
    if (text[start] != '*') return null
    val isBold = text.startsWith(BOLD_MARK, start)
    val markLength = if (isBold) BOLD_MARK.length else 1
    val contentStart = start + markLength
    val close = if (isBold) text.indexOf(BOLD_MARK, contentStart) else singleStarFrom(text, contentStart)
    if (close < 0 || close <= contentStart) return null
    val firstChar = text[contentStart]
    val lastChar = text[close - 1]
    return if (firstChar.isWhitespace() || lastChar.isWhitespace()) null else close
}

/** The next lone "*" at or after [from], stepping over any "**" pair, or -1. */
private fun singleStarFrom(
    text: String,
    from: Int,
): Int {
    var i = from
    while (i < text.length) {
        if (text[i] == '*') {
            if (!text.startsWith(BOLD_MARK, i)) return i
            val pairEnd = text.indexOf(BOLD_MARK, i + BOLD_MARK.length)
            i = if (pairEnd < 0) i + BOLD_MARK.length else pairEnd + BOLD_MARK.length
        } else {
            i++
        }
    }
    return -1
}

private class LinkMatch(
    val label: String,
    /** The URL when it is a usable https URL, otherwise null. */
    val url: String?,
    /** Index just after the closing parenthesis. */
    val end: Int,
)

private fun linkAt(
    text: String,
    start: Int,
): LinkMatch? {
    val labelEnd = text.indexOf(']', start + 1)
    if (labelEnd <= start + 1 || text.getOrNull(labelEnd + 1) != '(') return null
    val urlEnd = text.indexOf(')', labelEnd + 2)
    if (urlEnd < 0) return null
    val url = text.substring(labelEnd + 2, urlEnd)
    return LinkMatch(text.substring(start + 1, labelEnd), url.takeIf { isHttpsUrl(it) }, urlEnd + 1)
}

/** True for an https URL with a host. Release notes come from a server, so nothing else may open. */
private fun isHttpsUrl(url: String): Boolean {
    if (url.any { it.isWhitespace() }) return false
    return try {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty()
    } catch (_: URISyntaxException) {
        false
    }
}

// Neighbouring runs with the same style are joined, so a "**" that stayed literal does not split the
// text around it into separate spans.
private fun MutableList<Span>.addStyled(
    text: String,
    bold: Boolean,
    italic: Boolean,
    url: String?,
) {
    val last = lastOrNull()
    if (url == null && last != null && last.url == null && last.bold == bold && last.italic == italic) {
        this[lastIndex] = last.copy(text = last.text + text)
    } else {
        add(Span(text, bold, italic, url))
    }
}
