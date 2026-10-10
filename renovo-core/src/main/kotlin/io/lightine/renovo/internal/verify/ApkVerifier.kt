package io.lightine.renovo.internal.verify

import io.lightine.renovo.FailureReason
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.internal.VerificationResult
import io.lightine.renovo.internal.Verifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/**
 * Checks a downloaded APK in the order [Verifier] prescribes.
 *
 * This is a fail-fast check that gives the user a clear reason before the system installer runs.
 * The installer verifies the full signature, including every byte of the file, again at install
 * time; see [SignatureSchemeReader] for what Renovo's own signature check covers.
 *
 * @param platformSdk The device's API level (`Build.VERSION.SDK_INT`). The platform picks the
 *   signature scheme and the v3 signer by API level, so the verifier must use the same one.
 */
internal class ApkVerifier(
    private val installedApp: InstalledApp,
    private val logger: RenovoLogger?,
    private val platformSdk: Int,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Verifier {
    override suspend fun verify(
        file: File,
        info: UpdateInfo,
        installedVersionCode: Long,
    ): VerificationResult = withContext(dispatcher) {
        try {
            check(file, info, installedVersionCode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            fail(FailureReason.Storage, "The downloaded file could not be read.", e)
        } catch (e: ApkFormatException) {
            fail(FailureReason.HashMismatch, "The downloaded file is not a valid APK: ${e.message}", e)
        } catch (e: ApkSignatureException) {
            fail(FailureReason.HashMismatch, "The downloaded APK's signature is not valid: ${e.message}", e)
        } catch (e: Exception) {
            // The contract is "never throws". Anything else, such as a PackageManager error, is
            // still a reason not to install, and the file is the thing in doubt.
            fail(FailureReason.HashMismatch, "The downloaded file could not be verified.", e)
        }
    }

    private fun check(
        file: File,
        info: UpdateInfo,
        installedVersionCode: Long,
    ): VerificationResult {
        // 1. Integrity against the version file.
        val expectedHash = info.sha256
        if (expectedHash != null && !MessageDigest.isEqual(sha256(file), hexToBytes(expectedHash))) {
            return fail(FailureReason.HashMismatch, "The downloaded file's SHA-256 does not match the version file.")
        }

        // 2. Same app.
        val manifest = ApkReader.readManifest(file)
        if (manifest.packageName != installedApp.packageName) {
            return fail(FailureReason.SignatureMismatch, "The downloaded APK is for a different app.")
        }

        // 3. Newer. A version code other than the announced one means the file is not what the
        // version file describes, which is an integrity failure, not an old version.
        val apkVersionCode = manifest.longVersionCode
        if (apkVersionCode != info.versionCode) {
            return fail(
                FailureReason.HashMismatch,
                "The downloaded APK has version code $apkVersionCode, the version file announced ${info.versionCode}.",
            )
        }
        if (apkVersionCode <= installedVersionCode) {
            return fail(
                FailureReason.NotNewer,
                "The downloaded APK's version code $apkVersionCode is not higher than the installed $installedVersionCode.",
            )
        }

        // 4. Same publisher.
        val signature = ApkReader.readSignature(file, platformSdk)
        if (!certificatesMatch(installedApp.signingCertificates(), signature)) {
            return fail(FailureReason.SignatureMismatch, "The downloaded APK is signed with a different key.")
        }

        // Last, the system's own parser: if it cannot read the file, the installer cannot either.
        if (!installedApp.canParseArchive(file)) {
            return fail(FailureReason.HashMismatch, "The system cannot parse the downloaded APK.")
        }
        return VerificationResult.Passed
    }

    private fun fail(
        reason: FailureReason,
        message: String,
        error: Throwable? = null,
    ): VerificationResult {
        logger?.log("Verification failed ($reason). $message", error)
        return VerificationResult.Failed(reason)
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024

        fun sha256(file: File): ByteArray {
            // Streamed: the APK can be hundreds of megabytes.
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest()
        }

        /** [UpdateInfo] guarantees 64 lowercase hexadecimal characters. */
        fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

        /**
         * The platform's update rule (InstallPackageHelper: the new APK must carry the old
         * certificate with the "installed data" capability), restricted to what Renovo can see.
         *
         * One installed signer: the APK's signer must be the same certificate, or the APK's v3
         * lineage must list it as a past certificate that may keep the app's data. Several signers
         * cannot rotate, so the sets must be equal.
         *
         * Not covered: the platform also accepts an APK signed with a past key when the installed
         * lineage grants that key the rollback capability. Renovo cannot read installed lineage
         * capabilities, and such a key rollback is rare, so it is reported as a mismatch.
         */
        fun certificatesMatch(
            installed: List<ByteArray>,
            signature: ApkSignature,
        ): Boolean {
            if (installed.isEmpty()) return false
            val signers = signature.signers
            if (installed.size == 1 && signers.size == 1) {
                val current = installed.single()
                val signer = signers.single()
                if (signer.certificate.encoded.contentEquals(current)) return true
                return signer.lineage.dropLast(1).any {
                    it.certificate.encoded.contentEquals(current) && it.allowsInstalledData
                }
            }
            val apkCertificates = signers.map { it.certificate.encoded }
            return installed.size == apkCertificates.size &&
                installed.all { mine -> apkCertificates.any { it.contentEquals(mine) } } &&
                apkCertificates.all { theirs -> installed.any { it.contentEquals(theirs) } }
        }
    }
}
