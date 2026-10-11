package io.lightine.renovo.internal.verify

import io.lightine.renovo.FailureReason
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.internal.VerificationResult
import io.lightine.renovo.internal.verify.ApkFixtures.GOOD_V2V3
import io.lightine.renovo.internal.verify.ApkFixtures.GOOD_V2_ONLY
import io.lightine.renovo.internal.verify.ApkFixtures.LOWER_VERSION
import io.lightine.renovo.internal.verify.ApkFixtures.OTHER_KEY
import io.lightine.renovo.internal.verify.ApkFixtures.OTHER_PACKAGE
import io.lightine.renovo.internal.verify.ApkFixtures.ROTATED
import io.lightine.renovo.internal.verify.ApkFixtures.ROTATED_NO_INSTALLED_DATA
import io.lightine.renovo.internal.verify.ApkFixtures.ROTATED_V31
import io.lightine.renovo.internal.verify.ApkFixtures.V1_ONLY
import io.lightine.renovo.internal.verify.ApkFixtures.VERSION_MAJOR
import io.lightine.renovo.internal.verify.ApkFixtures.certificateA
import io.lightine.renovo.internal.verify.ApkFixtures.certificateB
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class ApkVerifierTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val logged = CopyOnWriteArrayList<String>()
    private val logger = RenovoLogger { message, _ -> logged += message }

    private fun fixture(name: String): File = ApkFixtures.copy(name, temp.root)

    private fun info(
        versionCode: Long = 2,
        sha256: String? = null,
    ): UpdateInfo = UpdateInfo(
        versionCode = versionCode,
        versionName = "$versionCode",
        apkUrl = "https://example.com/app.apk",
        sha256 = sha256,
    )

    private fun verifyResult(
        file: File,
        info: UpdateInfo = info(),
        installed: InstalledApp = FakeInstalledApp(),
        platformSdk: Int = 37,
    ): VerificationResult {
        var result: VerificationResult? = null
        runTest {
            result =
                ApkVerifier(installed, logger, platformSdk, UnconfinedTestDispatcher(testScheduler))
                    .verify(file, info, installed.versionCode())
        }
        return checkNotNull(result)
    }

    private fun failed(reason: FailureReason) = VerificationResult.Failed(reason)

    // Every fixture against the installed sample app: key A, versionCode 1.

    @Test
    fun `an APK signed with the installed key passes, with v3 and with v2`() {
        listOf(24, 27, 28, 33, 37).forEach { sdk ->
            assertEquals("v2v3 on $sdk", VerificationResult.Passed, verifyResult(fixture(GOOD_V2V3), platformSdk = sdk))
            assertEquals("v2 on $sdk", VerificationResult.Passed, verifyResult(fixture(GOOD_V2_ONLY), platformSdk = sdk))
        }
    }

    @Test
    fun `an APK signed with another key is a signature mismatch`() {
        listOf(27, 37).forEach { sdk ->
            assertEquals(failed(FailureReason.SignatureMismatch), verifyResult(fixture(OTHER_KEY), platformSdk = sdk))
        }
    }

    @Test
    fun `a rotated APK passes when the installed key is in its lineage`() {
        // On API 28+ the v3 signer is B, accepted only through the lineage from A.
        assertEquals(VerificationResult.Passed, verifyResult(fixture(ROTATED), platformSdk = 37))
        // Below API 28 the platform reads v2, which is still signed by A.
        assertEquals(VerificationResult.Passed, verifyResult(fixture(ROTATED), platformSdk = 27))
        // An app that already runs on key B updates with the same APK.
        assertEquals(
            VerificationResult.Passed,
            verifyResult(fixture(ROTATED), installed = FakeInstalledApp(certificates = listOf(certificateB))),
        )
    }

    @Test
    fun `a rotated APK fails when the old key may not keep the app's data`() {
        assertEquals(failed(FailureReason.SignatureMismatch), verifyResult(fixture(ROTATED_NO_INSTALLED_DATA), platformSdk = 37))
    }

    @Test
    fun `the lineage never lets the old key stand in for a rotated app`() {
        // The installed app has rotated to B. An APK signed only by A (B is not in its lineage)
        // must not pass, even though A is B's predecessor.
        val installedOnB = FakeInstalledApp(certificates = listOf(certificateB))
        assertEquals(failed(FailureReason.SignatureMismatch), verifyResult(fixture(GOOD_V2V3), installed = installedOnB))
    }

    @Test
    fun `a v3_1 rotation passes on every API level`() {
        listOf(27, 32, 33, 37).forEach { sdk ->
            assertEquals("installed A on $sdk", VerificationResult.Passed, verifyResult(fixture(ROTATED_V31), platformSdk = sdk))
        }
        val installedOnB = FakeInstalledApp(certificates = listOf(certificateB))
        assertEquals(VerificationResult.Passed, verifyResult(fixture(ROTATED_V31), installed = installedOnB, platformSdk = 33))
    }

    @Test
    fun `a v1-only APK is rejected as not a valid APK`() {
        assertEquals(failed(FailureReason.HashMismatch), verifyResult(fixture(V1_ONLY)))
        assertTrue(logged.single(), logged.single().contains("JAR signing (v1) only"))
    }

    @Test
    fun `a lower version is not newer`() {
        assertEquals(
            failed(FailureReason.NotNewer),
            verifyResult(fixture(LOWER_VERSION), info = info(versionCode = 1)),
        )
    }

    @Test
    fun `an equal version is not newer`() {
        assertEquals(
            failed(FailureReason.NotNewer),
            verifyResult(fixture(GOOD_V2V3), installed = FakeInstalledApp(versionCode = 2)),
        )
    }

    @Test
    fun `another package is a signature mismatch`() {
        assertEquals(failed(FailureReason.SignatureMismatch), verifyResult(fixture(OTHER_PACKAGE)))
    }

    @Test
    fun `a flipped byte in the signed data is a hash mismatch`() {
        listOf(27, 37).forEach { sdk ->
            val file = ApkFixtures.writeTo(temp.newFile("tampered-$sdk.apk"), ApkReaderTest.tampered(GOOD_V2V3))
            assertEquals(failed(FailureReason.HashMismatch), verifyResult(file, platformSdk = sdk))
        }
    }

    // Version code against the version file

    @Test
    fun `a version code other than the announced one is a hash mismatch`() {
        // The file is newer than the installed app, but it is not the version the file announced.
        assertEquals(failed(FailureReason.HashMismatch), verifyResult(fixture(GOOD_V2V3), info = info(versionCode = 3)))
    }

    @Test
    fun `newer than the installed app is checked before the announced version`() {
        // The spec's order: "not newer" comes first. The lower APK is reported as old, whatever the
        // version file announced, so AC-2's lower or equal version is NotNewer and never a mismatch.
        assertEquals(
            failed(FailureReason.NotNewer),
            verifyResult(fixture(LOWER_VERSION), info = info(versionCode = 2)),
        )
        assertEquals(
            failed(FailureReason.NotNewer),
            verifyResult(fixture(LOWER_VERSION), info = info(versionCode = 1)),
        )
        // An equal version too, announced or not.
        assertEquals(
            failed(FailureReason.NotNewer),
            verifyResult(fixture(GOOD_V2V3), info = info(versionCode = 3), installed = FakeInstalledApp(versionCode = 2)),
        )
        assertTrue(logged.last(), logged.last().contains("not higher than the installed"))
    }

    @Test
    fun `versionCodeMajor counts in the comparison`() {
        val major = 0x1_0000_0002L
        assertEquals(VerificationResult.Passed, verifyResult(fixture(VERSION_MAJOR), info = info(versionCode = major)))
        assertEquals(failed(FailureReason.HashMismatch), verifyResult(fixture(VERSION_MAJOR), info = info(versionCode = 2)))
        assertEquals(
            failed(FailureReason.NotNewer),
            verifyResult(fixture(VERSION_MAJOR), info = info(versionCode = major), installed = FakeInstalledApp(versionCode = major)),
        )
    }

    // SHA-256

    @Test
    fun `the right SHA-256 passes`() {
        val file = fixture(GOOD_V2V3)
        assertEquals(VerificationResult.Passed, verifyResult(file, info = info(sha256 = ApkFixtures.sha256Hex(file.readBytes()))))
    }

    @Test
    fun `the right SHA-256 in upper case passes`() {
        val file = fixture(GOOD_V2V3)
        val hash = ApkFixtures.sha256Hex(file.readBytes()).uppercase()
        assertEquals(VerificationResult.Passed, verifyResult(file, info = info(sha256 = hash)))
    }

    @Test
    fun `a wrong SHA-256 is a hash mismatch, before every other check`() {
        val wrong = "0".repeat(64)
        assertEquals(failed(FailureReason.HashMismatch), verifyResult(fixture(GOOD_V2V3), info = info(sha256 = wrong)))
        // The hash comes first: another app with a wrong hash still reports the hash.
        assertEquals(failed(FailureReason.HashMismatch), verifyResult(fixture(OTHER_PACKAGE), info = info(sha256 = wrong)))
    }

    // Check order

    @Test
    fun `the package is checked before the version and the signature`() {
        val installedOnB = FakeInstalledApp(certificates = listOf(certificateB))
        // other-package.apk is signed by A, so with an app on key B both checks would fail.
        assertEquals(failed(FailureReason.SignatureMismatch), verifyResult(fixture(OTHER_PACKAGE), installed = installedOnB))
        assertTrue(logged.single().contains("different app"))
    }

    @Test
    fun `the version is checked before the signature`() {
        assertEquals(
            failed(FailureReason.NotNewer),
            verifyResult(fixture(OTHER_KEY), installed = FakeInstalledApp(versionCode = 2)),
        )
    }

    // Other failures

    @Test
    fun `an unreadable file is a storage failure`() {
        val missing = File(temp.root, "missing.apk")
        assertEquals(failed(FailureReason.Storage), verifyResult(missing))
        assertEquals(failed(FailureReason.Storage), verifyResult(missing, info = info(sha256 = "0".repeat(64))))
    }

    @Test
    fun `a file that is not a zip is a hash mismatch`() {
        val file = ApkFixtures.writeTo(temp.newFile("text.apk"), "not an APK".repeat(10).toByteArray())
        assertEquals(failed(FailureReason.HashMismatch), verifyResult(file))
    }

    @Test
    fun `a file the system cannot parse is a hash mismatch`() {
        assertEquals(failed(FailureReason.HashMismatch), verifyResult(fixture(GOOD_V2V3), installed = FakeInstalledApp(canParse = false)))
    }

    @Test
    fun `no installed certificates is a signature mismatch`() {
        assertEquals(failed(FailureReason.SignatureMismatch), verifyResult(fixture(GOOD_V2V3), installed = FakeInstalledApp(certificates = emptyList())))
    }

    @Test
    fun `several installed signers must match the APK's signers exactly`() {
        val twoSigners = FakeInstalledApp(certificates = listOf(certificateA, certificateB))
        assertEquals(failed(FailureReason.SignatureMismatch), verifyResult(fixture(GOOD_V2V3), installed = twoSigners))
    }

    @Test
    fun `an exception from the installed app is a failure, not a crash`() {
        val broken =
            object : InstalledApp by FakeInstalledApp() {
                override fun signingCertificates(): List<ByteArray> = throw IllegalStateException("package manager died")
            }
        assertEquals(failed(FailureReason.HashMismatch), verifyResult(fixture(GOOD_V2V3), installed = broken))
    }

    @Test
    fun `the file is never deleted`() {
        val file = fixture(OTHER_KEY)
        verifyResult(file)
        assertTrue(file.exists())
    }

    @Test
    fun `a passing check logs nothing`() {
        verifyResult(fixture(GOOD_V2V3))
        assertFalse(logged.any())
    }

    @Test
    fun `cancellation is not swallowed`() {
        val cancelled =
            object : InstalledApp by FakeInstalledApp() {
                override fun signingCertificates(): List<ByteArray> = throw CancellationException("cancelled")
            }
        val verifier = ApkVerifier(cancelled, logger, 37)
        assertThrows(CancellationException::class.java) {
            runBlocking { verifier.verify(fixture(GOOD_V2V3), info(), 1) }
        }
        assertFalse(logged.any())
    }
}
