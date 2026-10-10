package io.lightine.renovo.internal.verify

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Reads what the verifier needs from an APK file: the manifest's package name and version code,
 * and the verified signers of its APK Signature Scheme v2 or v3 signature.
 *
 * Pure Kotlin and `java.*`, so it runs the same on every Android version and in JVM unit tests.
 * It reads the signing block itself because no non-deprecated platform API returns an archive's
 * signing certificate below API 30.
 *
 * Every method throws [ApkFormatException] for a file that is not a valid APK,
 * [ApkSignatureException] for a signature that does not verify, and [IOException] when the file
 * cannot be read.
 */
internal object ApkReader {
    /** The package name and version code from the APK's AndroidManifest.xml. */
    fun readManifest(file: File): ApkManifest {
        val bytes =
            try {
                ZipFile(file).use { zip ->
                    val entry = zip.getEntry(MANIFEST) ?: throw ApkFormatException("The APK has no $MANIFEST.")
                    if (entry.isDirectory) throw ApkFormatException("$MANIFEST is a directory.")
                    zip.getInputStream(entry).use { readAtMost(it, MAX_MANIFEST_SIZE) }
                }
            } catch (e: ZipException) {
                // A ZipException is a broken archive, not a failed read: keep it out of the
                // IOException path that means "storage".
                throw ApkFormatException("The APK is not a valid zip archive: ${e.message}", e)
            }
        return BinaryManifestParser(bytes).parse()
    }

    /**
     * The signers the platform at [platformSdk] would verify, with their signatures over the
     * signed data checked. See [SignatureSchemeReader] for what that does and does not prove.
     */
    fun readSignature(
        file: File,
        platformSdk: Int,
    ): ApkSignature {
        val blocks =
            ApkSigningBlock.read(file)
                ?: throw ApkFormatException(
                    "The APK has no APK Signing Block: it is unsigned or signed with JAR signing (v1) only. " +
                        "Renovo needs APK Signature Scheme v2 or v3.",
                )
        return SignatureSchemeReader.read(blocks, platformSdk)
    }

    private fun readAtMost(
        input: InputStream,
        limit: Int,
    ): ByteArray {
        // The limit stops a hostile archive from inflating a huge entry into memory. Real
        // manifests are a few kilobytes up to a few hundred. InputStream.readNBytes would do this
        // but only exists from API 33.
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > limit) throw ApkFormatException("$MANIFEST is larger than $limit bytes.")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private const val BUFFER_SIZE = 8192

    private const val MANIFEST = "AndroidManifest.xml"
    private const val MAX_MANIFEST_SIZE = 8 * 1024 * 1024
}
