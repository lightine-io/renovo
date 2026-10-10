package io.lightine.renovo.internal.verify

import io.lightine.renovo.internal.verify.ApkFixtures.CERT_A_SHA256
import io.lightine.renovo.internal.verify.ApkFixtures.CERT_B_SHA256
import io.lightine.renovo.internal.verify.ApkFixtures.GOOD_V2V3
import io.lightine.renovo.internal.verify.ApkFixtures.GOOD_V2_ONLY
import io.lightine.renovo.internal.verify.ApkFixtures.LOWER_VERSION
import io.lightine.renovo.internal.verify.ApkFixtures.OTHER_KEY
import io.lightine.renovo.internal.verify.ApkFixtures.OTHER_PACKAGE
import io.lightine.renovo.internal.verify.ApkFixtures.ROTATED
import io.lightine.renovo.internal.verify.ApkFixtures.ROTATED_NO_INSTALLED_DATA
import io.lightine.renovo.internal.verify.ApkFixtures.ROTATED_V31
import io.lightine.renovo.internal.verify.ApkFixtures.ROTATED_V31_API34
import io.lightine.renovo.internal.verify.ApkFixtures.SAMPLE_PACKAGE
import io.lightine.renovo.internal.verify.ApkFixtures.V1_ONLY
import io.lightine.renovo.internal.verify.ApkFixtures.VERSION_MAJOR
import io.lightine.renovo.internal.verify.ApkFixtures.sha256Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkReaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun fixture(name: String): File = ApkFixtures.copy(name, temp.root)

    private fun signerHashes(signature: ApkSignature): List<String> = signature.signers.map { sha256Hex(it.certificate.encoded) }

    // Manifest

    @Test
    fun `reads package and version code`() {
        val manifest = ApkReader.readManifest(fixture(GOOD_V2V3))
        assertEquals(SAMPLE_PACKAGE, manifest.packageName)
        assertEquals(2, manifest.versionCode)
        assertEquals(0, manifest.versionCodeMajor)
        assertEquals(2L, manifest.longVersionCode)
    }

    @Test
    fun `reads versionCodeMajor into the upper 32 bits`() {
        val manifest = ApkReader.readManifest(fixture(VERSION_MAJOR))
        assertEquals(2, manifest.versionCode)
        assertEquals(1, manifest.versionCodeMajor)
        assertEquals(0x1_0000_0002L, manifest.longVersionCode)
    }

    @Test
    fun `reads another package and a lower version`() {
        assertEquals("io.lightine.renovo.other", ApkReader.readManifest(fixture(OTHER_PACKAGE)).packageName)
        assertEquals(1L, ApkReader.readManifest(fixture(LOWER_VERSION)).longVersionCode)
    }

    @Test
    fun `a zip without AndroidManifest_xml is rejected`() {
        val file = zipOf(temp.newFile("no-manifest.apk"), "classes.dex" to byteArrayOf(1, 2, 3))
        assertThrows(ApkFormatException::class.java) { ApkReader.readManifest(file) }
    }

    @Test
    fun `a manifest that is not binary XML is rejected`() {
        val text = "<manifest package=\"$SAMPLE_PACKAGE\"/>".toByteArray()
        val file = zipOf(temp.newFile("text-manifest.apk"), "AndroidManifest.xml" to text)
        assertThrows(ApkFormatException::class.java) { ApkReader.readManifest(file) }
    }

    @Test
    fun `a truncated binary manifest is rejected`() {
        val manifest = manifestBytes(GOOD_V2V3)
        listOf(0, 7, 8, 40, manifest.size / 2, manifest.size - 1).forEach { length ->
            assertThrows("length $length", ApkFormatException::class.java) {
                BinaryManifestParser(manifest.copyOf(length)).parse()
            }
        }
    }

    @Test
    fun `the binary manifest of a real APK parses on its own`() {
        assertEquals(SAMPLE_PACKAGE, BinaryManifestParser(manifestBytes(GOOD_V2V3)).parse().packageName)
    }

    // Signing block, scheme choice

    @Test
    fun `v3 is used from API 28 and v2 below`() {
        val file = fixture(GOOD_V2V3)
        val v3 = ApkReader.readSignature(file, platformSdk = 28)
        assertEquals(SignatureScheme.V3, v3.scheme)
        assertEquals(listOf(CERT_A_SHA256), signerHashes(v3))
        assertTrue(v3.signers.single().lineage.isEmpty())

        val v2 = ApkReader.readSignature(file, platformSdk = 27)
        assertEquals(SignatureScheme.V2, v2.scheme)
        assertEquals(listOf(CERT_A_SHA256), signerHashes(v2))
    }

    @Test
    fun `a v2-only APK uses v2 on every API level`() {
        listOf(24, 28, 33, 37).forEach { sdk ->
            val signature = ApkReader.readSignature(fixture(GOOD_V2_ONLY), sdk)
            assertEquals(SignatureScheme.V2, signature.scheme)
            assertEquals(listOf(CERT_A_SHA256), signerHashes(signature))
        }
    }

    @Test
    fun `an ECDSA signer verifies`() {
        listOf(27, 37).forEach { sdk ->
            assertEquals(listOf(CERT_B_SHA256), signerHashes(ApkReader.readSignature(fixture(OTHER_KEY), sdk)))
        }
    }

    @Test
    fun `the signer's certificate chain is returned as X509 certificates`() {
        val certificate = ApkReader.readSignature(fixture(GOOD_V2V3), 37).signers.single().certificate
        assertEquals("CN=Renovo test key A", certificate.x509.subjectX500Principal.name)
        assertTrue(certificate.x509.encoded.contentEquals(certificate.encoded))
    }

    // Rotation

    @Test
    fun `a v3 rotation carries the verified lineage from A to B`() {
        val signature = ApkReader.readSignature(fixture(ROTATED), platformSdk = 37)
        assertEquals(SignatureScheme.V3, signature.scheme)
        val signer = signature.signers.single()
        assertEquals(CERT_B_SHA256, sha256Hex(signer.certificate.encoded))
        assertEquals(listOf(CERT_A_SHA256, CERT_B_SHA256), signer.lineage.map { sha256Hex(it.certificate.encoded) })
        assertTrue(signer.lineage.first().allowsInstalledData)
    }

    @Test
    fun `the rotated APK's v2 block is still signed by the original key`() {
        val signature = ApkReader.readSignature(fixture(ROTATED), platformSdk = 27)
        assertEquals(SignatureScheme.V2, signature.scheme)
        assertEquals(listOf(CERT_A_SHA256), signerHashes(signature))
    }

    @Test
    fun `lineage flags are read`() {
        val lineage = ApkReader.readSignature(fixture(ROTATED_NO_INSTALLED_DATA), 37).signers.single().lineage
        assertEquals(CERT_A_SHA256, sha256Hex(lineage.first().certificate.encoded))
        assertFalse(lineage.first().allowsInstalledData)
    }

    @Test
    fun `v3_1 is used from API 33, v3 below it`() {
        val file = fixture(ROTATED_V31)
        val v31 = ApkReader.readSignature(file, platformSdk = 33)
        assertEquals(SignatureScheme.V31, v31.scheme)
        assertEquals(listOf(CERT_B_SHA256), signerHashes(v31))
        assertEquals(listOf(CERT_A_SHA256, CERT_B_SHA256), v31.signers.single().lineage.map { sha256Hex(it.certificate.encoded) })

        val v3 = ApkReader.readSignature(file, platformSdk = 32)
        assertEquals(SignatureScheme.V3, v3.scheme)
        assertEquals(listOf(CERT_A_SHA256), signerHashes(v3))

        assertEquals(SignatureScheme.V2, ApkReader.readSignature(file, platformSdk = 27).scheme)
    }

    @Test
    fun `a v3_1 rotation from API 34 keeps the v3 signer on API 33`() {
        // The v3.1 signer is skipped on 33; the v3 signer's rotation minSdk (34) matches it, so
        // the stripping protection is satisfied.
        val file = fixture(ROTATED_V31_API34)
        val v3 = ApkReader.readSignature(file, platformSdk = 33)
        assertEquals(SignatureScheme.V3, v3.scheme)
        assertEquals(listOf(CERT_A_SHA256), signerHashes(v3))
        assertEquals(SignatureScheme.V31, ApkReader.readSignature(file, platformSdk = 34).scheme)
    }

    @Test
    fun `a stripped v3_1 block is rejected from API 33 like the platform does`() {
        val stripped = ApkFixtures.writeTo(temp.newFile("stripped.apk"), withoutV31Block(ROTATED_V31_API34))
        // The v3 signer covers API 33 and promises a v3.1 block for 34: stripping protection.
        val error = assertThrows(ApkSignatureException::class.java) { ApkReader.readSignature(stripped, platformSdk = 33) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("v3.1"))
        // API 28 to 32 do not know the protection and use the v3 signer.
        assertEquals(listOf(CERT_A_SHA256), signerHashes(ApkReader.readSignature(stripped, platformSdk = 32)))
        // On 34 nothing is left for the device: the v3 signer ends at 33.
        assertThrows(ApkFormatException::class.java) { ApkReader.readSignature(stripped, platformSdk = 34) }
    }

    @Test
    fun `apksigner's default rotation without its v3_1 block has no signer from API 33`() {
        val stripped = ApkFixtures.writeTo(temp.newFile("stripped-default.apk"), withoutV31Block(ROTATED_V31))
        assertThrows(ApkFormatException::class.java) { ApkReader.readSignature(stripped, platformSdk = 33) }
        assertEquals(listOf(CERT_A_SHA256), signerHashes(ApkReader.readSignature(stripped, platformSdk = 32)))
    }

    @Test
    fun `a lineage link with a broken signature is rejected`() {
        val lineage = lineageBytes(ApkFixtures.bytes(ROTATED))
        assertEquals(2, SignatureSchemeReader.readLineage(LittleEndianReader(lineage)).size)

        // The last field of the record is level 2's signature, made by key A over B's certificate.
        lineage[lineage.size - 1] = (lineage[lineage.size - 1].toInt() xor 0x01).toByte()
        assertThrows(ApkSignatureException::class.java) { SignatureSchemeReader.readLineage(LittleEndianReader(lineage)) }
    }

    // Rejections

    @Test
    fun `a v1-only APK is rejected with a clear reason`() {
        val error = assertThrows(ApkFormatException::class.java) { ApkReader.readSignature(fixture(V1_ONLY), 37) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("JAR signing (v1) only"))
    }

    @Test
    fun `a zip without a signing block is rejected`() {
        val file = zipOf(temp.newFile("unsigned.apk"), "AndroidManifest.xml" to manifestBytes(GOOD_V2V3))
        // The manifest is fine; only the signature is missing.
        assertEquals(SAMPLE_PACKAGE, ApkReader.readManifest(file).packageName)
        val error = assertThrows(ApkFormatException::class.java) { ApkReader.readSignature(file, 37) }
        assertTrue(error.message.orEmpty().contains("no APK Signing Block"))
    }

    @Test
    fun `a file that is not a zip is rejected`() {
        val file = ApkFixtures.writeTo(temp.newFile("text.apk"), "This is not an APK.".repeat(10).toByteArray())
        assertThrows(ApkFormatException::class.java) { ApkReader.readSignature(file, 37) }
        assertThrows(ApkFormatException::class.java) { ApkReader.readManifest(file) }
    }

    @Test
    fun `an empty file is rejected`() {
        val file = temp.newFile("empty.apk")
        assertThrows(ApkFormatException::class.java) { ApkReader.readSignature(file, 37) }
        assertThrows(ApkFormatException::class.java) { ApkReader.readManifest(file) }
    }

    @Test
    fun `data after the end of the zip is rejected`() {
        val file = ApkFixtures.writeTo(temp.newFile("trailing.apk"), ApkFixtures.bytes(GOOD_V2V3) + byteArrayOf(0, 0, 0, 0, 0))
        assertThrows(ApkFormatException::class.java) { ApkReader.readSignature(file, 37) }
    }

    @Test
    fun `a truncated APK is rejected`() {
        val bytes = ApkFixtures.bytes(GOOD_V2V3)
        val file = ApkFixtures.writeTo(temp.newFile("truncated.apk"), bytes.copyOf(bytes.size - 100))
        assertThrows(ApkFormatException::class.java) { ApkReader.readSignature(file, 37) }
    }

    @Test
    fun `a flipped byte in the signed data fails the signature check`() {
        listOf(GOOD_V2V3 to 37, GOOD_V2V3 to 27, ROTATED to 37, OTHER_KEY to 37).forEach { (name, sdk) ->
            val file = ApkFixtures.writeTo(temp.newFile("tampered-$name-$sdk"), tampered(name))
            assertThrows("$name on API $sdk", ApkSignatureException::class.java) { ApkReader.readSignature(file, sdk) }
        }
    }

    @Test
    fun `a missing file is an I-O error, not a format error`() {
        val missing = File(temp.root, "missing.apk")
        assertThrows(FileNotFoundException::class.java) { ApkReader.readSignature(missing, 37) }
    }

    private fun manifestBytes(fixture: String): ByteArray = ZipFile(fixture(fixture)).use { zip ->
        zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() }
    }

    private fun zipOf(
        file: File,
        vararg entries: Pair<String, ByteArray>,
    ): File {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    /**
     * The fixture with its v3.1 block removed. The block is a separate entry of the signing
     * block, outside every signature, so renaming its ID removes it without breaking anything.
     */
    private fun withoutV31Block(name: String): ByteArray {
        val bytes = ApkFixtures.bytes(name)
        val at = ApkFixtures.indexesOf(bytes, littleEndian(SignatureSchemeReader.V31_BLOCK_ID)).single()
        littleEndian(0x7e57ed00).copyInto(bytes, at)
        return bytes
    }

    private fun littleEndian(value: Int): ByteArray = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private fun intAt(
        bytes: ByteArray,
        offset: Int,
    ): Int = LittleEndianReader(bytes, offset, offset + 4).int("test field")

    companion object {
        /**
         * The fixture with the last byte of every copy of its signing certificate flipped. That
         * byte belongs to the certificate's own signature value, so the certificate still parses,
         * but every v2 and v3 signed data block that lists it no longer matches its signature.
         */
        fun tampered(name: String): ByteArray {
            val bytes = ApkFixtures.bytes(name)
            val file = File.createTempFile("renovo-tamper", ".apk")
            val certificate =
                try {
                    ApkReader
                        .readSignature(ApkFixtures.writeTo(file, bytes), 37)
                        .signers
                        .single()
                        .certificate
                        .encoded
                } finally {
                    file.delete()
                }
            val places = ApkFixtures.indexesOf(bytes, certificate)
            check(places.isNotEmpty())
            places.forEach { start ->
                val last = start + certificate.size - 1
                bytes[last] = (bytes[last].toInt() xor 0x01).toByte()
            }
            return bytes
        }
    }

    /**
     * The proof-of-rotation record of [ROTATED], found by its layout: attribute length, attribute
     * ID 0x3ba06f8c, version 1, then level 1, whose signed data starts with certificate A.
     */
    private fun lineageBytes(apk: ByteArray): ByteArray {
        val certificateA = ApkFixtures.certificateA
        val start =
            ApkFixtures.indexesOf(apk, certificateA).map { it - 16 }.single { recordStart ->
                recordStart >= 8 &&
                    intAt(apk, recordStart - 4) == 0x3ba06f8c &&
                    intAt(apk, recordStart) == 1 &&
                    intAt(apk, recordStart + 12) == certificateA.size
            }
        val attributeLength = intAt(apk, start - 8)
        return apk.copyOfRange(start, start - 4 + attributeLength)
    }
}
