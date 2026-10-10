package io.lightine.renovo.internal

import android.app.Activity
import io.lightine.renovo.UpdateInfo

/**
 * Shows and removes the "update ready" notification.
 *
 * The implementing task must guarantee:
 * - [showReady] shows nothing unless the system already allows notifications for the app. It never
 *   asks for the permission. Tapping the notification opens the launcher activity; it never installs.
 * - A dedicated channel on API 26 and up, with the texts from the UI spec.
 * - [requestPermission] asks for `POST_NOTIFICATIONS` on API 33 and up, and does nothing below.
 *
 * The runtime decides whether the app is in the background and whether the setting is on.
 */
internal interface Notifier {
    fun showReady(info: UpdateInfo)

    fun cancelReady()

    fun requestPermission(activity: Activity)
}
