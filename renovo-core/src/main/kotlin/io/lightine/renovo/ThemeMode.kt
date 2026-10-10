package io.lightine.renovo

/**
 * Whether the update UI uses a light or a dark theme.
 *
 * Read by `renovo-ui` only.
 *
 * ```
 * Renovo.init(this) {
 *     versionUrl = "https://example.com/app/version.json"
 *     ui { themeMode = ThemeMode.Dark }
 * }
 * ```
 */
public enum class ThemeMode {
    /** Follow the system setting. */
    System,

    /** Always use the light theme. */
    Light,

    /** Always use the dark theme. */
    Dark,
}
