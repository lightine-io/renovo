package io.lightine.renovo.internal.verify

/**
 * The file is not an APK that Renovo can read: not a zip, a broken structure, or no APK Signature
 * Scheme v2 or v3 signature.
 *
 * Kept apart from [java.io.IOException] on purpose: an I/O error means the file could not be read
 * (a storage problem), this means the bytes that were read are wrong.
 */
internal class ApkFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The APK is well formed, but a signature over its signed data, or over its proof-of-rotation
 * lineage, does not verify.
 */
internal class ApkSignatureException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
