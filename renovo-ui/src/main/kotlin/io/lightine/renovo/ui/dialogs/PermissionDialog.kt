package io.lightine.renovo.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.lightine.renovo.ui.InnerCard
import io.lightine.renovo.ui.R
import io.lightine.renovo.ui.StepTile

/**
 * The dialog of the `PermissionNeeded` state (UI specification 2.4): headline, body, three numbered
 * steps, a picture of the system switch, and "Cancel" and "Open settings".
 *
 * The switch is a picture. It cannot be tapped, it takes no focus, and screen readers skip it.
 *
 * ```
 * PermissionDialog(legacyStepText = false, dismissible = true, onCancel = { hide() }, onOpenSettings = { open() })
 * ```
 *
 * @param legacyStepText True on Android 7.0 and 7.1 (API 24-25), where the setting is device-wide and
 *   step 2 names "unknown sources".
 * @param dismissible False during a forced update: there is then no "Cancel", and Back, a tap
 *   outside, and Escape do nothing.
 * @param onCancel Called for "Cancel", Back, a tap outside, and Escape.
 * @param onOpenSettings Called for "Open settings" and Enter.
 */
@Composable
internal fun PermissionDialog(
    legacyStepText: Boolean,
    dismissible: Boolean,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RenovoDialog(
        onDismissRequest = onCancel,
        dismissible = dismissible,
        onPrimaryAction = onOpenSettings,
        modifier = modifier,
        actions = { primaryFocus ->
            if (dismissible) {
                DialogTextButton(text = stringResource(R.string.renovo_ui_cancel), onClick = onCancel)
            }
            DialogPrimaryButton(
                text = stringResource(R.string.renovo_ui_open_settings),
                onClick = onOpenSettings,
                modifier = Modifier.focusRequester(primaryFocus),
            )
        },
    ) {
        DialogHeadline(text = stringResource(R.string.renovo_ui_permission_headline))
        DialogBody(text = stringResource(R.string.renovo_ui_permission_body))
        Column(verticalArrangement = Arrangement.spacedBy(STEP_SPACING)) {
            Step(number = 1, text = stringResource(R.string.renovo_ui_permission_step_1))
            Step(
                number = 2,
                text =
                stringResource(
                    if (legacyStepText) {
                        R.string.renovo_ui_permission_step_2_legacy
                    } else {
                        R.string.renovo_ui_permission_step_2
                    },
                ),
            )
            Step(number = 3, text = stringResource(R.string.renovo_ui_permission_step_3))
        }
        SwitchIllustration()
    }
}

@Composable
private fun Step(
    number: Int,
    text: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(STEP_TEXT_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepTile(number = number)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// A drawing of the system switch the user will see, not a control: no change callback, so it takes
// no touch and no focus, and the whole card is hidden from screen readers because it says nothing
// the steps do not say.
@Composable
private fun SwitchIllustration() {
    InnerCard(modifier = Modifier.fillMaxWidth().semantics { hideFromAccessibility() }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.renovo_ui_permission_switch_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = true, onCheckedChange = null)
        }
    }
}

private val STEP_SPACING = 12.dp
private val STEP_TEXT_GAP = 16.dp
