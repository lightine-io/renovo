package io.lightine.renovo.ui.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import io.lightine.renovo.ui.PermissionIllustration
import io.lightine.renovo.ui.PermissionSteps
import io.lightine.renovo.ui.R

/**
 * The dialog of the `PermissionNeeded` state: headline, body, three numbered
 * steps, a picture of the system switch (or of the "Always allow" choice on Android 17), and
 * "Cancel" and "Open settings".
 *
 * The switch or choice is a picture. It cannot be tapped, it takes no focus, and screen readers skip it.
 *
 * ```
 * PermissionDialog(
 *     legacyStepText = false,
 *     android17Step = false,
 *     dismissible = true,
 *     onCancel = { hide() },
 *     onOpenSettings = { open() },
 * )
 * ```
 *
 * @param legacyStepText True on Android 7.0 and 7.1 (API 24-25), where the setting is device-wide and
 *   step 2 names "unknown sources".
 * @param android17Step True on Android 17 and later (API 37+), where step 2 names "Always allow" and the
 *   picture shows that choice instead of the switch. Never true together with [legacyStepText].
 * @param dismissible False during a forced update: there is then no "Cancel", and Back, a tap
 *   outside, and Escape do nothing.
 * @param onCancel Called for "Cancel", Back, a tap outside, and Escape.
 * @param onOpenSettings Called for "Open settings" and Enter.
 */
@Composable
internal fun PermissionDialog(
    legacyStepText: Boolean,
    android17Step: Boolean,
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
        PermissionSteps(legacyStepText = legacyStepText, android17Step = android17Step)
        PermissionIllustration(android17Step = android17Step)
    }
}
