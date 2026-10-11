package io.lightine.renovo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Shows release notes from the version file: the Markdown subset of [parseReleaseNotes], drawn
 * with bullets and links.
 *
 * A link opens through the `LocalUriHandler`, and the parser only produces https links.
 */
@Composable
internal fun ReleaseNotesText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    linkColor: Color = MaterialTheme.colorScheme.primary,
) {
    val blocks = remember(markdown) { parseReleaseNotes(markdown) }
    val textStyle = style.copy(color = color)
    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is Block.Paragraph -> {
                    if (index > 0 && blocks[index - 1] !is Block.Gap) Spacer(Modifier.height(LINE_SPACING))
                    Text(text = block.spans.toAnnotatedString(linkColor), style = textStyle)
                }

                is Block.Bullet -> {
                    if (index > 0 && blocks[index - 1] !is Block.Gap) Spacer(Modifier.height(LINE_SPACING))
                    Row(horizontalArrangement = Arrangement.spacedBy(BULLET_GAP)) {
                        // A row child of fixed width keeps wrapped lines aligned under the first
                        // line's text (a hanging indent), which one text with a bullet character
                        // in front of it cannot do. Screen readers skip the dot.
                        Text(
                            text = BULLET,
                            style = textStyle,
                            modifier = Modifier.width(BULLET_WIDTH).clearAndSetSemantics { },
                        )
                        Text(
                            text = block.spans.toAnnotatedString(linkColor),
                            style = textStyle,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Block.Gap -> Spacer(Modifier.height(GAP_HEIGHT))
            }
        }
    }
}

/**
 * Builds the styled text of one line. Each link carries a [LinkAnnotation.Url], which Compose
 * opens through the `LocalUriHandler`.
 */
internal fun List<Span>.toAnnotatedString(linkColor: Color): AnnotatedString = buildAnnotatedString {
    for (span in this@toAnnotatedString) {
        val spanStyle =
            SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
            )
        val url = span.url
        if (url == null) {
            withStyle(spanStyle) { append(span.text) }
        } else {
            val link =
                LinkAnnotation.Url(
                    url = url,
                    styles =
                    TextLinkStyles(
                        style =
                        spanStyle.copy(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ),
                )
            withLink(link) { append(span.text) }
        }
    }
}

private const val BULLET = "•"
private val BULLET_WIDTH = 12.dp
private val BULLET_GAP = 8.dp
private val LINE_SPACING = 4.dp
private val GAP_HEIGHT = 12.dp
