package io.lightine.renovo.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.ui.IconTile
import io.lightine.renovo.ui.InnerCard
import io.lightine.renovo.ui.R

/**
 * The dialog of the `ReadyToInstall` state (UI specification 2.3): shield icon, headline, body,
 * the verified line, and "Not now" and "Install".
 *
 * ```
 * ReadyToInstallDialog(info = info, onNotNow = { hide() }, onInstall = { install() })
 * ```
 *
 * @param onNotNow Called for "Not now", Back, a tap outside, and Escape.
 * @param onInstall Called for "Install" and Enter.
 */
@Composable
internal fun ReadyToInstallDialog(
    info: UpdateInfo,
    onNotNow: () -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RenovoDialog(
        onDismissRequest = onNotNow,
        dismissible = true,
        onPrimaryAction = onInstall,
        modifier = modifier,
        contentAlignment = Alignment.CenterHorizontally,
        actions = { primaryFocus ->
            DialogTextButton(text = stringResource(R.string.renovo_ui_not_now), onClick = onNotNow)
            DialogPrimaryButton(
                text = stringResource(R.string.renovo_ui_install),
                onClick = onInstall,
                modifier = Modifier.focusRequester(primaryFocus),
            )
        },
    ) {
        IconTile(icon = R.drawable.renovo_ic_verified)
        DialogHeadline(text = stringResource(R.string.renovo_ui_ready_headline), centered = true)
        DialogBody(text = stringResource(R.string.renovo_ui_ready_body, info.versionName), centered = true)
        InnerCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(VERIFIED_ICON_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The text says all of it, so the icon is decoration.
                Icon(
                    painter = painterResource(R.drawable.renovo_ic_check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(VERIFIED_ICON_SIZE),
                )
                Text(
                    text = stringResource(R.string.renovo_ui_ready_verified),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private val VERIFIED_ICON_SIZE = 24.dp
private val VERIFIED_ICON_GAP = 12.dp
