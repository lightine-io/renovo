package io.lightine.renovo

import android.app.Activity
import android.content.Context
import io.lightine.renovo.internal.RenovoRuntime
import io.lightine.renovo.internal.RuntimeFactory
import io.lightine.renovo.internal.RuntimeHolder
import kotlinx.coroutines.flow.StateFlow

/**
 * The entry point of Renovo: lets a host app (the app that uses Renovo) update itself from a
 * version file, without the Play Store.
 *
 * Call [init] once in `Application.onCreate`. Every other member fails with
 * [IllegalStateException] until then.
 *
 * ```
 * class App : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         Renovo.init(this) {
 *             versionUrl = "https://example.com/app/version.json"
 *         }
 *     }
 * }
 * ```
 */
public object Renovo {
    // The facade holds no logic. Everything is forwarded to the runtime.
    private val holder = RuntimeHolder()

    // Replaced in tests of the wiring. Read only inside init.
    internal var runtimeFactory: RuntimeFactory = RuntimeFactory.Default

    // Workers and receivers are created by the system, so they cannot get the runtime through a
    // constructor. They read it here and do nothing when the host app has not called init yet.
    internal val runtimeOrNull: RenovoRuntime?
        get() = holder.runtimeOrNull

    /**
     * Starts Renovo with the settings in [configure]. Call it once, in `Application.onCreate`.
     *
     * Exactly one of `versionUrl` and `versionSource` must be set; invalid settings throw
     * [IllegalArgumentException]. A second call is ignored and logged.
     *
     * ```
     * Renovo.init(this) {
     *     versionUrl = "https://example.com/app/version.json"
     *     canInstallNow = { !repository.hasUnsentData() }
     * }
     * ```
     */
    public fun init(
        context: Context,
        configure: RenovoConfig.Builder.() -> Unit,
    ) {
        holder.initialize {
            val config = RenovoConfig.Builder().apply(configure).build()
            runtimeFactory.create(context.applicationContext, config)
        }
    }

    /**
     * The settings given to [init]. Read-only; the update UI reads `ui` and `canInstallNow` from it.
     *
     * ```
     * val dark = Renovo.config.ui.themeMode == ThemeMode.Dark
     * ```
     */
    public val config: RenovoConfig
        get() = holder.runtime.config

    /**
     * The current update state, updated as checks, downloads, and installs progress.
     *
     * ```
     * lifecycleScope.launch {
     *     Renovo.state.collect { state -> render(state) }
     * }
     * ```
     */
    public val state: StateFlow<UpdateState>
        get() = holder.runtime.state

    /**
     * Calls [listener] on the main thread with the current state, then after every change.
     * For callers that do not use coroutines.
     *
     * ```
     * Renovo.addListener { state -> render(state) }
     * ```
     */
    public fun addListener(listener: UpdateListener) {
        holder.runtime.addListener(listener)
    }

    /**
     * Stops calling [listener].
     *
     * ```
     * override fun onDestroy() {
     *     Renovo.removeListener(listener)
     *     super.onDestroy()
     * }
     * ```
     */
    public fun removeListener(listener: UpdateListener) {
        holder.runtime.removeListener(listener)
    }

    /**
     * Checks for a new version now, for example from a "Check for updates" button.
     *
     * Renovo also checks on app start and periodically. A failure of this check is reported as
     * [UpdateState.Failed] with `userInitiated = true`.
     *
     * ```
     * checkButton.setOnClickListener { Renovo.checkNow() }
     * ```
     */
    public fun checkNow() {
        holder.runtime.checkNow()
    }

    /**
     * Starts the download of the available version, or tries again after a failure.
     *
     * When the file is already downloaded and verified, this starts the install instead.
     *
     * ```
     * updateButton.setOnClickListener { Renovo.startDownload() }
     * ```
     */
    public fun startDownload() {
        holder.runtime.startDownload()
    }

    /**
     * Installs the downloaded, verified version, after asking `canInstallNow`.
     *
     * When the user must first allow this app to install updates, this opens that setting
     * instead; the install continues when the user comes back with the permission granted.
     * Does nothing when no verified file exists.
     *
     * ```
     * installButton.setOnClickListener { Renovo.installIfReady() }
     * ```
     */
    public fun installIfReady() {
        holder.runtime.installIfReady()
    }

    /**
     * Hands over version information the host app already has, for example from its own sync.
     * Same effect as a check that returned [info].
     *
     * ```
     * Renovo.submit(UpdateInfo(42, "1.8.0", "https://example.com/app/app-1.8.0.apk"))
     * ```
     */
    public fun submit(info: UpdateInfo) {
        holder.runtime.submit(info)
    }

    /**
     * Lets a waiting forced update download with mobile data, for this version only.
     *
     * Works only while the state is [UpdateState.Required] with
     * [UpdateStep.WaitingForNetwork.canUseMobileData] true. Does nothing otherwise.
     *
     * ```
     * mobileDataButton.setOnClickListener { Renovo.useMobileDataOnce() }
     * ```
     */
    public fun useMobileDataOnce() {
        holder.runtime.useMobileDataOnce()
    }

    /**
     * Asks the user to allow notifications, so the "update ready" notification can be shown.
     *
     * Renovo never asks on its own; the host app picks the moment. Does nothing on Android 12L
     * and earlier, where notifications need no runtime permission.
     *
     * ```
     * Renovo.requestNotificationPermission(activity)
     * ```
     */
    public fun requestNotificationPermission(activity: Activity) {
        holder.runtime.requestNotificationPermission(activity)
    }
}
