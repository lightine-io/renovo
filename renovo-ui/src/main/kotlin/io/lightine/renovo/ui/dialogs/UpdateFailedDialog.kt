package io.lightine.renovo.ui.dialogs

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import io.lightine.renovo.FailureReason
import io.lightine.renovo.ui.IconTile
import io.lightine.renovo.ui.InnerCard
import io.lightine.renovo.ui.R
import io.lightine.renovo.ui.reasonRes

/**
 * The dialog of the `Failed` state after something the user started: error
 * icon, headline, a body, the reason, and "Close" and "Try again".
 *
 * The body is the safety-check text for [FailureReason.HashMismatch], [FailureReason.SignatureMismatch],
 * and [FailureReason.NotNewer], and the general text for every other reason.
 *
 * ```
 * UpdateFailedDialog(reason = FailureReason.Network, onClose = { hide() }, onTryAgain = { retry() })
 * ```
 *
 * @param onClose Called for "Close", Back, a tap outside, and Escape.
 * @param onTryAgain Called for "Try again" and Enter.
 */
@Composable
internal fun UpdateFailedDialog(
    reason: FailureReason,
    onClose: () -> Unit,
    onTryAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RenovoDialog(
        onDismissRequest = onClose,
        dismissible = true,
        onPrimaryAction = onTryAgain,
        modifier = modifier,
        contentAlignment = Alignment.CenterHorizontally,
        actions = { primaryFocus ->
            DialogTextButton(text = stringResource(R.string.renovo_ui_close), onClick = onClose)
            DialogPrimaryButton(
                text = stringResource(R.string.renovo_ui_try_again),
                onClick = onTryAgain,
                modifier = Modifier.focusRequester(primaryFocus),
            )
        },
    ) {
        IconTile(
            icon = R.drawable.renovo_ic_error,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            iconColor = MaterialTheme.colorScheme.error,
        )
        DialogHeadline(text = stringResource(R.string.renovo_ui_failed_headline), centered = true)
        DialogBody(text = stringResource(reason.bodyRes()), centered = true)
        InnerCard(modifier = Modifier.fillMaxWidth()) {
            val label = stringResource(R.string.renovo_ui_failed_reason_label)
            val reasonText = stringResource(reason.reasonRes())
            // One text, two colors: a screen reader reads "Reason: no connection" as one sentence.
            Text(
                text =
                buildAnnotatedString {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(label) }
                    append(" ")
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) { append(reasonText) }
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// Both functions list every reason, with no else branch, so a new FailureReason does not compile
// until its text is chosen here.
@StringRes
internal fun FailureReason.bodyRes(): Int = when (this) {
    FailureReason.HashMismatch,
    FailureReason.SignatureMismatch,
    FailureReason.NotNewer,
    -> R.string.renovo_ui_failed_body_verification

    FailureReason.Network,
    FailureReason.Parse,
    FailureReason.Storage,
    FailureReason.InstallRejected,
    FailureReason.Cancelled,
    -> R.string.renovo_ui_failed_body_general
}
