package io.lightine.renovo.ui

import androidx.compose.material3.ColorScheme

/**
 * A light and a dark Material 3 color scheme that the update UI uses instead of its own colors.
 *
 * The host app passes the same two schemes it gives to its own `MaterialTheme`. Pass it to
 * [RenovoUpdateHost] or [attachUi].
 *
 * ```
 * val schemes = RenovoColorSchemes(
 *     light = lightColorScheme(primary = Color(0xFF6750A4)),
 *     dark = darkColorScheme(primary = Color(0xFFD0BCFF)),
 * )
 * Renovo.attachUi(this, schemes)
 * ```
 */
public class RenovoColorSchemes(
    /**
     * The scheme used when the update UI is light.
     *
     * ```
     * val primary = schemes.light.primary
     * ```
     */
    public val light: ColorScheme,
    /**
     * The scheme used when the update UI is dark.
     *
     * ```
     * val primary = schemes.dark.primary
     * ```
     */
    public val dark: ColorScheme,
) {
    override fun equals(other: Any?): Boolean = other is RenovoColorSchemes && other.light == light && other.dark == dark

    override fun hashCode(): Int = 31 * light.hashCode() + dark.hashCode()

    override fun toString(): String = "RenovoColorSchemes(light=$light, dark=$dark)"
}
