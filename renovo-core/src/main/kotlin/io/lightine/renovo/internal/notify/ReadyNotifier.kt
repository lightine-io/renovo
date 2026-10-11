package io.lightine.renovo.internal.notify

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.lightine.renovo.R
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.internal.Notifier

/**
 * Posts the "update ready" notification through the AndroidX helpers.
 *
 * The helpers are used because the platform has no notification builder without deprecated API on
 * Android 7.x. They also handle notification channels, which exist only from Android 8.
 *
 * The small icon is the library drawable `renovo_ic_update_ready`. A host app replaces it by defining
 * a drawable with the same name.
 */
internal class ReadyNotifier(
    context: Context,
    private val logger: RenovoLogger?,
) : Notifier {
    // The application context cannot leak an activity and outlives every screen.
    private val appContext: Context = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    // Android silently drops a notification from a blocked app or channel. Checking first lets
    // the log say why nothing appeared. areNotificationsEnabled() already implies the Android 13
    // permission, but lint wants to see the permission check next to notify(), so it is explicit.
    override fun showReady(info: UpdateInfo) {
        val permitted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!permitted || !manager.areNotificationsEnabled()) {
            logger?.log("Update ready notification skipped: notifications are not allowed.", null)
            return
        }
        // Creating the channel again with the same values changes nothing, so it is done on every
        // call. That also brings it back if the host app deleted it. Below API 26 the call does nothing.
        manager.createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(appContext.getString(R.string.renovo_notification_channel_name))
                .build(),
        )
        // The user can switch a single channel off in the system settings. The result is null below API 26.
        val channel = manager.getNotificationChannelCompat(CHANNEL_ID)
        if (channel != null && channel.importance == NotificationManagerCompat.IMPORTANCE_NONE) {
            logger?.log("Update ready notification skipped: the user turned the channel off.", null)
            return
        }

        val builder =
            NotificationCompat
                .Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.renovo_ic_update_ready)
                .setContentTitle(appContext.getString(R.string.renovo_notification_ready_title))
                .setContentText(appContext.getString(R.string.renovo_notification_ready_text, info.versionName))
                .setAutoCancel(true)
        val tapIntent = launcherPendingIntent(info)
        if (tapIntent != null) {
            builder.setContentIntent(tapIntent)
        } else {
            logger?.log("The host app has no launcher activity. The notification has no tap action.", null)
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    override fun cancelReady() {
        manager.cancel(NOTIFICATION_ID)
    }

    override fun requestPermission(activity: Activity) {
        // Below API 33 notifications need no runtime permission.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_REQUEST_CODE)
    }

    private fun launcherPendingIntent(info: UpdateInfo): PendingIntent? {
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName) ?: return null
        launchIntent.putExtra(EXTRA_READY_VERSION_CODE, info.versionCode)
        // Immutable, because nothing needs to fill in the intent later. Update-current keeps the
        // extra above in step with the latest notification.
        return PendingIntent.getActivity(
            appContext,
            REQUEST_CODE,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    internal companion object {
        const val CHANNEL_ID = "renovo_updates"

        // One fixed id, so a newer version replaces the older notification instead of stacking.
        const val NOTIFICATION_ID = 0x52454E

        const val PERMISSION_REQUEST_CODE = 0x5245

        private const val REQUEST_CODE = 0

        /**
         * Extra on the launcher intent: a Long with the version code of the update that the
         * notification announced. A host app that reads its launcher intent can use it to tell
         * which version the notification was about. The update UI does not read it.
         */
        const val EXTRA_READY_VERSION_CODE = "io.lightine.renovo.READY_VERSION_CODE"
    }
}
