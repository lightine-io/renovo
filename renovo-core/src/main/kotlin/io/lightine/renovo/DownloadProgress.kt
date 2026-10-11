package io.lightine.renovo

/**
 * How much of the update's APK has been downloaded.
 *
 * ```
 * val percent = progress.totalBytes?.let { total -> progress.bytesDownloaded * 100 / total }
 * ```
 *
 * @property bytesDownloaded Bytes on the device so far, including bytes from an earlier, interrupted run.
 * @property totalBytes Size of the whole file, or null when it is not known yet.
 */
public class DownloadProgress(
    public val bytesDownloaded: Long,
    public val totalBytes: Long?,
) {
    init {
        require(bytesDownloaded >= 0) { "bytesDownloaded must not be negative, got $bytesDownloaded." }
        require(totalBytes == null || totalBytes > 0) { "totalBytes must be positive when known, got $totalBytes." }
    }

    override fun equals(other: Any?): Boolean = other is DownloadProgress &&
        other.bytesDownloaded == bytesDownloaded &&
        other.totalBytes == totalBytes

    override fun hashCode(): Int = 31 * bytesDownloaded.hashCode() + totalBytes.hashCode()

    override fun toString(): String = "DownloadProgress(bytesDownloaded=$bytesDownloaded, totalBytes=$totalBytes)"
}
