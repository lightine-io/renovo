package io.lightine.renovo.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.lightine.renovo.ThemeMode

/**
 * The theme wrapper of every update UI screen.
 *
 * This is one of the two places that change when Material 3 Expressive becomes stable (the other is
 * [RenovoProgress]): swap [MaterialTheme] for the Expressive theme here and nothing else moves.
 *
 * The colors come from the first rule that applies:
 * 1. [inheritHostTheme] is true and the host app has its own `MaterialTheme`: the host's scheme.
 *    [themeMode], [dynamicColor], and [colorSchemes] are then ignored, because the host's theme
 *    already decided between light and dark.
 * 2. [colorSchemes] is given: its light or dark scheme.
 * 3. [dynamicColor] is true and the device runs Android 12 (API 31) or later: the wallpaper colors.
 * 4. The Material 3 baseline scheme.
 *
 * Light or dark follows [themeMode]; [ThemeMode.System] asks the system.
 */
@Composable
internal fun RenovoTheme(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    colorSchemes: RenovoColorSchemes?,
    inheritHostTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val hostScheme = MaterialTheme.colorScheme
    if (inheritHostTheme && hostScheme.isHostTheme()) {
        // The host's theme is already in place, so its colors, typography, and shapes pass through.
        content()
        return
    }

    val dark =
        when (themeMode) {
            ThemeMode.System -> isSystemInDarkTheme()
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }
    val context = LocalContext.current
    val colorScheme =
        remember(dark, dynamicColor, colorSchemes, context) {
            when {
                colorSchemes != null -> if (dark) colorSchemes.dark else colorSchemes.light

                dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

                dark -> darkColorScheme()

                else -> lightColorScheme()
            }
        }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/**
 * Tells whether a `MaterialTheme` was set up around the update UI.
 *
 * Compose offers no way to ask this. Without a theme, `MaterialTheme.colorScheme` returns the
 * light baseline scheme, so a scheme that differs from it came from the host app. The one blind
 * spot: a host app that sets exactly the light baseline scheme looks the same as no theme at all.
 * It then gets the update UI's own choice, which is the baseline too unless dynamic color applies.
 */
internal fun ColorScheme.isHostTheme(): Boolean = !hasSameColorsAs(lightColorScheme())

// ColorScheme has no value equality, and its constructor is deprecated, so the roles are listed here.
private fun ColorScheme.hasSameColorsAs(other: ColorScheme): Boolean = colorRoles() == other.colorRoles()

private fun ColorScheme.colorRoles() = listOf(
    primary,
    onPrimary,
    primaryContainer,
    onPrimaryContainer,
    inversePrimary,
    secondary,
    onSecondary,
    secondaryContainer,
    onSecondaryContainer,
    tertiary,
    onTertiary,
    tertiaryContainer,
    onTertiaryContainer,
    background,
    onBackground,
    surface,
    onSurface,
    surfaceVariant,
    onSurfaceVariant,
    surfaceTint,
    inverseSurface,
    inverseOnSurface,
    error,
    onError,
    errorContainer,
    onErrorContainer,
    outline,
    outlineVariant,
    scrim,
    surfaceBright,
    surfaceDim,
    surfaceContainer,
    surfaceContainerHigh,
    surfaceContainerHighest,
    surfaceContainerLow,
    surfaceContainerLowest,
)
