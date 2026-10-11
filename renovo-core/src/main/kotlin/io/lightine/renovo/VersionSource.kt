package io.lightine.renovo

/**
 * Supplies the latest version from the host app's own API, instead of a version file.
 *
 * Renovo calls [latest] on a background thread for every check. Throw to report a failed check:
 * an [IllegalArgumentException], which [UpdateInfo] throws for a value it rejects, counts as
 * version information that could not be read; anything else counts as a network failure. A
 * background check that fails for the network is retried with increasing delays.
 *
 * ```
 * Renovo.init(this) {
 *     versionSource = VersionSource {
 *         val r = api.latestVersion()
 *         UpdateInfo(
 *             versionCode = r.build,
 *             versionName = r.name,
 *             apkUrl = r.downloadUrl,
 *         )
 *     }
 * }
 * ```
 */
public fun interface VersionSource {
    /**
     * Returns the latest version that is available.
     *
     * ```
     * val source = VersionSource { UpdateInfo(42, "1.8.0", "https://example.com/app-1.8.0.apk") }
     * ```
     */
    public suspend fun latest(): UpdateInfo
}
