package io.lightine.renovo.internal.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec

/**
 * Covers the algorithms the fixtures cannot: apksigner signs RSA keys with PKCS#1 v1.5 and picks
 * SHA-512 only for keys above 3072 bits, so RSA-PSS and the SHA-512 variants are checked here
 * against signatures the JVM makes with the parameters from the v2 format page.
 */
class SignatureAlgorithmTest {
    private val data = "signed data".toByteArray()

    @Test
    fun `every algorithm verifies its own signature and rejects changed data`() {
        SignatureAlgorithm.entries.forEach { algorithm ->
            val keys = keysFor(algorithm)
            val signature = sign(algorithm, keys, data)
            assertTrue(algorithm.name, algorithm.verify(keys.public, data, signature))
            assertFalse(algorithm.name, algorithm.verify(keys.public, "other data".toByteArray(), signature))
        }
    }

    @Test
    fun `a signature by another key does not verify`() {
        val algorithm = SignatureAlgorithm.RSA_PSS_WITH_SHA256
        val signature = sign(algorithm, keysFor(algorithm), data)
        assertFalse(algorithm.verify(keysFor(algorithm).public, data, signature))
    }

    @Test
    fun `a key of the wrong type is a signature error, not a crash`() {
        val ecKey = keysFor(SignatureAlgorithm.ECDSA_WITH_SHA256).public
        assertThrows(ApkSignatureException::class.java) {
            SignatureAlgorithm.RSA_PKCS1_V1_5_WITH_SHA256.verify(ecKey, data, ByteArray(256))
        }
    }

    @Test
    fun `IDs and strength follow the format page and the platform`() {
        assertEquals(SignatureAlgorithm.RSA_PSS_WITH_SHA256, SignatureAlgorithm.byId(0x0101))
        assertEquals(SignatureAlgorithm.RSA_PSS_WITH_SHA512, SignatureAlgorithm.byId(0x0102))
        assertEquals(SignatureAlgorithm.RSA_PKCS1_V1_5_WITH_SHA256, SignatureAlgorithm.byId(0x0103))
        assertEquals(SignatureAlgorithm.RSA_PKCS1_V1_5_WITH_SHA512, SignatureAlgorithm.byId(0x0104))
        assertEquals(SignatureAlgorithm.ECDSA_WITH_SHA256, SignatureAlgorithm.byId(0x0201))
        assertEquals(SignatureAlgorithm.ECDSA_WITH_SHA512, SignatureAlgorithm.byId(0x0202))
        // DSA and the verity variants are not supported.
        listOf(0x0301, 0x0421, 0x0423, 0x0425).forEach { assertNull(SignatureAlgorithm.byId(it)) }
        assertTrue(SignatureAlgorithm.ECDSA_WITH_SHA512.strength > SignatureAlgorithm.RSA_PSS_WITH_SHA256.strength)
    }

    private fun keysFor(algorithm: SignatureAlgorithm): KeyPair = if (algorithm.keyAlgorithm == "EC") {
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    } else {
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }

    private fun sign(
        algorithm: SignatureAlgorithm,
        keys: KeyPair,
        data: ByteArray,
    ): ByteArray {
        val signer =
            when (algorithm) {
                SignatureAlgorithm.RSA_PSS_WITH_SHA256 ->
                    Signature.getInstance("RSASSA-PSS").apply {
                        setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
                    }

                SignatureAlgorithm.RSA_PSS_WITH_SHA512 ->
                    Signature.getInstance("RSASSA-PSS").apply {
                        setParameter(PSSParameterSpec("SHA-512", "MGF1", MGF1ParameterSpec.SHA512, 64, 1))
                    }

                SignatureAlgorithm.RSA_PKCS1_V1_5_WITH_SHA256 -> Signature.getInstance("SHA256withRSA")

                SignatureAlgorithm.RSA_PKCS1_V1_5_WITH_SHA512 -> Signature.getInstance("SHA512withRSA")

                SignatureAlgorithm.ECDSA_WITH_SHA256 -> Signature.getInstance("SHA256withECDSA")

                SignatureAlgorithm.ECDSA_WITH_SHA512 -> Signature.getInstance("SHA512withECDSA")
            }
        signer.initSign(keys.private)
        signer.update(data)
        return signer.sign()
    }
}
