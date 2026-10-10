package io.lightine.renovo.ui

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.window.core.layout.WindowSizeClass

/**
 * The width classes the update UI lays out for, as in the UI specification. Decided by window
 * width alone, never by device type.
 */
internal enum class WindowWidth {
    /** Narrower than 600 dp: phones, folded devices, narrow windows. */
    Compact,

    /** 600 to 839 dp: tablets in portrait, unfolded devices. */
    Medium,

    /** 840 dp and wider. */
    Expanded,
}

/**
 * The [WindowWidth] of the window the update UI is drawn in. It changes when the window is resized
 * or rotated.
 */
@Composable
internal fun rememberWindowWidth(): WindowWidth {
    val sizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    return when {
        // The V2 info also reports large (1200 dp) and extra-large (1600 dp) windows. The UI has no
        // layout of its own for them: they are wider than expanded, so they count as expanded.
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> WindowWidth.Expanded

        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> WindowWidth.Medium

        else -> WindowWidth.Compact
    }
}

/** The [WindowWidth] for a window [widthDp] dp wide. Kept free of Compose so a unit test can call it. */
internal fun windowWidthFor(widthDp: Int): WindowWidth = when {
    widthDp >= WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND -> WindowWidth.Expanded
    widthDp >= WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND -> WindowWidth.Medium
    else -> WindowWidth.Compact
}
