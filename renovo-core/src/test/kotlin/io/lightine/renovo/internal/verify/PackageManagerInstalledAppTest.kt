package io.lightine.renovo.internal.verify

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo

/**
 * The API 28+ path, where Renovo reads SigningInfo itself.
 *
 * API 35 rather than 36: Robolectric's API 36 runtime needs extra JVM flags on this JDK
 * (ApplicationSharedMemory), and the code under test does not differ between the two.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class PackageManagerInstalledAppTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val a = byteArrayOf(0x30, 0x01, 0x0a)
    private val b = byteArrayOf(0x30, 0x01, 0x0b)

    private fun install(
        signingInfo: SigningInfo,
        longVersionCode: Long = 1,
    ) {
        val info =
            PackageInfo().apply {
                packageName = context.packageName
                applicationInfo = ApplicationInfo().apply { packageName = context.packageName }
                this.signingInfo = signingInfo
                this.longVersionCode = longVersionCode
            }
        shadowOf(context.packageManager).installPackage(info)
    }

    private fun signingInfo(
        signers: List<ByteArray>,
        history: List<ByteArray>? = null,
    ): SigningInfo {
        val info = Shadow.newInstanceOf(SigningInfo::class.java)
        val shadow = Shadow.extract<ShadowSigningInfo>(info)
        shadow.setSignatures(signers.map { Signature(it) }.toTypedArray())
        history?.let { shadow.setPastSigningCertificates(it.map { bytes -> Signature(bytes) }.toTypedArray()) }
        return info
    }

    private fun certificates(): List<List<Byte>> = PackageManagerInstalledApp(context).signingCertificates().map { it.toList() }

    @Test
    fun `one signer is returned as is`() {
        install(signingInfo(listOf(a)))
        assertEquals(listOf(a.toList()), certificates())
    }

    @Test
    fun `a rotated app returns only its current certificate, not its history`() {
        install(signingInfo(signers = listOf(b), history = listOf(a, b)))
        assertEquals(listOf(b.toList()), certificates())
    }

    @Test
    fun `an app with several signers returns all of them`() {
        install(signingInfo(listOf(a, b)))
        assertEquals(listOf(a.toList(), b.toList()), certificates())
    }

    @Test
    fun `package name and long version code come from the package manager`() {
        install(signingInfo(listOf(a)), longVersionCode = 0x1_0000_0003L)
        val app = PackageManagerInstalledApp(context)
        assertEquals(context.packageName, app.packageName)
        assertEquals(0x1_0000_0003L, app.versionCode())
    }

    @Test
    fun `a file that is not an APK cannot be parsed by the system`() {
        install(signingInfo(listOf(a)))
        val file = kotlin.io.path.createTempFile(suffix = ".apk").toFile()
        try {
            file.writeText("not an APK")
            assertTrue(!PackageManagerInstalledApp(context).canParseArchive(file))
        } finally {
            file.delete()
        }
    }
}
