package io.lightine.renovo.internal.install

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.internal.ForegroundTracker
import io.lightine.renovo.internal.InstallPermission
import io.lightine.renovo.internal.PermissionGate

/**
 * API 24 and 25: installing from outside a store is one device-wide "Unknown sources" switch in
 * the security settings. Apps can read it but not change it.
 */
internal class LegacyPermissionGate(
    private val context: Context,
    private val foreground: ForegroundTracker,
    private val logger: RenovoLogger?,
    private val prerequisites: InstallPrerequisites = InstallPrerequisites(context, logger),
) : PermissionGate {
    override fun status(): InstallPermission = when {
        prerequisites.restricted() -> InstallPermission.Unsupported
        !prerequisites.permissionDeclared -> InstallPermission.Unsupported
        unknownSourcesAllowed() -> InstallPermission.Granted
        else -> InstallPermission.NeedsUser
    }

    // The Android 7.1 package installer opens this screen from its own "Unknown sources" dialog.
    override fun openSettings(): Boolean = startFromResumedActivity(foreground, Intent(Settings.ACTION_SECURITY_SETTINGS), logger)

    // INSTALL_NON_MARKET_APPS is deprecated from API 26 only, where canRequestPackageInstalls
    // replaces it and the setting always reads 1. The compiler checks against the newest SDK and
    // cannot see the version guard, so the warning is suppressed in this one function, whose own
    // guard keeps the read off API 26 and up even if this class were chosen wrongly.
    @Suppress("DEPRECATION")
    private fun unknownSourcesAllowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O &&
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
}

/** API 26 and up: a per-app "Install unknown apps" permission that the user can revoke at any time. */
@RequiresApi(Build.VERSION_CODES.O)
internal class PerAppPermissionGate(
    private val context: Context,
    private val foreground: ForegroundTracker,
    private val logger: RenovoLogger?,
    private val prerequisites: InstallPrerequisites = InstallPrerequisites(context, logger),
) : PermissionGate {
    override fun status(): InstallPermission = when {
        prerequisites.restricted() -> InstallPermission.Unsupported

        // Checked first because canRequestPackageInstalls needs the declaration.
        !prerequisites.permissionDeclared -> InstallPermission.Unsupported

        context.packageManager.canRequestPackageInstalls() -> InstallPermission.Granted

        else -> InstallPermission.NeedsUser
    }

    // The package URI opens the switch for this app directly instead of the list of all apps.
    override fun openSettings(): Boolean = startFromResumedActivity(
        foreground,
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", context.packageName, null)),
        logger,
    )
}
