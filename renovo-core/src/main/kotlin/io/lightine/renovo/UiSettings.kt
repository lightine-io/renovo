package io.lightine.renovo

// Internal rather than private: a private constructor called from the nested class would get a
// public synthetic accessor, which would leak into the binary API.
/**
 * Settings for the update UI. `renovo-ui` reads them; `renovo-core` ignores them.
 *
 * ```
 * Renovo.init(this) {
 *     versionUrl = "https://example.com/app/version.json"
 *     ui {
 *         themeMode = ThemeMode.System
 *         dynamicColor = false
 *     }
 * }
 * ```
 */
public class UiSettings internal constructor(
    /** Light, dark, or following the system. Default [ThemeMode.System]. */
    public val themeMode: ThemeMode,
    /** True uses the wallpaper-based colors on Android 12 and later. Default true. */
    public val dynamicColor: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is UiSettings && other.themeMode == themeMode && other.dynamicColor == dynamicColor

    override fun hashCode(): Int = 31 * themeMode.hashCode() + dynamicColor.hashCode()

    override fun toString(): String = "UiSettings(themeMode=$themeMode, dynamicColor=$dynamicColor)"

    /**
     * Collects the update UI settings inside `ui { }`.
     *
     * ```
     * ui { themeMode = ThemeMode.Dark }
     * ```
     */
    public class Builder internal constructor() {
        /**
         * Light, dark, or following the system. Default [ThemeMode.System].
         *
         * ```
         * ui { themeMode = ThemeMode.Light }
         * ```
         */
        public var themeMode: ThemeMode = ThemeMode.System

        /**
         * Uses the wallpaper-based colors on Android 12 and later. Default true.
         *
         * ```
         * ui { dynamicColor = false }
         * ```
         */
        public var dynamicColor: Boolean = true

        internal fun build(): UiSettings = UiSettings(themeMode, dynamicColor)
    }
}
