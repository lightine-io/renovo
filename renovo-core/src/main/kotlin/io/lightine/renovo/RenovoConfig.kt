package io.lightine.renovo

import io.lightine.renovo.internal.engine.EngineSettings
import io.lightine.renovo.internal.isHttpsUrl
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

// The constructor is internal, not private. A private constructor called from the nested Builder
// would make Kotlin add a hidden public method, and that method would become part of the public API.
/**
 * The settings passed to [Renovo.init]. They cannot change after init.
 *
 * Read them back through [Renovo.config].
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
    /**
     * URL of the version file, or null when [versionSource] is used.
     *
     * ```
     * val url = Renovo.config.versionUrl
     * ```
     */
    public val versionUrl: String?,
    /**
     * The host app's own version source, or null when [versionUrl] is used.
     *
     * ```
     * val usesOwnSource = Renovo.config.versionSource != null
     * ```
     */
    public val versionSource: VersionSource?,
    /**
     * Returns the extra headers for the version file request.
     *
     * ```
     * val headers = Renovo.config.versionHeaders()
     * ```
     */
    public val versionHeaders: () -> Map<String, String>,
    /**
     * Time between background checks.
     *
     * ```
     * val hours = Renovo.config.checkInterval.inWholeHours
     * ```
     */
    public val checkInterval: Duration,
    /**
     * True when a newer version downloads without waiting for the user.
     *
     * ```
     * if (!Renovo.config.autoDownload) showDownloadButton()
     * ```
     */
    public val autoDownload: Boolean,
    /**
     * The networks an optional update may download on.
     *
     * ```
     * val wifiOnly = Renovo.config.optionalDownloadNetwork == Network.UnmeteredOnly
     * ```
     */
    public val optionalDownloadNetwork: Network,
    /**
     * The networks a forced update may download on.
     *
     * ```
     * val wifiOnly = Renovo.config.forcedDownloadNetwork == Network.UnmeteredOnly
     * ```
     */
    public val forcedDownloadNetwork: Network,
    /**
     * True when the user may download a waiting forced update with mobile data.
     *
     * ```
     * val offerMobileData = Renovo.config.allowMobileDataOverride
     * ```
     */
    public val allowMobileDataOverride: Boolean,
    /**
     * True when Renovo shows the "update ready" notification. It is shown only when notifications
     * are already allowed.
     *
     * ```
     * if (Renovo.config.notifications) Renovo.requestNotificationPermission(this)
     * ```
     */
    public val notifications: Boolean,
    /**
     * Asked right before every install. False postpones the install.
     *
     * ```
     * val safeNow = Renovo.config.canInstallNow()
     * ```
     */
    public val canInstallNow: () -> Boolean,
    /**
     * Receives diagnostic messages, or null when logging is off.
     *
     * ```
     * val loggingOn = Renovo.config.logger != null
     * ```
     */
    public val logger: RenovoLogger?,
    /**
     * Settings for the update UI.
     *
     * ```
     * val dark = Renovo.config.ui.themeMode == ThemeMode.Dark
     * ```
     */
    public val ui: UiSettings,
) {
    /** The same settings with another logger. The runtime uses it to put in a logger that never throws. */
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
         * Extra headers for the version file request. Renovo calls this before every request, so a
         * refreshed token is always used. Default: no extra headers.
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
         * When false, the download starts only when you call [Renovo.startDownload].
         *
         * ```
         * autoDownload = false
         * ```
         */
        public var autoDownload: Boolean = true

        /**
         * The networks an optional update may download on. Default [Network.Any].
         *
         * ```
         * optionalDownloadNetwork = Network.UnmeteredOnly
         * ```
         */
        public var optionalDownloadNetwork: Network = Network.Any

        /**
         * The networks a forced update may download on. Default [Network.Any].
         *
         * ```
         * forcedDownloadNetwork = Network.NotRoaming
         * ```
         */
        public var forcedDownloadNetwork: Network = Network.Any

        /**
         * Lets the user download a forced update with mobile data while it waits for an allowed
         * network. It only matters when [forcedDownloadNetwork] is not [Network.Any]. Default true.
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
         * would lose work, for example while unsent data exists. The install then waits.
         * Default: always true.
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

        // Internal, so the only way to build settings is through Renovo.init.
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
