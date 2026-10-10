package io.lightine.renovo.internal.install

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.internal.ForegroundTracker

/**
 * The checks both permission gates share: whether a policy forbids installs, and whether the host
 * app declares the permission at all. Neither can be fixed by the user, so both mean "unsupported".
 */
internal class InstallPrerequisites(
    private val context: Context,
    private val logger: RenovoLogger?,
) {
    /**
     * True when a user restriction forbids this install. The user cannot lift a restriction from
     * the install screen, so asking would only lead nowhere.
     */
    fun restricted(): Boolean {
        val userManager = context.getSystemService(UserManager::class.java) ?: return false
        if (userManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)) return true
        if (userManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS)) return true
        // Android 17's Advanced Protection Mode blocks unknown sources through this restriction.
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            userManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
    }

    /**
     * True when the merged manifest requests `REQUEST_INSTALL_PACKAGES`. Renovo's own manifest adds
     * it, so false means the host app's build removed it, which is a programming error.
     */
    val permissionDeclared: Boolean by lazy {
        val requested =
            try {
                packageInfoWithPermissions().requestedPermissions
            } catch (e: PackageManager.NameNotFoundException) {
                logger?.log("Could not read the app's own package information.", e)
                null
            }
        val declared = requested?.contains(Manifest.permission.REQUEST_INSTALL_PACKAGES) == true
        if (!declared) {
            logger?.log(
                "REQUEST_INSTALL_PACKAGES is missing from the merged manifest. Updates cannot be " +
                    "installed. Check that the build does not remove the permission Renovo declares.",
                null,
            )
        }
        declared
    }

    // The int-flags overload is not deprecated; the PackageInfoFlags one only adds room for long
    // flags, which this read does not need, so one call serves every API level.
    private fun packageInfoWithPermissions(): PackageInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
}

/**
 * Starts [intent] from the resumed activity. False when no activity of the host app is resumed or
 * when no screen handles the intent; the reference warns that some settings screens may not exist.
 */
internal fun startFromResumedActivity(
    foreground: ForegroundTracker,
    intent: Intent,
    logger: RenovoLogger?,
): Boolean {
    val activity: Activity =
        foreground.resumedActivity ?: run {
            logger?.log("No activity is resumed, so the settings screen was not opened.", null)
            return false
        }
    return try {
        activity.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        logger?.log("This device has no screen for ${intent.action}.", e)
        false
    }
}
