package io.lightine.renovo.ui.dialogs

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import io.lightine.renovo.DownloadProgress
import io.lightine.renovo.ui.R
import io.lightine.renovo.ui.RenovoProgress
import io.lightine.renovo.ui.percentOf
import java.text.NumberFormat

/**
 * The dialog of the `Downloading` state (UI specification 2.2): headline, body, the progress
 * indicator, the percent and the size, and "Hide".
 *
 * When `progress.totalBytes` is null the indicator is indeterminate and the percent and the size
 * are left out, because both would be guesses.
 *
 * ```
 * DownloadingDialog(progress = DownloadProgress(15_000_000, 24_000_000), onHide = { hide() })
 * ```
 *
 * @param onHide Called for "Hide", Back, a tap outside, Escape, and Enter. The download goes on.
 */
@Composable
internal fun DownloadingDialog(
    progress: DownloadProgress,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = progress.totalBytes
    val fraction = total?.let { (progress.bytesDownloaded.toFloat() / it).coerceIn(0f, 1f) }
    RenovoDialog(
        onDismissRequest = onHide,
        dismissible = true,
        // "Hide" is the only button, so it is both the primary and the dismiss action.
        onPrimaryAction = onHide,
        modifier = modifier,
        actions = { primaryFocus ->
            DialogTextButton(
                text = stringResource(R.string.renovo_ui_hide),
                onClick = onHide,
                modifier = Modifier.focusRequester(primaryFocus),
            )
        },
    ) {
        DialogHeadline(text = stringResource(R.string.renovo_ui_downloading_headline))
        DialogBody(text = stringResource(R.string.renovo_ui_downloading_body))
        RenovoProgress(progress = fraction, modifier = Modifier.fillMaxWidth())
        if (total != null && fraction != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = percentText(fraction),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = sizeText(progress.bytesDownloaded, total),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// The percent sign and its place differ between languages, and the UI specification has no string
// for it, so the platform formats it for the language the screen is in.
@Composable
private fun percentText(fraction: Float): String {
    val locale = LocalConfiguration.current.locales[0]
    // Rounded down, as in the progress indicator's description: never "100%" before the end.
    return remember(fraction, locale) { NumberFormat.getPercentInstance(locale).format(percentOf(fraction) / PERCENT_BASE) }
}

// formatShortFileSize turns bytes into "15 MB" in the device's language.
@Composable
private fun sizeText(
    downloaded: Long,
    total: Long,
): String {
    val context = LocalContext.current
    val (done, whole) =
        remember(downloaded, total, context) {
            Formatter.formatShortFileSize(context, downloaded) to Formatter.formatShortFileSize(context, total)
        }
    return stringResource(R.string.renovo_ui_download_size, done, whole)
}

private const val PERCENT_BASE = 100.0
