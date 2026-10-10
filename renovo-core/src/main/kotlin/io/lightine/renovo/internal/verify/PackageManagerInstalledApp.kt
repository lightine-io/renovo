package io.lightine.renovo.internal.verify

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File

/** [InstalledApp] backed by the system's PackageManager. */
internal class PackageManagerInstalledApp(
    context: Context,
) : InstalledApp {
    private val packageManager: PackageManager = context.packageManager

    override val packageName: String = context.packageName

    // The app's own package is always visible to it, so no <queries> entry is needed.
    override fun versionCode(): Long = PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))

    override fun signingCertificates(): List<ByteArray> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // No key rotation exists below API 28, so every certificate here is a current signer.
            // PackageInfoCompat reads them without Renovo touching the deprecated GET_SIGNATURES.
            return PackageInfoCompat.getSignatures(packageManager, packageName).map { it.toByteArray() }
        }
        // Not PackageInfoCompat.getSignatures from API 28: there it returns the whole rotation
        // history, and its documentation warns against identity checks with it. Accepting a past
        // certificate would let an APK signed with a key the app rotated away from pass.
        val signingInfo =
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                ?: return emptyList()
        return if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners.orEmpty().map { it.toByteArray() }
        } else {
            // "original at index 0 and current at the last index" (SigningInfo reference).
            listOfNotNull(signingInfo.signingCertificateHistory?.lastOrNull()?.toByteArray())
        }
    }

    // Flags 0: parse only. getPackageArchiveInfo(String, int) is not deprecated on the current
    // SDK (it was only in the API 33 SDK). It returns null when the system cannot parse the file.
    override fun canParseArchive(file: File): Boolean = packageManager.getPackageArchiveInfo(file.path, 0) != null
}
