package io.lightine.renovo.ui

import androidx.compose.runtime.Composable

/** The Azerbaijani release notes of the version file example in technical specification section 4. */
internal const val AZERBAIJANI_NOTES = "- Sinxronizasiya **sürətləndirildi**\n- Xətalar aradan qaldırıldı"

/** One of each supported element, then the cases that must stay as typed. */
internal const val ALL_ELEMENTS_NOTES =
    "A plain line, then a line break.\n" +
        "Another line right below it.\n" +
        "\n" +
        "**Bold**, *italic*, and a [link to the project](https://example.com/changes).\n" +
        "- A bullet with **bold** inside\n" +
        "- A bullet with a [link](https://example.com) and *italic* text\n" +
        "- A long bullet that runs over more than one line, so the second line stays under the first " +
        "line's text and not under the bullet\n" +
        "\n" +
        "Kept as typed: [http link](http://example.com), an unmatched **marker, 2 * 3 * 4, and <b>tags</b>."

@LightAndDark
@Composable
internal fun ReleaseNotesAzerbaijaniPreview() {
    PreviewFrame { ReleaseNotesText(markdown = AZERBAIJANI_NOTES) }
}

@LightAndDark
@Composable
internal fun ReleaseNotesAllElementsPreview() {
    PreviewFrame { ReleaseNotesText(markdown = ALL_ELEMENTS_NOTES) }
}
