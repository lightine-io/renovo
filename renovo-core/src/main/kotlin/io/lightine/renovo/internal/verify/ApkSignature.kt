package io.lightine.renovo.internal.verify

import java.security.cert.X509Certificate

/** The APK Signature Scheme whose block provided the signers. */
internal enum class SignatureScheme {
    V2,
    V3,

    /** The v3.1 block: the v3 format under its own ID, read on API 33 and higher only. */
    V31,
}

/**
 * One X.509 certificate, with the exact bytes it was stored as.
 *
 * Comparisons use [encoded], the bytes from the APK, because that is what the platform compares
 * and what `android.content.pm.Signature` holds for the installed app. Re-encoding a parsed
 * certificate could change the bytes.
 */
internal class SigningCertificate(
    val encoded: ByteArray,
    val x509: X509Certificate,
)

/**
 * One level of a v3 proof-of-rotation lineage.
 *
 * @property flags The capabilities granted to this certificate, see [allowsInstalledData].
 */
internal class LineageEntry(
    val certificate: SigningCertificate,
    val flags: Int,
) {
    /**
     * True when an app signed with this older certificate may be updated by the rotated APK while
     * keeping its data. The platform requires this capability for an update
     * (InstallPackageHelper, CERT_INSTALLED_DATA). The bit value comes from apksig,
     * SigningCertificateLineage.PAST_CERT_INSTALLED_DATA; the format page does not list it.
     */
    val allowsInstalledData: Boolean
        get() = flags and FLAG_INSTALLED_DATA != 0

    private companion object {
        const val FLAG_INSTALLED_DATA = 1
    }
}

/**
 * One signer whose signature over its signed data has verified.
 *
 * @property certificates The certificate chain; the first one is the signing certificate.
 * @property lineage The v3 proof-of-rotation, oldest certificate first and [certificate] last, or
 *   empty when the signer has none.
 */
internal class ApkSigner(
    val certificates: List<SigningCertificate>,
    val lineage: List<LineageEntry>,
) {
    val certificate: SigningCertificate
        get() = certificates.first()
}

/** The signers the platform would use for the APK on the given API level. */
internal class ApkSignature(
    val scheme: SignatureScheme,
    val signers: List<ApkSigner>,
)
