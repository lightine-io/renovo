package io.lightine.renovo.ui.forced

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.lightine.renovo.DownloadProgress
import io.lightine.renovo.FailureReason
import io.lightine.renovo.Network
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateStep
import io.lightine.renovo.ui.InnerCard
import io.lightine.renovo.ui.PermissionIllustration
import io.lightine.renovo.ui.PermissionSteps
import io.lightine.renovo.ui.R
import io.lightine.renovo.ui.ReleaseNotesText
import io.lightine.renovo.ui.RenovoProgress
import io.lightine.renovo.ui.WindowWidth
import io.lightine.renovo.ui.downloadSizeText
import io.lightine.renovo.ui.percentText
import io.lightine.renovo.ui.reasonRes

/**
 * Everything to the right of (expanded) or below (compact, medium) the shape: headline, body,
 * the versions, and the part that depends on [step].
 *
 * [forcedDownloadNetwork] is the host app's `forcedDownloadNetwork`. It picks the waiting note: the
 * Wi-Fi text only when the download waits for an unmetered network, the general text otherwise.
 */
@Composable
internal fun ForcedTextColumn(
    width: WindowWidth,
    tight: Boolean,
    info: UpdateInfo,
    installedVersionName: String,
    step: UpdateStep,
    isOnline: Boolean,
    forcedDownloadNetwork: Network,
    legacyPermissionStep: Boolean,
    android17PermissionStep: Boolean,
    onUpdateNow: () -> Unit,
    onTryAgain: () -> Unit,
    onOpenSettings: () -> Unit,
    onUseMobileData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The design centers the text in a single column and aligns it to the start beside the shape.
    val centered = width != WindowWidth.Expanded
    val horizontal = if (centered) Alignment.CenterHorizontally else Alignment.Start
    val textAlign = if (centered) TextAlign.Center else TextAlign.Start
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(if (tight) TIGHT_SECTION_SPACING else SECTION_SPACING),
        horizontalAlignment = horizontal,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(HEADLINE_GAP), horizontalAlignment = horizontal) {
            Text(
                text = stringResource(R.string.renovo_ui_forced_headline),
                // One size smaller on compact windows, where displaySmall would wrap early.
                style = if (width == WindowWidth.Compact) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth().semantics { heading() },
            )
            Text(
                // On a device that cannot install updates, the unsupported-device text replaces the general body.
                text =
                stringResource(
                    if (step == UpdateStep.Unsupported) R.string.renovo_ui_unsupported_body else R.string.renovo_ui_forced_body,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // The permission state on a short compact window drops the pill: the versions have no use while the
        // user grants the permission, and the height it frees keeps "Open settings" on screen.
        if (!tight) {
            VersionsPill(installedVersionName = installedVersionName, requiredVersionName = info.versionName)
        }
        when (step) {
            UpdateStep.WaitingForUser -> {
                val notes = info.releaseNotes
                if (notes != null) {
                    val language = LocalConfiguration.current.locales[0].language
                    val markdown = remember(notes, language) { notes.forLanguage(language) }
                    ReleaseNotesCard(markdown = markdown)
                }
                ForcedPrimaryButton(text = stringResource(R.string.renovo_ui_update_now), onClick = onUpdateNow, width = width)
                if (!isOnline) {
                    Text(
                        text = stringResource(R.string.renovo_ui_forced_offline_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = textAlign,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            is UpdateStep.Downloading -> DownloadingBlock(progress = step.progress)

            is UpdateStep.WaitingForNetwork -> {
                InnerCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(waitingNoteRes(forcedDownloadNetwork)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = textAlign,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (step.canUseMobileData) {
                    ForcedOutlinedButton(text = mobileDataLabel(info.size), onClick = onUseMobileData, width = width)
                }
            }

            UpdateStep.PermissionNeeded -> PermissionBlock(
                legacyPermissionStep = legacyPermissionStep,
                android17PermissionStep = android17PermissionStep,
                onOpenSettings = onOpenSettings,
                width = width,
                tight = tight,
            )

            UpdateStep.Installing -> {
                // No percent and no size: the system installer reports neither, so the bar is indeterminate.
                ProgressBlock(label = stringResource(R.string.renovo_ui_forced_installing_label), progress = null)
            }

            is UpdateStep.Failed -> {
                ReasonCard(reason = step.reason)
                ForcedPrimaryButton(text = stringResource(R.string.renovo_ui_try_again), onClick = onTryAgain, width = width)
            }

            UpdateStep.Unsupported -> Unit
        }
    }
}

// Only an unmetered-only download waits for Wi-Fi. The other settings accept mobile data too, so a
// note that names Wi-Fi would send the user looking for the wrong network.
@StringRes
private fun waitingNoteRes(network: Network): Int = when (network) {
    Network.UnmeteredOnly -> R.string.renovo_ui_forced_waiting_note
    Network.NotRoaming, Network.Any -> R.string.renovo_ui_forced_waiting_note_any_network
}

/** "Installed 1.6.2   Required 1.8.0" in one rounded row, with the versions in medium weight. */
@Composable
private fun VersionsPill(
    installedVersionName: String,
    requiredVersionName: String,
) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        // A flow row, so a large font scale moves the second pair to a new line instead of clipping it.
        FlowRow(
            modifier = Modifier.padding(horizontal = PILL_HORIZONTAL_PADDING, vertical = PILL_VERTICAL_PADDING),
            horizontalArrangement = Arrangement.spacedBy(PILL_GAP),
            verticalArrangement = Arrangement.spacedBy(PILL_LINE_GAP),
        ) {
            VersionPair(label = stringResource(R.string.renovo_ui_forced_installed), version = installedVersionName)
            VersionPair(label = stringResource(R.string.renovo_ui_forced_required), version = requiredVersionName)
        }
    }
}

@Composable
private fun VersionPair(
    label: String,
    version: String,
) {
    val valueColor = MaterialTheme.colorScheme.onSurface
    // One text, so a screen reader reads "Installed 1.6.2" as one phrase.
    Text(
        text =
        buildAnnotatedString {
            append(label)
            append(" ")
            withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = valueColor)) { append(version) }
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ReleaseNotesCard(markdown: String) {
    InnerCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.renovo_ui_whats_new),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(CARD_LABEL_GAP))
        // Long notes scroll in their own area, so the card cannot push the button far out of reach.
        ReleaseNotesText(
            markdown = markdown,
            modifier = Modifier.heightIn(max = NOTES_MAX_HEIGHT).verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun DownloadingBlock(progress: DownloadProgress) {
    val total = progress.totalBytes
    val fraction = total?.let { (progress.bytesDownloaded.toFloat() / it).coerceIn(0f, 1f) }
    ProgressBlock(label = stringResource(R.string.renovo_ui_forced_progress_label), progress = fraction) {
        // With an unknown size both numbers would be guesses, so they are left out.
        if (total != null && fraction != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = percentText(fraction),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = downloadSizeText(progress.bytesDownloaded, total),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A label above a progress indicator, with optional [details] below it. Always start-aligned, as in the design. */
@Composable
private fun ProgressBlock(
    label: String,
    progress: Float?,
    details: @Composable () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PROGRESS_SPACING)) {
        Text(text = label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        RenovoProgress(progress = progress, modifier = Modifier.fillMaxWidth())
        details()
    }
}

@Composable
private fun PermissionBlock(
    legacyPermissionStep: Boolean,
    android17PermissionStep: Boolean,
    onOpenSettings: () -> Unit,
    width: WindowWidth,
    tight: Boolean,
) {
    // Tighter than the other states: with the smaller shape the whole block has to fit without scrolling.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (tight) TIGHT_PERMISSION_SPACING else PERMISSION_SPACING),
    ) {
        Text(
            text = stringResource(R.string.renovo_ui_permission_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        PermissionSteps(
            legacyStepText = legacyPermissionStep,
            android17Step = android17PermissionStep,
            textStyle = MaterialTheme.typography.bodyLarge,
        )
        PermissionIllustration(android17Step = android17PermissionStep)
        // No "Cancel": a forced update cannot be postponed.
        ForcedPrimaryButton(text = stringResource(R.string.renovo_ui_open_settings), onClick = onOpenSettings, width = width)
    }
}

@Composable
private fun ReasonCard(reason: FailureReason) {
    InnerCard(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
        Row(horizontalArrangement = Arrangement.spacedBy(REASON_ICON_GAP), verticalAlignment = Alignment.CenterVertically) {
            // The text carries the meaning, so the icon is decoration.
            Icon(
                painter = painterResource(R.drawable.renovo_ic_error),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(REASON_ICON_SIZE),
            )
            Text(
                text = "${stringResource(R.string.renovo_ui_failed_reason_label)} ${stringResource(reason.reasonRes())}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/**
 * The filled button of the forced screen. It asks for focus when it appears, so Enter on a keyboard
 * starts the action and a screen reader begins on it.
 */
@Composable
private fun ForcedPrimaryButton(
    text: String,
    onClick: () -> Unit,
    width: WindowWidth,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    Button(
        onClick = onClick,
        modifier = Modifier.forcedButtonSize(width).focusRequester(focus),
        shape = CircleShape,
        contentPadding = forcedButtonPadding(width),
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ForcedOutlinedButton(
    text: String,
    onClick: () -> Unit,
    width: WindowWidth,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.forcedButtonSize(width),
        shape = CircleShape,
        // Named, not left to the defaults: the design asks for an `outline` border and `primary` text.
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.outline),
        contentPadding = forcedButtonPadding(width),
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium)
    }
}

// Heights are minimums, so a large font scale grows the button instead of clipping the label.
// Compact and medium fill the column; expanded is as wide as its label plus the padding.
private fun Modifier.forcedButtonSize(width: WindowWidth): Modifier = when (width) {
    WindowWidth.Compact -> fillMaxWidth().heightIn(min = COMPACT_BUTTON_HEIGHT)
    WindowWidth.Medium -> fillMaxWidth().heightIn(min = MEDIUM_BUTTON_HEIGHT)
    WindowWidth.Expanded -> heightIn(min = EXPANDED_BUTTON_HEIGHT)
}

private fun forcedButtonPadding(width: WindowWidth): PaddingValues = when (width) {
    WindowWidth.Compact, WindowWidth.Medium -> ButtonDefaultsPadding
    WindowWidth.Expanded -> PaddingValues(horizontal = EXPANDED_BUTTON_PADDING)
}

/** The label of the mobile data button: with the size when it is known, a shorter label otherwise. */
@Composable
private fun mobileDataLabel(sizeBytes: Long?): String {
    val context = LocalContext.current
    val size = remember(sizeBytes, context) { sizeBytes?.let { Formatter.formatShortFileSize(context, it) } }
    return if (size != null) {
        stringResource(R.string.renovo_ui_forced_mobile_data_button, size)
    } else {
        stringResource(R.string.renovo_ui_forced_mobile_data_button_no_size)
    }
}

private val SECTION_SPACING = 24.dp
private val HEADLINE_GAP = 12.dp
private val PERMISSION_SPACING = 16.dp
private val TIGHT_SECTION_SPACING = 12.dp
private val TIGHT_PERMISSION_SPACING = 12.dp
private val PROGRESS_SPACING = 12.dp
private val PILL_HORIZONTAL_PADDING = 24.dp
private val PILL_VERTICAL_PADDING = 14.dp
private val PILL_GAP = 32.dp
private val PILL_LINE_GAP = 4.dp
private val CARD_LABEL_GAP = 8.dp
private val NOTES_MAX_HEIGHT = 192.dp
private val REASON_ICON_SIZE = 24.dp
private val REASON_ICON_GAP = 12.dp
private val COMPACT_BUTTON_HEIGHT = 56.dp
private val MEDIUM_BUTTON_HEIGHT = 64.dp
private val EXPANDED_BUTTON_HEIGHT = 56.dp
private val EXPANDED_BUTTON_PADDING = 40.dp
private val ButtonDefaultsPadding = ButtonDefaults.ContentPadding
