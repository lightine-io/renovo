package io.lightine.renovo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateInfoTest {
    private val url = "https://example.com/app.apk"

    @Test
    fun `accepts a minimal valid version`() {
        val info = UpdateInfo(versionCode = 1, versionName = "1.0", apkUrl = url)

        assertEquals(1L, info.versionCode)
        assertNull(info.sha256)
        assertNull(info.size)
        assertEquals(false, info.mandatory)
        assertNull(info.minSupportedVersionCode)
        assertNull(info.releaseNotes)
    }

    @Test
    fun `rejects a version code below 1`() {
        assertThrows(IllegalArgumentException::class.java) { UpdateInfo(0, "1.0", url) }
        assertThrows(IllegalArgumentException::class.java) { UpdateInfo(-5, "1.0", url) }
    }

    @Test
    fun `rejects a blank version name`() {
        assertThrows(IllegalArgumentException::class.java) { UpdateInfo(1, "", url) }
        assertThrows(IllegalArgumentException::class.java) { UpdateInfo(1, "   ", url) }
    }

    @Test
    fun `rejects an apk url that is not https`() {
        listOf(
            "http://example.com/app.apk",
            "ftp://example.com/app.apk",
            "example.com/app.apk",
            "https:///app.apk",
            "https://exa mple.com/app.apk",
            "",
        ).forEach { bad ->
            assertThrows("Accepted \"$bad\"", IllegalArgumentException::class.java) { UpdateInfo(1, "1.0", bad) }
        }
    }

    @Test
    fun `accepts https in any case`() {
        UpdateInfo(1, "1.0", "HTTPS://example.com/app.apk")
    }

    @Test
    fun `normalizes the hash to lowercase`() {
        val info = UpdateInfo(1, "1.0", url, sha256 = "AB".repeat(32))

        assertEquals("ab".repeat(32), info.sha256)
        assertEquals(UpdateInfo(1, "1.0", url, sha256 = "ab".repeat(32)), info)
    }

    @Test
    fun `rejects a hash that is not 64 hexadecimal characters`() {
        listOf("ab".repeat(31), "ab".repeat(33), "zz".repeat(32), " " + "ab".repeat(32), "").forEach { bad ->
            assertThrows("Accepted \"$bad\"", IllegalArgumentException::class.java) { UpdateInfo(1, "1.0", url, sha256 = bad) }
        }
    }

    @Test
    fun `rejects a size that is not positive`() {
        assertThrows(IllegalArgumentException::class.java) { UpdateInfo(1, "1.0", url, size = 0) }
        assertThrows(IllegalArgumentException::class.java) { UpdateInfo(1, "1.0", url, size = -1) }
    }

    @Test
    fun `equal values are equal`() {
        fun make(notes: String) = UpdateInfo(2, "2.0", url, null, 10, true, 1, ReleaseNotes(notes))

        assertEquals(make("a"), make("a"))
        assertEquals(make("a").hashCode(), make("a").hashCode())
        assertNotEquals(make("a"), make("b"))
    }
}
