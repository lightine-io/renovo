package io.lightine.renovo.internal.runtime

import android.app.Application
import android.content.Context
import android.os.Build
import io.lightine.renovo.RenovoConfig
import io.lightine.renovo.internal.VersionFetcher
import io.lightine.renovo.internal.engine.EventSink
import io.lightine.renovo.internal.install.InstallerFactory
import io.lightine.renovo.internal.notify.ActivityForegroundTracker
import io.lightine.renovo.internal.notify.ReadyNotifier
import io.lightine.renovo.internal.store.PreferencesStore
import io.lightine.renovo.internal.verify.ApkVerifier
import io.lightine.renovo.internal.verify.PackageManagerInstalledApp
import io.lightine.renovo.internal.version.HttpVersionFetcher
import io.lightine.renovo.internal.version.SourceVersionFetcher
import io.lightine.renovo.internal.work.WorkDownloader
import io.lightine.renovo.internal.work.WorkScheduler
import kotlinx.coroutines.CoroutineScope

/** The real components, assembled in one place with plain constructor calls. */
internal object ProductionComponents {
    /**
     * @param events Where the components report. It is the runtime's queue, passed in because the
     *   components exist before the runtime does.
     * @param currentEtag The engine's current ETag, read right before every request.
     */
    fun create(
        context: Context,
        config: RenovoConfig,
        scope: CoroutineScope,
        events: EventSink,
        currentEtag: () -> String?,
    ): Components {
        val logger = config.logger
        // The tracker registers lifecycle callbacks, which only the Application can do. The
        // application context of a running app is always the Application.
        val application = context.applicationContext as? Application ?: error("The application context is not an Application.")
        val foreground = ActivityForegroundTracker(application, logger)
        val installedApp = PackageManagerInstalledApp(application)

        return Components(
            store = PreferencesStore(application, logger),
            foreground = foreground,
            notifier = ReadyNotifier(application, logger),
            scheduler = WorkScheduler(application, fetcherFor(config), events, currentEtag, scope, logger),
            downloader = WorkDownloader(application, events, foreground, scope, logger),
            verifier = ApkVerifier(installedApp, logger, platformSdk = Build.VERSION.SDK_INT),
            installer = InstallerFactory.installer(application, events, logger),
            permissionGate = InstallerFactory.permissionGate(application, foreground, logger),
            installedVersionCode = installedApp::versionCode,
        )
    }

    // RenovoConfig guarantees that exactly one of the two is set.
    private fun fetcherFor(config: RenovoConfig): VersionFetcher {
        val source = config.versionSource
        val url = config.versionUrl
        return when {
            source != null -> SourceVersionFetcher(source, config.logger)
            url != null -> HttpVersionFetcher(url, config.versionHeaders, config.logger)
            else -> error("RenovoConfig has neither a versionSource nor a versionUrl.")
        }
    }
}
