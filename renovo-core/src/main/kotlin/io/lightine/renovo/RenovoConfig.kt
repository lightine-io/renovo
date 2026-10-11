package io.lightine.renovo

import io.lightine.renovo.internal.engine.EngineSettings
import io.lightine.renovo.internal.isHttpsUrl
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

// Internal rather than private: a private constructor called from the nested class would get a
// public synthetic accessor, which would leak into the binary API.
/**
 * The settings passed to [Renovo.init]. Immutable once built.
 *
 * ```
 * Renovo.init(this) {
 *     versionUrl = "https://example.com/app/version.json"
 *     checkInterval = 12.hours
 *     canInstallNow = { !repository.hasUnsentData() }
 * }
 * ```
 */
public class RenovoConfig internal constructor(
    /** URL of the version file, or null when [versionSource] is used. */
    public val versionUrl: String?,
    /** The host app's own version source, or null when [versionUrl] is used. */
    public val versionSource: VersionSource?,
    /** Extra headers for the version file request. */
    public val versionHeaders: () -> Map<String, String>,
    /** Time between background checks. */
    public val checkInterval: Duration,
    /** True downloads a newer version without waiting for the user. */
    public val autoDownload: Boolean,
    /** Networks an optional update may download on. */
    public val optionalDownloadNetwork: Network,
    /** Networks a forced update may download on. */
    public val forcedDownloadNetwork: Network,
    /** True lets the user download a waiting forced update with mobile data. */
    public val allowMobileDataOverride: Boolean,
    /** True shows the "update ready" notification when notifications are already allowed. */
    public val notifications: Boolean,
    /** Asked right before every install; false postpones the install. */
    public val canInstallNow: () -> Boolean,
    /** Receives diagnostic messages, or null for no logging. */
    public val logger: RenovoLogger?,
    /** Settings for the update UI. */
    public val ui: UiSettings,
) {
    /** The same settings with another logger. The runtime uses it to swap in a logger that cannot throw. */
    internal fun withLogger(logger: RenovoLogger?): RenovoConfig = RenovoConfig(
        versionUrl = versionUrl,
        versionSource = versionSource,
        versionHeaders = versionHeaders,
        checkInterval = checkInterval,
        autoDownload = autoDownload,
        optionalDownloadNetwork = optionalDownloadNetwork,
        forcedDownloadNetwork = forcedDownloadNetwork,
        allowMobileDataOverride = allowMobileDataOverride,
        notifications = notifications,
        canInstallNow = canInstallNow,
        logger = logger,
        ui = ui,
    )

    internal fun engineSettings(): EngineSettings = EngineSettings(
        autoDownload = autoDownload,
        optionalDownloadNetwork = optionalDownloadNetwork,
        forcedDownloadNetwork = forcedDownloadNetwork,
        allowMobileDataOverride = allowMobileDataOverride,
        notifications = notifications,
        logger = logger,
    )

    /**
     * Collects the settings inside the [Renovo.init] block.
     *
     * Exactly one of [versionUrl] and [versionSource] must be set. Everything else has a default.
     *
     * ```
     * Renovo.init(this) {
     *     versionUrl = "https://example.com/app/version.json"
     * }
     * ```
     */
    public class Builder internal constructor() {
        /**
         * URL of the version file. Must be https. Set this or [versionSource], not both.
         *
         * ```
         * versionUrl = "https://example.com/app/version.json"
         * ```
         */
        public var versionUrl: String? = null

        /**
         * The host app's own source of version information. Set this or [versionUrl], not both.
         *
         * ```
         * versionSource = VersionSource { api.latestVersion().toUpdateInfo() }
         * ```
         */
        public var versionSource: VersionSource? = null

        /**
         * Extra headers for the version file request, read before every request. Default: none.
         *
         * ```
         * versionHeaders = { mapOf("Authorization" to "Bearer ${tokens.current()}") }
         * ```
         */
        public var versionHeaders: () -> Map<String, String> = { emptyMap() }

        /**
         * Time between background checks. Default 6 hours, minimum 15 minutes.
         *
         * ```
         * checkInterval = 12.hours
         * ```
         */
        public var checkInterval: Duration = DEFAULT_CHECK_INTERVAL

        /**
         * Downloads a newer version without waiting for the user. Default true.
         * When false, the download starts only after [Renovo.startDownload].
         *
         * ```
         * autoDownload = false
         * ```
         */
        public var autoDownload: Boolean = true

        /**
         * Networks an optional update may download on. Default [Network.Any].
         *
         * ```
         * optionalDownloadNetwork = Network.UnmeteredOnly
         * ```
         */
        public var optionalDownloadNetwork: Network = Network.Any

        /**
         * Networks a forced update may download on. Default [Network.Any].
         *
         * ```
         * forcedDownloadNetwork = Network.NotRoaming
         * ```
         */
        public var forcedDownloadNetwork: Network = Network.Any

        /**
         * Lets the user download a forced update with mobile data while it waits for an allowed
         * network. Default true.
         *
         * ```
         * allowMobileDataOverride = false
         * ```
         */
        public var allowMobileDataOverride: Boolean = true

        /**
         * Shows the "update ready" notification when the app is in the background and
         * notifications are already allowed. Renovo never asks for the permission itself.
         * Default true.
         *
         * ```
         * notifications = false
         * ```
         */
        public var notifications: Boolean = true

        /**
         * Asked on the main thread right before every install. Return false while installing
         * would lose work, for example while unsent data exists. Default: always true.
         *
         * ```
         * canInstallNow = { !repository.hasUnsentData() }
         * ```
         */
        public var canInstallNow: () -> Boolean = { true }

        /**
         * Receives diagnostic messages. Default null, which turns logging off.
         *
         * ```
         * logger = RenovoLogger { message, error -> Log.d("Renovo", message, error) }
         * ```
         */
        public var logger: RenovoLogger? = null

        private val uiBuilder = UiSettings.Builder()

        /**
         * Sets how the update UI looks. Read by `renovo-ui` only.
         *
         * ```
         * ui {
         *     themeMode = ThemeMode.Dark
         *     dynamicColor = false
         * }
         * ```
         */
        public fun ui(configure: UiSettings.Builder.() -> Unit) {
            uiBuilder.configure()
        }

        // Internal so that the only way to get a configuration is through Renovo.init.
        internal fun build(): RenovoConfig {
            val url = versionUrl
            val source = versionSource
            require(url == null || source == null) { "Set either versionUrl or versionSource, not both." }
            require(url != null || source != null) { "Set versionUrl or versionSource." }
            require(url == null || isHttpsUrl(url)) { "versionUrl must be an https URL with a host, got \"$url\"." }
            // WorkManager does not run periodic work more often than every 15 minutes.
            require(checkInterval >= MIN_CHECK_INTERVAL) {
                "checkInterval must be at least $MIN_CHECK_INTERVAL, got $checkInterval."
            }
            return RenovoConfig(
                versionUrl = url,
                versionSource = source,
                versionHeaders = versionHeaders,
                checkInterval = checkInterval,
                autoDownload = autoDownload,
                optionalDownloadNetwork = optionalDownloadNetwork,
                forcedDownloadNetwork = forcedDownloadNetwork,
                allowMobileDataOverride = allowMobileDataOverride,
                notifications = notifications,
                canInstallNow = canInstallNow,
                logger = logger,
                ui = uiBuilder.build(),
            )
        }
    }

    internal companion object {
        val DEFAULT_CHECK_INTERVAL: Duration = 6.hours
        val MIN_CHECK_INTERVAL: Duration = 15.minutes
    }
}
