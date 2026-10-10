package io.lightine.renovo.internal.verify

import java.io.File
import java.security.MessageDigest

/**
 * The signed test APKs in src/test/resources/apks/, built by src/test/fixtures/make-fixtures.sh.
 *
 * Every fixture is a minimal app; unless its name says otherwise it has package [SAMPLE_PACKAGE],
 * versionCode 2, and is signed with key A (RSA) using schemes v2 and v3.
 */
internal object ApkFixtures {
    const val SAMPLE_PACKAGE = "io.lightine.renovo.sample"

    /**
     * SHA-256 of the two signing certificates, as printed by make-fixtures.sh (from keytool, so
     * independent of the reader under test). Key A is RSA 2048, key B is EC P-256.
     */
    const val CERT_A_SHA256 = "0c89f9febbba24a0b231bd7d7dabfaa9d2da30a4d36546a084ffe49c987d9452"
    const val CERT_B_SHA256 = "c74d21fc03bbb7b4b03efe297a0c22227389ef284d00a7d6c503024b88cd4463"

    /** Key A, v2 and v3. */
    const val GOOD_V2V3 = "good-v2v3.apk"

    /** Key A, v2 only. */
    const val GOOD_V2_ONLY = "good-v2-only.apk"

    /** Key B, v2 and v3. */
    const val OTHER_KEY = "other-key.apk"

    /** Key A, JAR signing (v1) only. */
    const val V1_ONLY = "v1-only.apk"

    /** v2 signed by A; v3 signed by B with a lineage A to B, rotation from API 28. */
    const val ROTATED = "rotated.apk"

    /** Like [ROTATED], but the lineage takes the "installed data" capability away from A. */
    const val ROTATED_NO_INSTALLED_DATA = "rotated-no-installed-data.apk"

    /** apksigner's default rotation: v2 and v3 (API 24 to 32) by A, v3.1 (API 33+) by B with the lineage. */
    const val ROTATED_V31 = "rotated-v31.apk"

    /** Like [ROTATED_V31] with rotation from API 34: v3 by A for API 24 to 33, v3.1 by B for 34+. */
    const val ROTATED_V31_API34 = "rotated-v31-api34.apk"

    /** Key A, versionCode 1. */
    const val LOWER_VERSION = "lower-version.apk"

    /** Key A, package io.lightine.renovo.other. */
    const val OTHER_PACKAGE = "other-package.apk"

    /** Key A, versionCode 2 and versionCodeMajor 1. */
    const val VERSION_MAJOR = "version-major.apk"

    fun bytes(name: String): ByteArray {
        val stream =
            ApkFixtures::class.java.getResourceAsStream("/apks/$name")
                ?: throw IllegalStateException("Missing fixture $name. Run src/test/fixtures/make-fixtures.sh.")
        return stream.use { it.readBytes() }
    }

    /** A copy of the fixture in [directory], so a test may change or delete it. */
    fun copy(
        name: String,
        directory: File,
    ): File = writeTo(File(directory, name), bytes(name))

    fun writeTo(
        file: File,
        bytes: ByteArray,
    ): File {
        file.writeBytes(bytes)
        return file
    }

    val certificateA: ByteArray by lazy { signerCertificate(GOOD_V2_ONLY, CERT_A_SHA256) }
    val certificateB: ByteArray by lazy { signerCertificate(OTHER_KEY, CERT_B_SHA256) }

    private fun signerCertificate(
        fixture: String,
        expectedSha256: String,
    ): ByteArray {
        val file = File.createTempFile("renovo-fixture", ".apk")
        try {
            val encoded =
                ApkReader
                    .readSignature(writeTo(file, bytes(fixture)), platformSdk = 27)
                    .signers
                    .single()
                    .certificate
                    .encoded
            // The hash pins the certificate independently of the reader, so a reader bug cannot
            // make the fake installed app agree with itself.
            check(sha256Hex(encoded) == expectedSha256) { "The certificate of $fixture is not the expected one." }
            return encoded
        } finally {
            file.delete()
        }
    }

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** All start offsets of [pattern] in [bytes]. */
    fun indexesOf(
        bytes: ByteArray,
        pattern: ByteArray,
    ): List<Int> = (0..bytes.size - pattern.size).filter { start ->
        pattern.indices.all { bytes[start + it] == pattern[it] }
    }
}

/** An [InstalledApp] with fixed answers: by default the sample app at versionCode 1, signed with key A. */
internal class FakeInstalledApp(
    override val packageName: String = ApkFixtures.SAMPLE_PACKAGE,
    private val versionCode: Long = 1,
    private val certificates: List<ByteArray> = listOf(ApkFixtures.certificateA),
    private val canParse: Boolean = true,
) : InstalledApp {
    override fun versionCode(): Long = versionCode

    override fun signingCertificates(): List<ByteArray> = certificates

    override fun canParseArchive(file: File): Boolean = canParse
}
