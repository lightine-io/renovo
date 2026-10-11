package io.lightine.renovo.ui.dialogs

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.ui.IconTile
import io.lightine.renovo.ui.InnerCard
import io.lightine.renovo.ui.R
import io.lightine.renovo.ui.ReleaseNotesText
import io.lightine.renovo.ui.WindowWidth
import io.lightine.renovo.ui.isHardwareKeyboardAttached
import io.lightine.renovo.ui.rememberWindowWidth

/**
 * The dialog of the `Available` state: icon, headline, the version, the
 * release notes card, and "Later" and "Update".
 *
 * The release notes card is left out when [info] has no release notes. The size line is left out
 * when [sizeText] is null.
 *
 * ```
 * UpdateAvailableDialog(info = info, sizeText = "24 MB", onLater = { hide() }, onUpdate = { start() })
 * ```
 *
 * @param sizeText The size as the user should read it, such as "24 MB", or null when it is not known.
 * @param onLater Called for "Later", Back, a tap outside, and Escape.
 * @param onUpdate Called for "Update" and Enter.
 */
@Composable
internal fun UpdateAvailableDialog(
    info: UpdateInfo,
    sizeText: String?,
    onLater: () -> Unit,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The hint teaches keys, so it only appears where a keyboard is likely: a wide window with one attached.
    val showKeyboardHint = rememberWindowWidth() == WindowWidth.Expanded && isHardwareKeyboardAttached()
    RenovoDialog(
        onDismissRequest = onLater,
        dismissible = true,
        onPrimaryAction = onUpdate,
        modifier = modifier,
        contentAlignment = Alignment.CenterHorizontally,
        footer =
        if (showKeyboardHint) {
            { KeyboardHint() }
        } else {
            null
        },
        actions = { primaryFocus ->
            DialogTextButton(text = stringResource(R.string.renovo_ui_later), onClick = onLater)
            DialogPrimaryButton(
                text = stringResource(R.string.renovo_ui_update),
                onClick = onUpdate,
                modifier = Modifier.focusRequester(primaryFocus),
            )
        },
    ) {
        IconTile(icon = R.drawable.renovo_ic_download)
        DialogHeadline(text = stringResource(R.string.renovo_ui_available_headline), centered = true)
        DialogBody(
            text = stringResource(R.string.renovo_ui_available_body, info.versionName),
            centered = true,
        )
        val notes = info.releaseNotes
        if (notes != null) {
            // The whole tag, not only the language, so a key with a region such as "pt-BR" can match.
            val language = LocalConfiguration.current.locales[0].toLanguageTag()
            val markdown = remember(notes, language) { notes.forLanguage(language) }
            ReleaseNotesCard(markdown = markdown, versionName = info.versionName, sizeText = sizeText)
        }
    }
}

@Composable
private fun ReleaseNotesCard(
    markdown: String,
    versionName: String,
    sizeText: String?,
) {
    InnerCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.renovo_ui_whats_new),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(CARD_LABEL_GAP))
        // A long list of notes scrolls in its own area, so the card never pushes the headline and
        // the buttons out of a short window.
        ReleaseNotesText(
            markdown = markdown,
            modifier = Modifier.heightIn(max = NOTES_MAX_HEIGHT).verticalScroll(rememberScrollState()),
        )
        // The size is left out when it is not known. The version always stays.
        run {
            Spacer(Modifier.height(CARD_LINE_GAP))
            Text(
                text =
                if (sizeText != null) {
                    stringResource(R.string.renovo_ui_version_line, versionName, sizeText)
                } else {
                    stringResource(R.string.renovo_ui_version_line_no_size, versionName)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KeyboardHint() {
    Text(
        text = stringResource(R.string.renovo_ui_keyboard_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        modifier = Modifier.fillMaxWidth().padding(top = HINT_TOP_GAP),
    )
}

private val NOTES_MAX_HEIGHT = 192.dp
private val CARD_LABEL_GAP = 8.dp
private val CARD_LINE_GAP = 12.dp
private val HINT_TOP_GAP = 12.dp
