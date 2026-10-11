package io.lightine.renovo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.lightine.renovo.Renovo

/**
 * Draws [content] and, on top of it, the update UI: the dialogs and the forced update screen.
 *
 * Call `Renovo.init` first. The update UI follows `Renovo.state` and needs no other setup. It
 * appears only in the most recently resumed activity that has a host.
 *
 * The colors come from the surrounding `MaterialTheme` when there is one. Pass [colorSchemes] to
 * use other colors; the `ui { }` settings of `Renovo.init` then pick light or dark.
 *
 * ```
 * setContent {
 *     AppTheme {
 *         RenovoUpdateHost { AppContent() }
 *     }
 * }
 * ```
 *
 * @param colorSchemes A light and a dark scheme for the update UI, or null to inherit the host theme.
 * @param content The host app's own screen.
 */
@Composable
public fun RenovoUpdateHost(
    colorSchemes: RenovoColorSchemes? = null,
    content: @Composable () -> Unit,
) {
    // The minimum constraints go through, so the content is measured as it would be without the Box.
    Box(propagateMinConstraints = true) {
        content()
        RenovoUpdateUi(colorSchemes = colorSchemes, inheritHostTheme = colorSchemes == null)
    }
}

/**
 * The update UI wired to `Renovo`: its theme, its state, and its actions. Both entry points draw
 * this, and they differ only in where the theme comes from.
 *
 * @param inheritHostTheme True when a `MaterialTheme` of the host app may be used. [attachUi] has none.
 */
@Composable
internal fun RenovoUpdateUi(
    colorSchemes: RenovoColorSchemes?,
    inheritHostTheme: Boolean,
) {
    val config = Renovo.config
    RenovoTheme(
        themeMode = config.ui.themeMode,
        dynamicColor = config.ui.dynamicColor,
        colorSchemes = colorSchemes,
        inheritHostTheme = inheritHostTheme,
    ) {
        // The engine is the state holder (technical specification section 13), so no ViewModel here.
        val state by Renovo.state.collectAsStateWithLifecycle()
        UpdateUi(
            state = state,
            canInstallNow = config.canInstallNow,
            actions = UpdateUiActions.Default,
            active = rememberIsActiveHost(),
        )
    }
}
