package io.lightine.renovo.internal.verify

import java.io.File

/**
 * What the verifier needs to know about the host app as it is installed now.
 *
 * An interface so [ApkVerifier] runs in JVM tests without Android; the device implementation is
 * [PackageManagerInstalledApp].
 */
internal interface InstalledApp {
    /** The host app's package name. */
    val packageName: String

    /** The installed long version code (versionCodeMajor in the upper 32 bits). */
    fun versionCode(): Long

    /**
     * The encoded X.509 certificates the installed app is signed with now: one certificate
     * normally, several only for an app with several signers. Never the older certificates of a
     * rotated key: an APK signed with a key the app has rotated away from must not pass.
     */
    fun signingCertificates(): List<ByteArray>

    /** False when the system's own package parser cannot read [file] as an APK. */
    fun canParseArchive(file: File): Boolean
}
