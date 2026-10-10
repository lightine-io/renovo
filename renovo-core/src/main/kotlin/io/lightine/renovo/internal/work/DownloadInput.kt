package io.lightine.renovo.internal.work

import androidx.work.Data
import androidx.work.workDataOf
import io.lightine.renovo.UpdateInfo

/**
 * What a [DownloadWorker] needs to know, carried in the work request's input data.
 *
 * @property foreground True when the user started the download while the app was in the foreground.
 *   Decided when the request is enqueued, because that is the moment the platform allows a
 *   foreground service to start; the worker may run much later, from the background.
 */
internal data class DownloadInput(
    val versionCode: Long,
    val apkUrl: String,
    val size: Long?,
    val foreground: Boolean,
) {
    fun toData(): Data = workDataOf(
        KEY_VERSION_CODE to versionCode,
        KEY_URL to apkUrl,
        KEY_SIZE to (size ?: NO_SIZE),
        KEY_FOREGROUND to foreground,
    )

    companion object {
        private const val KEY_VERSION_CODE = "versionCode"
        private const val KEY_URL = "apkUrl"
        private const val KEY_SIZE = "size"
        private const val KEY_FOREGROUND = "foreground"
        private const val NO_SIZE = -1L

        fun of(
            info: UpdateInfo,
            foreground: Boolean,
        ): DownloadInput = DownloadInput(info.versionCode, info.apkUrl, info.size, foreground)

        /** Null when the data is not a download request, which only a corrupted work database can cause. */
        fun from(data: Data): DownloadInput? {
            val versionCode = data.getLong(KEY_VERSION_CODE, 0)
            val url = data.getString(KEY_URL)
            if (versionCode < 1 || url == null) return null
            val size = data.getLong(KEY_SIZE, NO_SIZE).takeIf { it > 0 }
            return DownloadInput(versionCode, url, size, data.getBoolean(KEY_FOREGROUND, false))
        }
    }
}

/** The tag that tells which version a unique download work belongs to. */
internal fun versionTag(versionCode: Long): String = "renovo-download-v$versionCode"

/** The version code in [tags], or null when none of them is a [versionTag]. */
internal fun versionCodeOf(tags: Set<String>): Long? = tags.firstNotNullOfOrNull { tag ->
    tag.removePrefix("renovo-download-v").takeIf { it.length < tag.length }?.toLongOrNull()
}
