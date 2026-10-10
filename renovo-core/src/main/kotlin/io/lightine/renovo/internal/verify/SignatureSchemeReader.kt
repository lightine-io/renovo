package io.lightine.renovo.internal.verify

import java.io.ByteArrayInputStream
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec

/**
 * Picks and verifies the signers of an APK the way the platform does on a given API level.
 *
 * Formats: https://source.android.com/docs/security/features/apksigning/v2 (signer layout,
 * algorithms, verification steps), .../v3 (minSdk and maxSdk, proof-of-rotation) and .../v3-1
 * (the v3.1 block). Where the pages are silent, the behavior follows the platform verifier
 * (frameworks/base, android/util/apk/ApkSignatureSchemeV3Verifier.java), and the comments say so.
 *
 * What this proves: each selected signer holds the private key of its certificate and signed the
 * signed data, including the content digests. It does not recompute the content digests over the
 * file, so it does not prove the file is intact. The SHA-256 check against the version file and
 * the system installer, which verifies everything, cover integrity.
 */
internal object SignatureSchemeReader {
    const val V2_BLOCK_ID = 0x7109871a
    const val V3_BLOCK_ID = 0xf05368c0.toInt()
    const val V31_BLOCK_ID = 0x1b93ad61

    /**
     * The verified signers from the scheme the platform at [platformSdk] would use.
     *
     * API 28+ reads v3 (and v3.1 from API 33) when present and never falls back to v2 when that
     * block is broken, which is the "must not be verified using the v1 or v2 scheme" rule of the
     * v3 page. Below API 28 the platform does not know v3, so v2 is used.
     *
     * @param blocks The APK Signing Block's values by ID.
     */
    fun read(
        blocks: Map<Int, ByteArray>,
        platformSdk: Int,
    ): ApkSignature {
        if (platformSdk >= SDK_V3) {
            var v31SkippedMinSdk: Int? = null
            if (platformSdk >= SDK_V31) {
                blocks[V31_BLOCK_ID]?.let { block ->
                    val result = readV3Block(block, SignatureScheme.V31, platformSdk, null)
                    result.signer?.let { return ApkSignature(SignatureScheme.V31, listOf(it)) }
                    v31SkippedMinSdk = result.lowestSkippedMinSdk
                }
            }
            blocks[V3_BLOCK_ID]?.let { block ->
                val result = readV3Block(block, SignatureScheme.V3, platformSdk, v31SkippedMinSdk)
                val signer =
                    result.signer
                        ?: throw ApkFormatException("The v3 signature has no signer for API level $platformSdk.")
                return ApkSignature(SignatureScheme.V3, listOf(signer))
            }
        }
        val v2 =
            blocks[V2_BLOCK_ID]
                ?: throw ApkFormatException(
                    "The APK Signing Block has no APK Signature Scheme v2 or v3 signature usable on API level $platformSdk.",
                )
        return ApkSignature(SignatureScheme.V2, readV2Block(v2))
    }

    private fun readV2Block(block: ByteArray): List<ApkSigner> {
        val signers = LittleEndianReader(block).lengthPrefixed("v2 signers")
        val result = mutableListOf<ApkSigner>()
        while (signers.hasRemaining()) {
            val signer = signers.lengthPrefixed("v2 signer")
            when (val read = readSigner(signer, SignatureScheme.V2, platformSdk = 0, v31SkippedMinSdk = null)) {
                is SignerResult.Verified -> result += read.signer

                // v2 signers have no SDK range, so readSigner never skips one.
                is SignerResult.Skipped -> Unit
            }
        }
        // v2 rule: "Verification succeeds if at least one signer was found".
        if (result.isEmpty()) throw ApkFormatException("The v2 signature has no signers.")
        return result
    }

    private class V3BlockResult(
        val signer: ApkSigner?,
        val lowestSkippedMinSdk: Int?,
    )

