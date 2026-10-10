package io.lightine.renovo.internal.verify

import java.security.GeneralSecurityException
import java.security.NoSuchAlgorithmException
import java.security.PublicKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec

/**
 * The signature algorithms of APK Signature Scheme v2 and v3 that Renovo verifies.
 *
 * IDs and parameters from https://source.android.com/docs/security/features/apksigning/v2,
 * "Signature algorithm IDs". DSA (0x0301) is not supported: apksigner signs with RSA or EC keys
 * by default, and DSA keys for APK signing are long out of use. The verity variants
 * (0x0421, 0x0423, 0x0425) only add a digest for fs-verity and always come with a plain
 * signature, so skipping them loses nothing. A signer that offers nothing from this list is
 * rejected.
 */
internal enum class SignatureAlgorithm(
    val id: Int,
    /** The key algorithm name for [java.security.KeyFactory]. */
    val keyAlgorithm: String,
    /** Java names to try in order: Android and the desktop JVM spell RSA-PSS differently. */
    private val jcaNames: List<String>,
    private val pssParameters: PSSParameterSpec?,
    /** Bits of the content digest, the platform's strength order (ApkSigningBlockUtils.compareSignatureAlgorithm). */
    val strength: Int,
) {
    RSA_PSS_WITH_SHA256(0x0101, "RSA", PSS_NAMES_SHA256, pss("SHA-256", MGF1ParameterSpec.SHA256, 32), 256),
    RSA_PSS_WITH_SHA512(0x0102, "RSA", PSS_NAMES_SHA512, pss("SHA-512", MGF1ParameterSpec.SHA512, 64), 512),
    RSA_PKCS1_V1_5_WITH_SHA256(0x0103, "RSA", listOf("SHA256withRSA"), null, 256),
    RSA_PKCS1_V1_5_WITH_SHA512(0x0104, "RSA", listOf("SHA512withRSA"), null, 512),
    ECDSA_WITH_SHA256(0x0201, "EC", listOf("SHA256withECDSA"), null, 256),
    ECDSA_WITH_SHA512(0x0202, "EC", listOf("SHA512withECDSA"), null, 512),
    ;

    /**
     * True when [signature] is a valid signature over [data] by [publicKey].
     *
     * @throws ApkSignatureException when the key does not fit the algorithm or the runtime has
     *   no implementation of it.
     */
    fun verify(
        publicKey: PublicKey,
        data: ByteArray,
        signature: ByteArray,
    ): Boolean = try {
        val verifier = newSignature()
        verifier.initVerify(publicKey)
        pssParameters?.let { verifier.setParameter(it) }
        verifier.update(data)
        verifier.verify(signature)
    } catch (e: GeneralSecurityException) {
        throw ApkSignatureException("The $name signature could not be checked: ${e.message}", e)
    }

    private fun newSignature(): Signature {
        var lastError: NoSuchAlgorithmException? = null
        for (jcaName in jcaNames) {
            try {
                return Signature.getInstance(jcaName)
            } catch (e: NoSuchAlgorithmException) {
                lastError = e
            }
        }
        throw lastError ?: NoSuchAlgorithmException(name)
    }

    companion object {
        fun byId(id: Int): SignatureAlgorithm? = entries.firstOrNull { it.id == id }
    }
}

// Android names RSA-PSS "SHA256withRSA/PSS" (API 23+, "RSASSA-PSS" only on API 1 to 8, per the
// java.security.Signature reference). The desktop JVM that runs the unit tests only knows
// "RSASSA-PSS". The parameters are set explicitly either way, as the format page prescribes:
// MGF1 with the same digest, salt as long as the digest, trailer 0xbc (trailer field 1).
private val PSS_NAMES_SHA256 = listOf("SHA256withRSA/PSS", "RSASSA-PSS")
private val PSS_NAMES_SHA512 = listOf("SHA512withRSA/PSS", "RSASSA-PSS")

private fun pss(
    digest: String,
    mgf1: MGF1ParameterSpec,
    saltLength: Int,
): PSSParameterSpec = PSSParameterSpec(digest, "MGF1", mgf1, saltLength, 1)
