package io.lightine.renovo

import io.lightine.renovo.internal.isHttpsUrl

/**
 * Describes the latest version: what the version file says, or what a [VersionSource] returns.
 *
 * The constructor checks its values. It throws [IllegalArgumentException] in any of these cases:
 * - [versionCode] is below 1.
 * - [versionName] is blank.
 * - [apkUrl] is not an https URL.
 * - [sha256] is not 64 hexadecimal characters.
 * - [size] is not positive.
 *
 * ```
 * val info = UpdateInfo(
 *     versionCode = 42,
 *     versionName = "1.8.0",
 *     apkUrl = "https://example.com/app/app-1.8.0.apk",
 *     mandatory = false,
 *     minSupportedVersionCode = 38,
 *     releaseNotes = ReleaseNotes("- Bug fixes"),
 * )
 * Renovo.submit(info)
 * ```
 *
 * @param sha256 SHA-256 hash of the APK in hexadecimal, in either case. Stored in lowercase.
 */
public class UpdateInfo(
    /** Version code of the latest APK. */
    public val versionCode: Long,
    /** Version name shown to the user, such as "1.8.0". */
    public val versionName: String,
    /** HTTPS URL of the APK. */
    public val apkUrl: String,
    sha256: String? = null,
    /** APK size in bytes, used for the progress display, or null when not known. */
    public val size: Long? = null,
    /** True makes this update a forced update, which the user cannot postpone. */
    public val mandatory: Boolean = false,
    /** Installed versions below this version code are forced to update. Null means no minimum. */
    public val minSupportedVersionCode: Long? = null,
    /** What is new in this version, or null when there are no notes. */
    public val releaseNotes: ReleaseNotes? = null,
) {
    /** SHA-256 hash of the APK in lowercase hexadecimal, or null when the file's hash is not checked. */
    public val sha256: String? = sha256?.lowercase()

    init {
        require(versionCode >= 1) { "versionCode must be 1 or higher, got $versionCode." }
        require(versionName.isNotBlank()) { "versionName must not be blank." }
        require(isHttpsUrl(apkUrl)) { "apkUrl must be an https URL with a host, got \"$apkUrl\"." }
        require(this.sha256 == null || SHA256_PATTERN.matches(this.sha256)) {
            "sha256 must be 64 hexadecimal characters."
        }
        require(size == null || size > 0) { "size must be positive when given, got $size." }
    }

    override fun equals(other: Any?): Boolean = other is UpdateInfo &&
        other.versionCode == versionCode &&
        other.versionName == versionName &&
        other.apkUrl == apkUrl &&
        other.sha256 == sha256 &&
        other.size == size &&
        other.mandatory == mandatory &&
        other.minSupportedVersionCode == minSupportedVersionCode &&
        other.releaseNotes == releaseNotes

    override fun hashCode(): Int {
        var result = versionCode.hashCode()
        result = 31 * result + versionName.hashCode()
        result = 31 * result + apkUrl.hashCode()
        result = 31 * result + sha256.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + mandatory.hashCode()
        result = 31 * result + minSupportedVersionCode.hashCode()
        result = 31 * result + releaseNotes.hashCode()
        return result
    }

    override fun toString(): String = "UpdateInfo(versionCode=$versionCode, versionName=$versionName, apkUrl=$apkUrl, sha256=$sha256, " +
        "size=$size, mandatory=$mandatory, minSupportedVersionCode=$minSupportedVersionCode, " +
        "releaseNotes=$releaseNotes)"

    private companion object {
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}