    private fun readV3Block(
        block: ByteArray,
        scheme: SignatureScheme,
        platformSdk: Int,
        v31SkippedMinSdk: Int?,
    ): V3BlockResult {
        val signers = LittleEndianReader(block).lengthPrefixed("$scheme signers")
        var found: ApkSigner? = null
        var inRange = 0
        var lowestSkipped: Int? = null
        while (signers.hasRemaining()) {
            val signer = signers.lengthPrefixed("$scheme signer")
            when (val read = readSigner(signer, scheme, platformSdk, v31SkippedMinSdk)) {
                is SignerResult.Verified -> {
                    inRange++
                    found = read.signer
                }

                is SignerResult.Skipped -> {
                    val current = lowestSkipped
                    if (current == null || read.minSdk < current) lowestSkipped = read.minSdk
                }
            }
        }
        // v3 rule: "Verification succeeds if exactly one signer was found in range of the current
        // platform".
        if (inRange > 1) throw ApkFormatException("The $scheme signature has $inRange signers for one API level.")
        return V3BlockResult(found, lowestSkipped)
    }

    private sealed interface SignerResult {
        class Verified(
            val signer: ApkSigner,
        ) : SignerResult

        /** The signer is for other API levels; the platform ignores it. */
        class Skipped(
            val minSdk: Int,
        ) : SignerResult
    }

    private fun readSigner(
        signer: LittleEndianReader,
        scheme: SignatureScheme,
        platformSdk: Int,
        v31SkippedMinSdk: Int?,
    ): SignerResult {
        val isV3 = scheme != SignatureScheme.V2
        val signedData = signer.lengthPrefixed("signed data")
        var minSdk = 0
        var maxSdk = 0
        if (isV3) {
            minSdk = signer.int("minSdk")
            maxSdk = signer.int("maxSdk")
            if (platformSdk < minSdk || platformSdk > maxSdk) return SignerResult.Skipped(minSdk)
        }
        val signatures = signer.lengthPrefixed("signatures")
        val publicKeyBytes = signer.lengthPrefixedBytes("public key")

        // Step "Choose the strongest supported signature algorithm ID from signatures".
        val offeredAlgorithms = mutableListOf<Int>()
        var best: SignatureAlgorithm? = null
        var bestSignature: ByteArray? = null
        while (signatures.hasRemaining()) {
            val record = signatures.lengthPrefixed("signature record")
            val id = record.int("signature algorithm ID")
            offeredAlgorithms += id
            val algorithm = SignatureAlgorithm.byId(id) ?: continue
            val signature = record.lengthPrefixedBytes("signature")
            val current = best
            if (current == null || algorithm.strength > current.strength) {
                best = algorithm
                bestSignature = signature
            }
        }
        if (offeredAlgorithms.isEmpty()) throw ApkFormatException("A $scheme signer has no signatures.")
        if (best == null || bestSignature == null) {
            throw ApkFormatException(
                "A $scheme signer uses no supported signature algorithm. Offered: " +
                    offeredAlgorithms.joinToString { "0x" + it.toUInt().toString(16) } + ".",
            )
        }

        // Step "Verify the corresponding signature from signatures against signed data using
        // public key. (It is now safe to parse signed data.)" Nothing inside signed data is read
        // before this point.
        val publicKey =
            try {
                KeyFactory.getInstance(best.keyAlgorithm).generatePublic(X509EncodedKeySpec(publicKeyBytes))
            } catch (e: GeneralSecurityException) {
                throw ApkFormatException("A $scheme signer's public key cannot be read: ${e.message}", e)
            }
        if (!best.verify(publicKey, signedData.toByteArray(), bestSignature)) {
            throw ApkSignatureException("The $scheme signature (${best.name}) over the signed data does not verify.")
        }

        val digests = signedData.lengthPrefixed("digests")
        val digestAlgorithms = mutableListOf<Int>()
        while (digests.hasRemaining()) {
            val record = digests.lengthPrefixed("digest record")
            digestAlgorithms += record.int("digest algorithm ID")
            record.lengthPrefixed("digest")
        }
        // Step "Verify that the ordered list of signature algorithm IDs in digests and signatures
        // is identical", against stripping or adding signatures.
        if (digestAlgorithms != offeredAlgorithms) {
            throw ApkSignatureException("A $scheme signer's signature and digest algorithm lists differ.")
        }

        val certificates = readCertificates(signedData.lengthPrefixed("certificates"), scheme)
        // Step "Verify that SubjectPublicKeyInfo of the first certificate of certificates is
        // identical to public key."
        if (!certificates.first().x509.publicKey.encoded.contentEquals(publicKeyBytes)) {
            throw ApkSignatureException("A $scheme signer's public key is not the key of its certificate.")
        }

        if (!isV3) {
            // The v2 additional attributes only protect against stripping v2 down to v1, which
            // does not apply here.
            return SignerResult.Verified(ApkSigner(certificates, emptyList()))
        }

        // v3 step "Verify the min and max SDK versions in the signed data match those specified
        // for the signer."
        val signedMinSdk = signedData.int("signed minSdk")
        val signedMaxSdk = signedData.int("signed maxSdk")
        if (signedMinSdk != minSdk || signedMaxSdk != maxSdk) {
            throw ApkSignatureException("A $scheme signer's SDK range differs between signed and unsigned data.")
        }
        val attributes = readV3Attributes(signedData.lengthPrefixed("additional attributes"))

        val lineage = attributes.lineage
        // v3 step "If the proof-of-rotation attribute exists for the signer verify that the
        // struct is valid and this signer is the last certificate in the list."
        if (lineage.isNotEmpty() && !lineage.last().certificate.encoded.contentEquals(certificates.first().encoded)) {
            throw ApkSignatureException("The last certificate of the $scheme lineage is not the signing certificate.")
        }

        // The v3.1 page: a rotation can target a platform under development. Devices in use run
        // release builds, which skip such a signer on exactly its minSdk (ApkSignatureSchemeV3Verifier,
        // ROTATION_ON_DEV_RELEASE_ATTR_ID with Build.VERSION.CODENAME "REL").
        if (scheme == SignatureScheme.V31 && attributes.rotationTargetsDevRelease && platformSdk == minSdk) {
            return SignerResult.Skipped(minSdk)
        }

        // Stripping protection from the v3.1 page: a v3 signer that names a rotation minSdk
        // expects a v3.1 block targeting that level. If the v3.1 block was removed, an API 33+
        // platform rejects the APK (ApkSignatureSchemeV3Verifier, ROTATION_MIN_SDK_VERSION_ATTR_ID),
        // so Renovo does too. Older platforms do not know the attribute.
        val rotationMinSdk = attributes.rotationMinSdk
        if (scheme == SignatureScheme.V3 && rotationMinSdk != null && platformSdk >= SDK_V31) {
            if (v31SkippedMinSdk != rotationMinSdk) {
                throw ApkSignatureException(
                    "The v3 signer expects a v3.1 signature for API level $rotationMinSdk, but the APK has " +
                        (v31SkippedMinSdk?.let { "one for API level $it." } ?: "none."),
                )
            }
        }
        return SignerResult.Verified(ApkSigner(certificates, lineage))
    }

