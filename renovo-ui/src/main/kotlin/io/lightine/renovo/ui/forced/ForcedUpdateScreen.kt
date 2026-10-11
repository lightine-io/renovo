package io.lightine.renovo.ui.forced

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateStep
import io.lightine.renovo.ui.ForcedUpdateShape
import io.lightine.renovo.ui.WindowWidth
import io.lightine.renovo.ui.rememberWindowWidth

/**
 * The full-screen forced update screen (UI specification 2.5): the shape, headline, body, the
 * installed and required versions, and then what [step] needs: the release notes and "Update now",
 * the download progress, the wait for a network, the permission steps, the installing label, the
 * failure reason with "Try again", or the unsupported-device text.
 *
 * The screen cannot be dismissed. Back and Escape do nothing. The layout follows the window width:
 * one column on compact windows, one centered column of at most 420 dp on medium windows, and two
 * columns on expanded windows. It scrolls when the window is too short.
 *
 * State is hoisted, so the screen needs no `Renovo`: the host passes what it reads from
 * `UpdateState.Required` and calls the matching action in each callback.
 *
 * ```
 * ForcedUpdateScreen(
 *     info = info,
 *     installedVersionName = "1.6.2",
 *     step = UpdateStep.WaitingForUser,
 *     isOnline = true,
 *     legacyPermissionStep = false,
 *     onUpdateNow = { Renovo.startDownload() },
 *     onTryAgain = { Renovo.startDownload() },
 *     onOpenSettings = { Renovo.installIfReady() },
 *     onUseMobileData = { Renovo.useMobileDataOnce() },
 * )
 * ```
 *
 * @param info The required version, with the release notes and the size.
 * @param installedVersionName The version name of the installed app.
 * @param isOnline False shows the offline note while the screen waits for the user.
 * @param legacyPermissionStep True on Android 7.0 and 7.1 (API 24-25), where step 2 names "unknown sources".
 * @param onUpdateNow Called for "Update now" and for Enter while it has focus.
 * @param onTryAgain Called for "Try again".
 * @param onOpenSettings Called for "Open settings".
 * @param onUseMobileData Called for "Download with mobile data".
 */
@Composable
internal fun ForcedUpdateScreen(
    info: UpdateInfo,
    installedVersionName: String,
    step: UpdateStep,
    isOnline: Boolean,
    legacyPermissionStep: Boolean,
    onUpdateNow: () -> Unit,
    onTryAgain: () -> Unit,
    onOpenSettings: () -> Unit,
    onUseMobileData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // An enabled handler with an empty body takes the Back press, so it neither closes the screen
    // nor falls through to the host app behind it.
    BackHandler(enabled = true) {}

    val width = rememberWindowWidth()
    val shapeSize = shapeSizeFor(width, permission = step == UpdateStep.PermissionNeeded)
    // One scroll state for the screen. It is saved with rememberSaveable inside, so a rotation keeps the position.
    val scroll = rememberScrollState()
    val text: @Composable (Modifier) -> Unit = { textModifier ->
        ForcedTextColumn(
            width = width,
            info = info,
            installedVersionName = installedVersionName,
            step = step,
            isOnline = isOnline,
            legacyPermissionStep = legacyPermissionStep,
            onUpdateNow = onUpdateNow,
            onTryAgain = onTryAgain,
            onOpenSettings = onOpenSettings,
            onUseMobileData = onUseMobileData,
            modifier = textModifier,
        )
    }

    Surface(
        // The preview pass sees the key before any child does, so Escape is taken from a focused
        // button too. Both key directions are consumed, or the key-up would still reach the system.
        modifier = modifier.fillMaxSize().onPreviewKeyEvent { event -> event.key == Key.Escape },
        color = MaterialTheme.colorScheme.surface,
    ) {
        // The safe drawing insets keep the content clear of cutouts, bars, and the on-screen keyboard.
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            when (width) {
                WindowWidth.Compact -> OneColumn(
                    scroll = scroll,
                    shapeSize = shapeSize,
                    modifier = Modifier.fillMaxWidth(),
                    text = text,
                )

                WindowWidth.Medium -> OneColumn(
                    scroll = scroll,
                    shapeSize = shapeSize,
                    modifier = Modifier.widthIn(max = MEDIUM_COLUMN_MAX_WIDTH).fillMaxWidth(),
                    text = text,
                )

                WindowWidth.Expanded -> TwoColumns(scroll = scroll, shapeSize = shapeSize, text = text)
            }
        }
    }
}

// The shape is smaller while the permission steps are shown, so the steps, the switch, and the
// button fit without scrolling (UI specification part 4, state 11). On expanded windows the shape
// sits beside the text and takes no height from it, so it keeps its size.
private fun shapeSizeFor(
    width: WindowWidth,
    permission: Boolean,
): Dp = when (width) {
    WindowWidth.Compact -> if (permission) 92.dp else 132.dp
    WindowWidth.Medium -> if (permission) 132.dp else 168.dp
    WindowWidth.Expanded -> 240.dp
}

@Composable
private fun OneColumn(
    scroll: ScrollState,
    shapeSize: Dp,
    modifier: Modifier,
    text: @Composable (Modifier) -> Unit,
) {
    // fillMaxSize before verticalScroll gives the content the window height as its minimum, so a
    // short screen is centered and a tall one scrolls.
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(SCREEN_PADDING),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            ForcedUpdateShape(size = shapeSize)
            Spacer(Modifier.height(SHAPE_GAP))
            text(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TwoColumns(
    scroll: ScrollState,
    shapeSize: Dp,
    text: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // The group of shape and text is centered. The gap takes the room that is left, within
        // limits, so the group neither sticks together on a narrow window nor falls apart on a wide one.
        val gap = (maxWidth - EXPANDED_PADDING * 2 - shapeSize - TEXT_COLUMN_WIDTH).coerceIn(MIN_COLUMN_GAP, MAX_COLUMN_GAP)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = EXPANDED_PADDING, vertical = SCREEN_PADDING),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
                ForcedUpdateShape(size = shapeSize)
                text(Modifier.width(TEXT_COLUMN_WIDTH))
            }
        }
    }
}

private val SCREEN_PADDING = 24.dp
private val EXPANDED_PADDING = 32.dp
private val SHAPE_GAP = 32.dp
private val MEDIUM_COLUMN_MAX_WIDTH = 420.dp
private val TEXT_COLUMN_WIDTH = 480.dp
private val MIN_COLUMN_GAP = 40.dp
private val MAX_COLUMN_GAP = 96.dp
