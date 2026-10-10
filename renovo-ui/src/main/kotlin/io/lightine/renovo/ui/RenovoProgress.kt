package io.lightine.renovo.ui

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics

/**
 * The progress indicator of every update UI screen: determinate for a [progress] from 0 to 1,
 * indeterminate for null (the download size is not known, or the update is installing).
 *
 * This is one of the two places that change when Material 3 Expressive becomes stable (the other is
 * [RenovoTheme]): swap the indicator for the wavy one here and no screen changes.
 */
@Composable
internal fun RenovoProgress(
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    if (progress == null) {
        // Set explicitly so the semantics do not depend on what the Material indicator adds.
        LinearProgressIndicator(
            modifier = modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
        )
        return
    }
    val value = progress.coerceIn(0f, 1f)
    // Rounded down: a download at 99.6 percent must not read "100 percent" before it is done.
    val description = stringResource(R.string.renovo_ui_download_progress_description, percentOf(value))
    LinearProgressIndicator(
        progress = { value },
        modifier =
        modifier.semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
            contentDescription = description
        },
    )
}

internal fun percentOf(progress: Float): Int = (progress.coerceIn(0f, 1f) * 100).toInt()
