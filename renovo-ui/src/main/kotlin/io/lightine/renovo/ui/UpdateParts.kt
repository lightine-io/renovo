package io.lightine.renovo.ui

import android.os.Build
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.lightine.renovo.FailureReason
import java.text.NumberFormat

// Pieces that both the dialogs and the forced update screen draw, so each exists once.

/**
 * The system screen where the user grants the install permission. Android changed it twice, and
 * step 2 and the picture under the steps have to match what the user will see.
 */
internal enum class PermissionScreen {
    /** Android 7.0 and 7.1: one device-wide "Unknown sources" setting. */
    UnknownSources,

    /** Android 8 to 16: "Install unknown apps", a switch per source app. */
    Switch,

    /** Android 17 and later: two choices, "Always allow" and "Ask every time". */
    AlwaysAllow,
}

/**
 * The permission screen of [sdkInt]. The update UI decides it here and nowhere else, so the two
 * flags derived from it, legacy and Android 17, can never both be true on a device.
 */
internal fun permissionScreenFor(sdkInt: Int = Build.VERSION.SDK_INT): PermissionScreen = when {
    sdkInt < Build.VERSION_CODES.O -> PermissionScreen.UnknownSources
    sdkInt < Build.VERSION_CODES.CINNAMON_BUN -> PermissionScreen.Switch
    else -> PermissionScreen.AlwaysAllow
}

/**
 * The three numbered steps of the permission screen. Step 2 names "unknown sources" when
 * [legacyStepText] is true (Android 7.0 and 7.1, where the setting is device-wide), and the
 * "Always allow" choice when [android17Step] is true (Android 17 and later). At most one is true.
 */
@Composable
internal fun PermissionSteps(
    legacyStepText: Boolean,
    android17Step: Boolean,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    require(!(legacyStepText && android17Step)) { "The Android 7 and the Android 17 permission steps exclude each other" }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(STEP_SPACING)) {
        PermissionStep(number = 1, text = stringResource(R.string.renovo_ui_permission_step_1), textStyle = textStyle)
        PermissionStep(
            number = 2,
            text =
            stringResource(
                when {
                    legacyStepText -> R.string.renovo_ui_permission_step_2_legacy
                    android17Step -> R.string.renovo_ui_permission_step_2_android17
                    else -> R.string.renovo_ui_permission_step_2
                },
            ),
            textStyle = textStyle,
        )
        PermissionStep(number = 3, text = stringResource(R.string.renovo_ui_permission_step_3), textStyle = textStyle)
    }
}

@Composable
private fun PermissionStep(
    number: Int,
    text: String,
    textStyle: TextStyle,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(STEP_TEXT_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepTile(number = number)
        Text(text = text, style = textStyle, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * The picture under the permission steps: the "Always allow" choice when [android17Step] is true,
 * the switch otherwise, because those are what the system screen shows.
 */
@Composable
internal fun PermissionIllustration(
    android17Step: Boolean,
    modifier: Modifier = Modifier,
) {
    if (android17Step) PermissionOptionIllustration(modifier) else PermissionSwitchIllustration(modifier)
}

/**
 * A drawing of the system switch the user will see, not a control: no change callback, so it takes
 * no touch and no focus, and the whole card is hidden from screen readers because it says nothing
 * the steps do not say.
 */
@Composable
private fun PermissionSwitchIllustration(modifier: Modifier) {
    InnerCard(modifier = modifier.fillMaxWidth().semantics { hideFromAccessibility() }) {
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

/**
 * A drawing of the selected "Always allow" choice of Android 17, not a control. For the same reasons
 * as the switch: no click callback, so it takes no touch and no focus, and the card is hidden from
 * screen readers. The radio comes first, as on the system screen.
 */
@Composable
private fun PermissionOptionIllustration(modifier: Modifier) {
    InnerCard(modifier = modifier.fillMaxWidth().semantics { hideFromAccessibility() }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OPTION_LABEL_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = true, onClick = null)
            Text(
                text = stringResource(R.string.renovo_ui_permission_option_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// The percent sign and its place differ between languages, and the library has no string for it,
// so the platform formats it for the language the screen is in.
@Composable
internal fun percentText(fraction: Float): String {
    val locale = LocalConfiguration.current.locales[0]
    // Rounded down, as in the progress indicator's description: never "100%" before the end.
    return remember(fraction, locale) { NumberFormat.getPercentInstance(locale).format(percentOf(fraction) / PERCENT_BASE) }
}

// formatShortFileSize turns bytes into "15 MB" in the device's language.
@Composable
internal fun downloadSizeText(
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

/** The reason line text of a failure, such as "no connection". Lists every reason with no else branch. */
@StringRes
internal fun FailureReason.reasonRes(): Int = when (this) {
    FailureReason.Network -> R.string.renovo_ui_failed_reason_network
    FailureReason.Parse -> R.string.renovo_ui_failed_reason_parse
    FailureReason.HashMismatch -> R.string.renovo_ui_failed_reason_hash
    FailureReason.SignatureMismatch -> R.string.renovo_ui_failed_reason_signature
    FailureReason.NotNewer -> R.string.renovo_ui_failed_reason_not_newer
    FailureReason.Storage -> R.string.renovo_ui_failed_reason_storage
    FailureReason.InstallRejected -> R.string.renovo_ui_failed_reason_rejected
    FailureReason.Cancelled -> R.string.renovo_ui_failed_reason_cancelled
}

private val STEP_SPACING = 12.dp
private val STEP_TEXT_GAP = 16.dp
private val OPTION_LABEL_GAP = 16.dp
private const val PERCENT_BASE = 100.0
