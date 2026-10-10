package io.lightine.renovo.internal.install

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.UserManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import io.lightine.renovo.internal.InstallPermission
import io.lightine.renovo.internal.PermissionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

// The way a device policy app sets a restriction. Robolectric stores it for UserManager to report.
private fun restrict(
    context: Context,
    restriction: String,
) {
    val admin = ComponentName(context, "io.lightine.renovo.test.Admin")
    val devicePolicy = context.getSystemService(DevicePolicyManager::class.java)
    shadowOf(devicePolicy).setActiveAdmin(admin)
    devicePolicy.addUserRestriction(admin, restriction)
}

private fun removeInstallPermissionFromManifest(context: Context) {
    val info = shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName)
    info.requestedPermissions = info.requestedPermissions?.filter { it != Manifest.permission.REQUEST_INSTALL_PACKAGES }?.toTypedArray()
}

private fun resumedActivity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 25])
class LegacyPermissionGateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val logger = RecordingLogger()

    private fun gate(foreground: FakeForeground = FakeForeground()): PermissionGate = LegacyPermissionGate(context, foreground, logger)

    @Test
    fun `the factory picks the device-wide setting below API 26`() {
        assertTrue(InstallerFactory.permissionGate(context, FakeForeground(), null) is LegacyPermissionGate)
    }

    @Test
    fun `the permission follows the unknown sources setting and is read fresh`() {
        val gate = gate()
        ShadowSettings.setInstallNonMarketApps(false)
        assertEquals(InstallPermission.NeedsUser, gate.status())
        ShadowSettings.setInstallNonMarketApps(true)
        assertEquals(InstallPermission.Granted, gate.status())
        ShadowSettings.setInstallNonMarketApps(false)
        assertEquals(InstallPermission.NeedsUser, gate.status())
    }

    @Test
    fun `a restriction on unknown sources makes the device unsupported`() {
        ShadowSettings.setInstallNonMarketApps(true)
        restrict(context, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        assertEquals(InstallPermission.Unsupported, gate().status())
    }

    @Test
    fun `a restriction on installing apps makes the device unsupported`() {
        ShadowSettings.setInstallNonMarketApps(true)
        restrict(context, UserManager.DISALLOW_INSTALL_APPS)
        assertEquals(InstallPermission.Unsupported, gate().status())
    }

    @Test
    fun `a missing manifest permission is unsupported and logged`() {
        ShadowSettings.setInstallNonMarketApps(true)
        removeInstallPermissionFromManifest(context)
        assertEquals(InstallPermission.Unsupported, gate().status())
        assertTrue(logger.contains("REQUEST_INSTALL_PACKAGES is missing"))
    }

    @Test
    fun `opening the settings starts the security settings from the resumed activity`() {
        val activity = resumedActivity()
        assertTrue(gate(FakeForeground(activity)).openSettings())
        assertEquals(Settings.ACTION_SECURITY_SETTINGS, shadowOf(activity).nextStartedActivity.action)
    }

    @Test
    fun `opening the settings fails without a resumed activity`() {
        assertFalse(gate(FakeForeground()).openSettings())
    }

    @Test
    fun `opening the settings fails when the screen does not exist`() {
        val activity = Robolectric.buildActivity(NoScreensActivity::class.java).get()
        assertFalse(gate(FakeForeground(activity)).openSettings())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 29, 33, 37])
class PerAppPermissionGateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val logger = RecordingLogger()

    private fun gate(foreground: FakeForeground = FakeForeground()): PermissionGate = PerAppPermissionGate(context, foreground, logger)

    private fun allowInstalls(allowed: Boolean) {
        shadowOf(context.packageManager).setCanRequestPackageInstalls(allowed)
    }

    @Test
    fun `the factory picks the per-app permission from API 26`() {
        assertTrue(InstallerFactory.permissionGate(context, FakeForeground(), null) is PerAppPermissionGate)
    }

    @Test
    fun `the permission follows canRequestPackageInstalls and is read fresh`() {
        val gate = gate()
        allowInstalls(false)
        assertEquals(InstallPermission.NeedsUser, gate.status())
        allowInstalls(true)
        assertEquals(InstallPermission.Granted, gate.status())
        // The user can revoke it at any time.
        allowInstalls(false)
        assertEquals(InstallPermission.NeedsUser, gate.status())
    }

    @Test
    fun `a restriction on unknown sources makes the device unsupported`() {
        allowInstalls(true)
        restrict(context, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        assertEquals(InstallPermission.Unsupported, gate().status())
    }

    @Test
    fun `a restriction on installing apps makes the device unsupported`() {
        allowInstalls(true)
        restrict(context, UserManager.DISALLOW_INSTALL_APPS)
        assertEquals(InstallPermission.Unsupported, gate().status())
    }

    @Test
    @Config(sdk = [29, 37])
    fun `the device-wide restriction of API 29 makes the device unsupported`() {
        allowInstalls(true)
        restrict(context, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
        assertEquals(InstallPermission.Unsupported, gate().status())
    }

    @Test
    fun `a missing manifest permission is unsupported and logged`() {
        allowInstalls(true)
        removeInstallPermissionFromManifest(context)
        assertEquals(InstallPermission.Unsupported, gate().status())
        assertTrue(logger.contains("REQUEST_INSTALL_PACKAGES is missing"))
    }

    @Test
    fun `the library manifest declares the install permission`() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions.orEmpty().contains(Manifest.permission.REQUEST_INSTALL_PACKAGES))
        assertTrue(info.requestedPermissions.orEmpty().contains(Manifest.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION))
    }

    @Test
    fun `opening the settings starts the per-app screen for this package`() {
        val activity = resumedActivity()
        assertTrue(gate(FakeForeground(activity)).openSettings())
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, started.action)
        assertEquals(Uri.parse("package:${context.packageName}"), started.data)
    }

    @Test
    fun `opening the settings fails without a resumed activity`() {
        assertFalse(gate(FakeForeground()).openSettings())
    }

    @Test
    fun `opening the settings fails when the screen does not exist`() {
        val activity = Robolectric.buildActivity(NoScreensActivity::class.java).get()
        assertFalse(gate(FakeForeground(activity)).openSettings())
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
