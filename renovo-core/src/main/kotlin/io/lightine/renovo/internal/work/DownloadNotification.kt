package io.lightine.renovo.internal.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import io.lightine.renovo.R
import java.util.UUID

/**
 * The progress notification that Android requires while a download runs as a foreground service.
 *
 * The small icon is the library drawable `renovo_ic_download`. A host app replaces it by defining a
 * drawable with the same name.
 */
internal class DownloadNotification(
    private val context: Context,
    private val workId: UUID,
) {
    init {
        // From API 26 a notification without a channel is dropped. The compat class does nothing below.
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.renovo_download_channel_name))
                .build(),
        )
    }

    fun foregroundInfo(
        bytes: Long,
        total: Long?,
    ): ForegroundInfo {
        val text =
            if (total == null) {
                Formatter.formatShortFileSize(context, bytes)
            } else {
                context.getString(
                    R.string.renovo_download_notification_progress,
                    Formatter.formatShortFileSize(context, bytes),
                    Formatter.formatShortFileSize(context, total),
                )
            }
        // The bar counts in kilobytes: Int is too small for the bytes of a large file.
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.renovo_ic_download)
                .setContentTitle(context.getString(R.string.renovo_download_notification_title))
                .setContentText(text)
                .setProgress(
                    total?.let { (it / BAR_UNIT).toInt().coerceAtLeast(1) } ?: 0,
                    total?.let { (bytes / BAR_UNIT).toInt() } ?: 0,
                    total == null,
                ).setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    context.getString(android.R.string.cancel),
                    WorkManager.getInstance(context).createCancelPendingIntent(workId),
                ).build()
        // The type tells the system, and WorkManager's timeout handling, which budget this service uses.
        // Passing a type is only legal from API 29.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    internal companion object {
        const val CHANNEL_ID = "renovo_downloads"

        // Any positive number; one download runs at a time, so one id is enough.
        const val NOTIFICATION_ID = 7_201

        private const val BAR_UNIT = 1024L
    }
}
