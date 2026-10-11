package io.lightine.renovo

import android.app.Activity
import android.content.Context
import io.lightine.renovo.internal.RenovoRuntime
import io.lightine.renovo.internal.RuntimeFactory
import io.lightine.renovo.internal.RuntimeHolder
import io.lightine.renovo.internal.SafeLogger
import io.lightine.renovo.internal.isMainProcess
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
    // This object holds no logic. It passes every call on to the runtime.
    private val holder = RuntimeHolder()

    // Tests replace this to check how init builds the runtime. Read only inside init.
    internal var runtimeFactory: RuntimeFactory = RuntimeFactory.Default

    // Workers and receivers are created by the system, so they cannot get the runtime through a
    // constructor. They read it here and do nothing when the host app has not called init yet.
    internal val runtimeOrNull: RenovoRuntime?
        get() = holder.runtimeOrNull

    /**
     * Starts Renovo with the settings in [configure]. Call it once, in `Application.onCreate`.
     *
     * Set exactly one of `versionUrl` and `versionSource`. Invalid settings throw
     * [IllegalArgumentException]. A second call is ignored and logged.
     *
     * Only the host app's main process runs Renovo. The main process is the one named after the
     * package. In any other process, such as a `:remote` service process, this call does nothing,
     * so it is safe to call from `Application.onCreate` in every process. Invalid settings still
     * throw there, and the other members still throw [IllegalStateException] there.
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
        // Built before the process check, so a mistake in the settings fails in every process alike.
        val config = RenovoConfig.Builder().apply(configure).build()
        if (!isMainProcess(context)) {
            // Another process would build a second engine that overwrites the main one's stored state.
            SafeLogger.wrap(config.logger)?.log("Renovo.init was skipped: this is not the app's main process.", null)
            return
        }
        holder.initialize { runtimeFactory.create(context.applicationContext, config) }
    }

    /**
     * The settings given to [init]. Read-only. The update UI reads `ui` and `canInstallNow` from it.
     *
     * ```
     * val dark = Renovo.config.ui.themeMode == ThemeMode.Dark
     * ```
     */
    public val config: RenovoConfig
        get() = holder.runtime.config

    /**
     * The current update state. It changes as checks, downloads, and installs move on.
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
     * Calls [listener] on the main thread with the current update state, then after every change.
     * Use it when your code does not use coroutines.
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
     * Renovo also checks when the app starts and then every [RenovoConfig.checkInterval]. When
     * this check fails, the state becomes [UpdateState.Failed] with `userInitiated = true`.
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
     * When the user must first allow this app to install updates, this opens that setting.
     *
     * ```
     * updateButton.setOnClickListener { Renovo.startDownload() }
     * ```
     */
    public fun startDownload() {
        holder.runtime.startDownload()
    }

    /**
     * Installs the downloaded and verified version. It asks `canInstallNow` first.
     *
     * When the user must first allow this app to install updates, this opens that setting
     * instead. The install goes on when the user comes back with the permission granted.
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
     * Gives Renovo version information the host app already has, for example from its own sync.
     * It has the same effect as a check that returned [info].
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
     * Renovo never asks on its own. The host app picks the moment. Does nothing on Android 12L
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
