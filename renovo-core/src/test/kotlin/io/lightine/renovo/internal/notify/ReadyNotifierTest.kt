package io.lightine.renovo.internal.notify

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.UpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// One level below 26 (no channels), one in 26-32, and one at 33 and up (runtime permission).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 28, 33, 37])
class ReadyNotifierTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val logs = mutableListOf<String>()
    private val notifier = ReadyNotifier(application, RenovoLogger { message, _ -> logs += message })
    private val info =
        UpdateInfo(
            versionCode = 42,
            versionName = "1.8.0",
            apkUrl = "https://example.com/app.apk",
        )

    @Before
    fun setUp() {
        shadowOf(manager).setNotificationsEnabled(true)
        // On Android 13 and later the notifier also checks the runtime permission; a real host app
        // that lets notifications through has it granted, so the tests grant it too.
        shadowOf(application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun registerLauncherActivity() {
        val component = ComponentName(application.packageName, "${application.packageName}.MainActivity")
        val packageManager = shadowOf(application.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    @Test
    fun `posts the notification when notifications are enabled`() {
        notifier.showReady(info)

        assertNotNull(shadowOf(manager).getNotification(ReadyNotifier.NOTIFICATION_ID))
    }

    @Test
    fun `the small icon is the replaceable library drawable`() {
        notifier.showReady(info)

        val posted = checkNotNull(shadowOf(manager).getNotification(ReadyNotifier.NOTIFICATION_ID))
        // A host app replaces the icon by defining a drawable of this name, which only works if the
        // notification refers to the library resource and not to a framework drawable.
        assertEquals(io.lightine.renovo.R.drawable.renovo_ic_update_ready, posted.smallIcon.resId)
        assertEquals(application.packageName, posted.smallIcon.resPackage)
    }

    @Test
    fun `posts nothing when notifications are not enabled`() {
        shadowOf(manager).setNotificationsEnabled(false)

        notifier.showReady(info)

        assertNull(shadowOf(manager).getNotification(ReadyNotifier.NOTIFICATION_ID))
        assertTrue(logs.any { it.contains("not allowed") })
    }

    @Test
    fun `creates the channel on API 26 and up only`() {
        notifier.showReady(info)

        // The channel methods do not exist below API 26, so the call itself stays behind the check.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = manager.getNotificationChannel(ReadyNotifier.CHANNEL_ID)
            assertNotNull(channel)
            assertEquals("App updates", channel.name.toString())
            assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        } else {
            assertNotNull(shadowOf(manager).getNotification(ReadyNotifier.NOTIFICATION_ID))
        }
    }

    @Test
    fun `posting twice keeps one channel and one notification`() {
        notifier.showReady(info)
        notifier.showReady(info)

        assertEquals(1, shadowOf(manager).size())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            assertEquals(1, manager.notificationChannels.size)
        }
    }

    @Test
    fun `posts nothing when the user turned the channel off`() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // No channels below API 26, so there is nothing to turn off.
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(ReadyNotifier.CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_NONE),
        )

        notifier.showReady(info)

        assertNull(shadowOf(manager).getNotification(ReadyNotifier.NOTIFICATION_ID))
        assertTrue(logs.any { it.contains("channel off") })
    }

    @Test
    fun `title and text carry the version name`() {
        notifier.showReady(info)

        val extras = shadowOf(manager).getNotification(ReadyNotifier.NOTIFICATION_ID).extras
        assertEquals("Update ready", extras.getCharSequence("android.title").toString())
        assertEquals("Tap to install version 1.8.0.", extras.getCharSequence("android.text").toString())
    }

    @Test
    fun `cancels itself on tap`() {
        notifier.showReady(info)

        val notification = shadowOf(manager).getNotification(ReadyNotifier.NOTIFICATION_ID)
        assertTrue(notification.flags and android.app.Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun `tapping opens the launcher activity with the version code`() {
        registerLauncherActivity()

        notifier.showReady(info)

        val pendingIntent = shadowOf(manager).getNotification(ReadyNotifier.NOTIFICATION_ID).contentIntent
        assertNotNull(pendingIntent)
        val intent = shadowOf(pendingIntent).savedIntent
        assertEquals("${application.packageName}.MainActivity", intent.component?.className)
        assertEquals(42L, intent.getLongExtra(ReadyNotifier.EXTRA_READY_VERSION_CODE, -1))
        assertEquals("io.lightine.renovo.READY_VERSION_CODE", ReadyNotifier.EXTRA_READY_VERSION_CODE)
    }

    @Test
    fun `without a launcher activity it posts without an action and logs`() {
        notifier.showReady(info)

        val notification = shadowOf(manager).getNotification(ReadyNotifier.NOTIFICATION_ID)
        assertNotNull(notification)
        assertNull(notification.contentIntent)
        assertTrue(logs.any { it.contains("no launcher activity") })
    }

    @Test
    fun `cancelReady removes the notification`() {
        notifier.showReady(info)

        notifier.cancelReady()

        assertNull(shadowOf(manager).getNotification(ReadyNotifier.NOTIFICATION_ID))
    }

    @Test
    fun `cancelReady without a notification does nothing`() {
        notifier.cancelReady()

        assertEquals(0, shadowOf(manager).size())
    }

    @Test
    @Config(sdk = [33])
    fun `requestPermission asks for the notification permission on API 33`() {
        shadowOf(application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()

        notifier.requestPermission(activity)

        val request = shadowOf(activity).lastRequestedPermission
        assertNotNull(request)
        assertEquals(listOf("android.permission.POST_NOTIFICATIONS"), request.requestedPermissions.toList())
        assertEquals(ReadyNotifier.PERMISSION_REQUEST_CODE, request.requestCode)
    }

    @Test
    @Config(sdk = [33])
    fun `requestPermission does not ask again once the permission is granted`() {
        shadowOf(application).grantPermissions("android.permission.POST_NOTIFICATIONS")
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()

        notifier.requestPermission(activity)

        assertNull(shadowOf(activity).lastRequestedPermission)
    }

    @Test
    @Config(sdk = [32])
    fun `requestPermission does nothing on API 32`() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()

        notifier.requestPermission(activity)

        assertNull(shadowOf(activity).lastRequestedPermission)
    }

    @Test
    @Config(sdk = [24])
    fun `requestPermission does nothing on API 24`() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()

        notifier.requestPermission(activity)

        assertNull(shadowOf(activity).lastRequestedPermission)
    }
}
