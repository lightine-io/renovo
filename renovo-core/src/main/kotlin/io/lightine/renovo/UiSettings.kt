package io.lightine.renovo

// The constructor is internal, not private. A private constructor called from the nested Builder
// would make Kotlin add a hidden public method, and that method would become part of the public API.
/**
 * Settings for the update UI. `renovo-ui` reads them. `renovo-core` ignores them.
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
    /**
     * Light, dark, or following the system. Default [ThemeMode.System].
     *
     * ```
     * val dark = Renovo.config.ui.themeMode == ThemeMode.Dark
     * ```
     */
    public val themeMode: ThemeMode,
    /**
     * True lets the update UI use colors taken from the wallpaper on Android 12 and later.
     * Default true.
     *
     * ```
     * val wallpaperColors = Renovo.config.ui.dynamicColor
     * ```
     */
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
         * Lets the update UI use colors taken from the wallpaper on Android 12 and later. Default true.
         *
         * ```
         * ui { dynamicColor = false }
         * ```
         */
        public var dynamicColor: Boolean = true

        internal fun build(): UiSettings = UiSettings(themeMode, dynamicColor)
    }
}
