package io.lightine.renovo.internal.install

import android.content.Context
import android.os.Build
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.internal.ForegroundTracker
import io.lightine.renovo.internal.Installer
import io.lightine.renovo.internal.PermissionGate
import io.lightine.renovo.internal.engine.EventSink

/** Builds the install components for the running API level. Called once, when the runtime is built. */
internal object InstallerFactory {
    /** The gate for this device: the device-wide setting below API 26, the per-app permission from 26. */
    fun permissionGate(
        context: Context,
        foreground: ForegroundTracker,
        logger: RenovoLogger?,
    ): PermissionGate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        PerAppPermissionGate(context, foreground, logger)
    } else {
        LegacyPermissionGate(context, foreground, logger)
    }

    /** One installer for every level; it branches by API level inside. */
    fun installer(
        context: Context,
        events: EventSink,
        logger: RenovoLogger?,
    ): Installer = SessionInstaller(context, events, logger)
}