    private fun readCertificates(
        sequence: LittleEndianReader,
        scheme: SignatureScheme,
    ): List<SigningCertificate> {
        val certificates = mutableListOf<SigningCertificate>()
        while (sequence.hasRemaining()) {
            certificates += readCertificate(sequence.lengthPrefixedBytes("certificate"))
        }
        if (certificates.isEmpty()) throw ApkFormatException("A $scheme signer lists no certificates.")
        return certificates
    }

    private fun readCertificate(encoded: ByteArray): SigningCertificate = try {
        val certificate =
            CertificateFactory
                .getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(encoded)) as? X509Certificate
                ?: throw ApkFormatException("A certificate is not an X.509 certificate.")
        SigningCertificate(encoded, certificate)
    } catch (e: CertificateException) {
        throw ApkFormatException("A certificate cannot be read: ${e.message}", e)
    }

    private class V3Attributes(
        val lineage: List<LineageEntry>,
        val rotationMinSdk: Int?,
        val rotationTargetsDevRelease: Boolean,
    )

    private fun readV3Attributes(attributes: LittleEndianReader): V3Attributes {
        var lineage: List<LineageEntry>? = null
        var rotationMinSdk: Int? = null
        var devRelease = false
        while (attributes.hasRemaining()) {
            val attribute = attributes.lengthPrefixed("additional attribute")
            when (attribute.int("additional attribute ID")) {
                PROOF_OF_ROTATION_ATTR_ID -> {
                    // The platform rejects a second record instead of picking one.
                    if (lineage != null) throw ApkFormatException("A v3 signer has two proof-of-rotation records.")
                    lineage = readLineage(attribute)
                }

                ROTATION_MIN_SDK_VERSION_ATTR_ID -> rotationMinSdk = attribute.int("rotation minSdk")

                ROTATION_ON_DEV_RELEASE_ATTR_ID -> devRelease = true

                // Unknown attributes are ignored, as the platform does.
                else -> Unit
            }
        }
        return V3Attributes(lineage.orEmpty(), rotationMinSdk, devRelease)
    }

    /**
     * Reads and verifies a proof-of-rotation record (v3 page, "Proof-of-rotation and
     * self-trusted-old-certs structs").
     *
     * The page leaves out one detail that the platform and apksig both use: the record starts
     * with a uint32 version, and the levels follow one after another until the end of the
     * attribute, each length-prefixed. Each level holds signed data (certificate, then the ID of
     * the algorithm the previous level's key signed it with), flags, the ID of the algorithm this
     * level's key uses for the next level, and the previous level's signature over the signed
     * data. Every link is verified here: an unverified lineage would let anyone claim to be the
     * successor of the installed app's key.
     */
    internal fun readLineage(record: LittleEndianReader): List<LineageEntry> {
        // The platform reads the version and ignores it (ApkSigningBlockUtils
        // .verifyProofOfRotationStruct), so a future version does not make Renovo stricter than
        // the installer.
        record.int("proof-of-rotation version")
        val entries = mutableListOf<LineageEntry>()
        var previous: SigningCertificate? = null
        var previousAlgorithmId = 0
        while (record.hasRemaining()) {
            val level = record.lengthPrefixed("proof-of-rotation level")
            val signedData = level.lengthPrefixed("proof-of-rotation signed data")
            val flags = level.int("proof-of-rotation flags")
            val algorithmId = level.int("proof-of-rotation signature algorithm ID")
            val signature = level.lengthPrefixedBytes("proof-of-rotation signature")

            if (previous != null) {
                val algorithm =
                    SignatureAlgorithm.byId(previousAlgorithmId)
                        ?: throw ApkSignatureException(
                            "The lineage uses an unsupported signature algorithm: 0x" +
                                previousAlgorithmId.toUInt().toString(16) + ".",
                        )
                if (!algorithm.verify(previous.x509.publicKey, signedData.toByteArray(), signature)) {
                    throw ApkSignatureException("Lineage level ${entries.size + 1} is not signed by the level before it.")
                }
            }

            val certificate = readCertificate(signedData.lengthPrefixedBytes("lineage certificate"))
            val signedAlgorithmId = signedData.int("lineage signed algorithm ID")
            // The page: the level's algorithm ID "must match the one from the signed data section
            // in the next level". Only the copy inside signed data is protected by the signature.
            if (previous != null && signedAlgorithmId != previousAlgorithmId) {
                throw ApkSignatureException("Lineage level ${entries.size + 1} names a different signature algorithm.")
            }
            if (entries.any { it.certificate.encoded.contentEquals(certificate.encoded) }) {
                throw ApkSignatureException("The lineage lists a certificate twice.")
            }
            entries += LineageEntry(certificate, flags)
            previous = certificate
            previousAlgorithmId = algorithmId
        }
        return entries
    }

    private const val SDK_V3 = 28
    private const val SDK_V31 = 33
    private const val PROOF_OF_ROTATION_ATTR_ID = 0x3ba06f8c

    // These two are not on the format pages. Values from the platform verifier and from apksig,
    // V3SchemeConstants.
    private const val ROTATION_MIN_SDK_VERSION_ATTR_ID = 0x559f8b02
    private const val ROTATION_ON_DEV_RELEASE_ATTR_ID = 0xc2a6b3ba.toInt()
}
