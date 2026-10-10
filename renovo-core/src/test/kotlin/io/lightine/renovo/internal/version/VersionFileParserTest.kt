package io.lightine.renovo.internal.version

import io.lightine.renovo.ReleaseNotes
import io.lightine.renovo.UpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric, because the plain unit test classpath only has android.jar stubs: every org.json
// method there throws "not mocked". Robolectric runs the real Android implementation. The SDK is
// pinned to 35 because Robolectric's SDK 36 and 37 sandboxes fail on JDK 21 without extra JVM
// arguments, and org.json is the same in all of them.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class VersionFileParserTest {
    private val sha = "9f2c".padEnd(64, 'e')

    private fun rejects(
        json: String,
        messagePart: String,
    ) {
        val e = assertThrows(VersionFileException::class.java) { VersionFileParser.parse(json) }
        assertEquals(true, e.message?.contains(messagePart, ignoreCase = true))
    }

    @Test
    fun `parses the full example of the specification`() {
        val info =
            VersionFileParser.parse(
                """
                {
                  "versionCode": 42,
                  "versionName": "1.8.0",
                  "apkUrl": "https://example.com/app/app-1.8.0.apk",
                  "sha256": "${sha.uppercase()}",
                  "size": 25165824,
                  "mandatory": true,
                  "minSupportedVersionCode": 38,
                  "releaseNotes": {
                    "az": "- Sinxronizasiya **sürətləndirildi**",
                    "en": "- Sync is **faster**\n- Bug fixes"
                  }
                }
                """.trimIndent(),
            )
        val expected =
            UpdateInfo(
                versionCode = 42,
                versionName = "1.8.0",
                apkUrl = "https://example.com/app/app-1.8.0.apk",
                sha256 = sha,
                size = 25165824,
                mandatory = true,
                minSupportedVersionCode = 38,
                releaseNotes =
                ReleaseNotes.of(
                    "az" to "- Sinxronizasiya **sürətləndirildi**",
                    "en" to "- Sync is **faster**\n- Bug fixes",
                ),
            )
        assertEquals(expected, info)
        // The hash is stored in lowercase whatever the case in the file.
        assertEquals(sha, info.sha256)
    }

    @Test
    fun `parses a minimal file and applies the defaults`() {
        val info = VersionFileParser.parse("""{"versionCode":1,"versionName":"1.0","apkUrl":"https://example.com/a.apk"}""")
        assertEquals(UpdateInfo(1, "1.0", "https://example.com/a.apk"), info)
        assertFalse(info.mandatory)
        assertNull(info.sha256)
        assertNull(info.size)
        assertNull(info.minSupportedVersionCode)
        assertNull(info.releaseNotes)
    }

    @Test
    fun `treats null like a missing optional field`() {
        val info =
            VersionFileParser.parse(
                """{"versionCode":1,"versionName":"1.0","apkUrl":"https://example.com/a.apk",
                "sha256":null,"size":null,"mandatory":null,"minSupportedVersionCode":null,"releaseNotes":null}""",
            )
        assertEquals(UpdateInfo(1, "1.0", "https://example.com/a.apk"), info)
    }

    @Test
    fun `ignores unknown fields of every type`() {
        val info =
            VersionFileParser.parse(
                """{"versionCode":2,"versionName":"2.0","apkUrl":"https://example.com/a.apk",
                "channel":"beta","rollout":{"percent":10},"tags":["a","b"],"flag":true}""",
            )
        assertEquals(2L, info.versionCode)
    }

    @Test
    fun `rejects broken JSON without repeating it`() {
        rejects("""{"versionCode": 4""", "not a valid JSON object")
        rejects("", "not a valid JSON object")
        rejects("<html>Bad gateway</html>", "not a valid JSON object")
        rejects("[1,2,3]", "not a valid JSON object")
        val e = assertThrows(VersionFileException::class.java) { VersionFileParser.parse("""{"secret-token": 4""") }
        assertEquals(false, e.message?.contains("secret-token"))
    }

    @Test
    fun `accepts a version code given as a string of digits`() {
        val info =
            VersionFileParser.parse(
                """{"versionCode":"42","versionName":"1.8.0","apkUrl":"https://example.com/a.apk",
                "size":"1024","minSupportedVersionCode":"7"}""",
            )
        assertEquals(42L, info.versionCode)
        assertEquals(1024L, info.size)
        assertEquals(7L, info.minSupportedVersionCode)
    }

    @Test
    fun `keeps every digit of a large version code string`() {
        val info = VersionFileParser.parse("""{"versionCode":"9007199254740993","versionName":"1","apkUrl":"https://example.com/a.apk"}""")
        assertEquals(9007199254740993L, info.versionCode)
    }

    @Test
    fun `accepts a whole number written with a fraction part of zero`() {
        val info = VersionFileParser.parse("""{"versionCode":42.0,"versionName":"1","apkUrl":"https://example.com/a.apk"}""")
        assertEquals(42L, info.versionCode)
    }

    @Test
    fun `rejects a fractional or non-numeric version code`() {
        val rest = ""","versionName":"1","apkUrl":"https://example.com/a.apk"}"""
        rejects("""{"versionCode":42.5$rest""", "versionCode must be a whole number")
        rejects("""{"versionCode":"42.5"$rest""", "versionCode must be a whole number")
        rejects("""{"versionCode":"forty"$rest""", "versionCode must be a whole number")
        rejects("""{"versionCode":true$rest""", "versionCode must be a whole number")
        rejects("""{"versionCode":1e40$rest""", "versionCode must be a whole number")
    }

    @Test
    fun `rejects a version code below one`() {
        rejects("""{"versionCode":0,"versionName":"1","apkUrl":"https://example.com/a.apk"}""", "versionCode")
    }

    @Test
    fun `reads release notes given as one string`() {
        val info =
            VersionFileParser.parse(
                """{"versionCode":1,"versionName":"1","apkUrl":"https://example.com/a.apk","releaseNotes":"- Bug fixes"}""",
            )
        assertEquals(ReleaseNotes("- Bug fixes"), info.releaseNotes)
    }

    @Test
    fun `reads release notes given per language and keeps their order`() {
        val info =
            VersionFileParser.parse(
                """{"versionCode":1,"versionName":"1","apkUrl":"https://example.com/a.apk",
                "releaseNotes":{"az":"A","ru":"R"}}""",
            )
        assertEquals(ReleaseNotes.of("az" to "A", "ru" to "R"), info.releaseNotes)
        // Neither "de" nor "en" exists, so the first entry of the file is the fallback.
        assertEquals("A", info.releaseNotes?.forLanguage("de"))
    }

    @Test
    fun `treats an empty release notes object as no notes`() {
        val info =
            VersionFileParser.parse("""{"versionCode":1,"versionName":"1","apkUrl":"https://example.com/a.apk","releaseNotes":{}}""")
        assertNull(info.releaseNotes)
    }

    @Test
    fun `rejects release notes of the wrong type`() {
        val head = """{"versionCode":1,"versionName":"1","apkUrl":"https://example.com/a.apk","releaseNotes":"""
        rejects("$head[\"a\"]}", "releaseNotes")
        rejects("$head{\"en\":5}}", "releaseNotes")
        rejects("$head{\"\":\"x\"}}", "not allowed")
        rejects("$head{\"en\":\"x\",\"EN\":\"y\"}}", "not allowed")
    }

    @Test
    fun `rejects an http apk url without repeating it`() {
        val e =
            assertThrows(VersionFileException::class.java) {
                VersionFileParser.parse("""{"versionCode":1,"versionName":"1","apkUrl":"http://example.com/a.apk?token=abc"}""")
            }
        assertEquals(true, e.message?.contains("https"))
        assertEquals(false, e.message?.contains("token"))
    }

    @Test
    fun `rejects a missing required field`() {
        rejects("""{"versionName":"1","apkUrl":"https://example.com/a.apk"}""", "versionCode is missing")
        rejects("""{"versionCode":1,"apkUrl":"https://example.com/a.apk"}""", "versionName is missing")
        rejects("""{"versionCode":1,"versionName":"1"}""", "apkUrl is missing")
        rejects("""{"versionCode":null,"versionName":"1","apkUrl":"https://example.com/a.apk"}""", "versionCode is missing")
    }

    @Test
    fun `rejects fields of the wrong type`() {
        rejects("""{"versionCode":1,"versionName":1.8,"apkUrl":"https://example.com/a.apk"}""", "versionName must be a string")
        rejects("""{"versionCode":1,"versionName":" ","apkUrl":"https://example.com/a.apk"}""", "versionName")
        rejects(
            """{"versionCode":1,"versionName":"1","apkUrl":"https://example.com/a.apk","mandatory":"yes"}""",
            "mandatory must be true or false",
        )
        rejects("""{"versionCode":1,"versionName":"1","apkUrl":"https://example.com/a.apk","size":0}""", "size")
    }

    @Test
    fun `rejects a bad sha256`() {
        val head = """{"versionCode":1,"versionName":"1","apkUrl":"https://example.com/a.apk","sha256":"""
        rejects("$head\"abc\"}", "sha256")
        rejects("$head\"${"z".repeat(64)}\"}", "sha256")
        rejects("${head}123}", "sha256 must be a string")
    }
}
