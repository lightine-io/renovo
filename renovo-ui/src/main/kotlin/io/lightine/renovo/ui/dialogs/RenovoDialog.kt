package io.lightine.renovo.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.lightine.renovo.ui.WindowWidth
import io.lightine.renovo.ui.rememberWindowWidth

/**
 * True makes [RenovoDialog] draw its frame in place instead of in a dialog window.
 *
 * A dialog window is a second window, which Android Studio previews and screenshot tests do not
 * capture together with the screen. Previews and screenshot tests set this so they can show the
 * dialog on a scrim of their own. The app never sets it.
 */
internal val LocalRenovoDialogInline = staticCompositionLocalOf { false }

/**
 * The frame of every dialog of the update UI: a dialog window with a `surfaceContainerHigh`
 * surface, a body that scrolls, and a button row that stays at the bottom.
 *
 * The window is plain Compose [Dialog], not a Material alert dialog, because the design needs a
 * centered icon tile and inner cards that the alert dialog slots cannot place.
 *
 * Keys: Escape calls [onDismissRequest] when [dismissible]. Enter calls [onPrimaryAction] only when
 * no focused element used it first. A focused button therefore keeps its own meaning (Enter on
 * "Later" means later), and the primary button, which asks for focus when the dialog opens, makes
 * Enter mean the primary action in the normal case.
 *
 * ```
 * RenovoDialog(
 *     onDismissRequest = onLater,
 *     dismissible = true,
 *     onPrimaryAction = onUpdate,
 *     actions = { primaryFocus -> DialogPrimaryButton("Update", onUpdate, Modifier.focusRequester(primaryFocus)) },
 * ) { DialogHeadline("Update available") }
 * ```
 *
 * @param onDismissRequest Called for Back, a tap outside, and Escape. Only fires when [dismissible].
 * @param onPrimaryAction What Enter does when no button used the key first.
 * @param contentAlignment Horizontal alignment of the body: centered for dialogs with an icon tile.
 * @param footer Pinned under the buttons, for example the keyboard hint.
 * @param actions The buttons. Give the primary one `Modifier.focusRequester(primaryFocus)`.
 */
@Composable
internal fun RenovoDialog(
    onDismissRequest: () -> Unit,
    dismissible: Boolean,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment.Horizontal = Alignment.Start,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    actions: @Composable (primaryFocus: FocusRequester) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val frame: @Composable () -> Unit = {
        RenovoDialogFrame(
            onDismissRequest = onDismissRequest,
            dismissible = dismissible,
            onPrimaryAction = onPrimaryAction,
            modifier = modifier,
            contentAlignment = contentAlignment,
            footer = footer,
            actions = actions,
            content = content,
        )
    }
    if (LocalRenovoDialogInline.current) {
        frame()
        return
    }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties =
        DialogProperties(
            // The default width is a platform value that ignores the dialog widths set below.
            usePlatformDefaultWidth = false,
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
        ),
    ) {
        MaterialScrimDim()
        frame()
    }
}

/**
 * Sets the dim behind the dialog window to the Material scrim: the `scrim` role at 32 percent.
 * DialogProperties has no stable setting for it, so the dim amount is set on the window's
 * attributes. The platform always dims with black, which is the color of the baseline scrim, so
 * only the opacity of the role can be followed.
 */
@Composable
private fun MaterialScrimDim() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    val amount = MaterialTheme.colorScheme.scrim.alpha * SCRIM_OPACITY
    SideEffect { window?.setDimAmount(amount) }
}

@Composable
private fun RenovoDialogFrame(
    onDismissRequest: () -> Unit,
    dismissible: Boolean,
    onPrimaryAction: () -> Unit,
    modifier: Modifier,
    contentAlignment: Alignment.Horizontal,
    footer: (@Composable ColumnScope.() -> Unit)?,
    actions: @Composable (primaryFocus: FocusRequester) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val width = rememberWindowWidth()
    val primaryFocus = remember { FocusRequester() }
    // A keyboard user can press Enter at once, and a screen reader starts on the main action.
    LaunchedEffect(primaryFocus) { primaryFocus.requestFocus() }

    // Outside the surface, so the margins are empty window area and not part of the dialog.
    // The safe drawing insets keep the dialog clear of cutouts, bars, and the on-screen keyboard.
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .safeDrawingPadding()
            .padding(
                horizontal = if (width == WindowWidth.Compact) COMPACT_MARGIN else 0.dp,
                vertical = VERTICAL_MARGIN,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier =
            modifier
                .widthFor(width)
                .onPreviewKeyEvent { event -> handleEscape(event, dismissible, onDismissRequest) }
                .onKeyEvent { event -> handleEnter(event, onPrimaryAction) },
            shape = DIALOG_SHAPE,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(modifier = Modifier.padding(DIALOG_PADDING)) {
                // weight(fill = false) lets a short dialog wrap its content and a tall one shrink
                // the body, so the buttons below always stay on screen.
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(ELEMENT_SPACING),
                    horizontalAlignment = contentAlignment,
                    content = content,
                )
                Spacer(Modifier.height(BUTTONS_GAP))
                // A flow row so large font scales and long labels wrap to a second line instead of clipping.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(BUTTON_SPACING, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(BUTTON_SPACING),
                ) {
                    actions(primaryFocus)
                }
                footer?.invoke(this)
            }
        }
    }
}

private fun Modifier.widthFor(width: WindowWidth): Modifier = when (width) {
    WindowWidth.Compact -> fillMaxWidth()

    WindowWidth.Medium -> width(MEDIUM_WIDTH)

    // At most 560 dp: the outer box already holds the window width, and an expanded window is wider.
    WindowWidth.Expanded -> widthIn(max = EXPANDED_MAX_WIDTH).fillMaxWidth()
}

// Escape goes through the preview pass and both key directions are consumed. The dialog window
// also treats Escape as Back, so an unconsumed key-up would dismiss a second time.
private fun handleEscape(
    event: KeyEvent,
    dismissible: Boolean,
    onDismissRequest: () -> Unit,
): Boolean {
    if (!dismissible || event.key != Key.Escape) return false
    // A held key repeats key-down. One press must dismiss once.
    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) onDismissRequest()
    return true
}

private fun handleEnter(
    event: KeyEvent,
    onPrimaryAction: () -> Unit,
): Boolean {
    if (event.key != Key.Enter && event.key != Key.NumPadEnter) return false
    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) onPrimaryAction()
    return true
}

private val DIALOG_SHAPE = RoundedCornerShape(28.dp)
private val DIALOG_PADDING = 24.dp
private val ELEMENT_SPACING = 16.dp
private val BUTTONS_GAP = 24.dp
private val BUTTON_SPACING = 8.dp
private val COMPACT_MARGIN = 24.dp
private val VERTICAL_MARGIN = 24.dp
private val MEDIUM_WIDTH = 440.dp
private val EXPANDED_MAX_WIDTH = 560.dp

// The opacity of the Material 3 scrim behind a dialog.
private const val SCRIM_OPACITY = 0.32f
