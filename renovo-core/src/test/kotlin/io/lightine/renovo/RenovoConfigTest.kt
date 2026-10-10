package io.lightine.renovo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RenovoConfigTest {
    private fun build(configure: RenovoConfig.Builder.() -> Unit): RenovoConfig = RenovoConfig.Builder().apply(configure).build()

    @Test
    fun `defaults match the specification`() {
        val config = build { versionUrl = "https://example.com/version.json" }

        assertEquals("https://example.com/version.json", config.versionUrl)
        assertNull(config.versionSource)
        assertEquals(emptyMap<String, String>(), config.versionHeaders())
        assertEquals(6.hours, config.checkInterval)
        assertTrue(config.autoDownload)
        assertEquals(Network.Any, config.optionalDownloadNetwork)
        assertEquals(Network.Any, config.forcedDownloadNetwork)
        assertTrue(config.allowMobileDataOverride)
        assertTrue(config.notifications)
        assertTrue(config.canInstallNow())
        assertNull(config.logger)
        assertEquals(ThemeMode.System, config.ui.themeMode)
        assertTrue(config.ui.dynamicColor)
    }

    @Test
    fun `keeps every setting`() {
        val source = VersionSource { UpdateInfo(2, "2.0", "https://example.com/app.apk") }
        val logger = RenovoLogger { _, _ -> }
        val config =
            build {
                versionSource = source
                versionHeaders = { mapOf("Authorization" to "token") }
                checkInterval = 15.minutes
                autoDownload = false
                optionalDownloadNetwork = Network.UnmeteredOnly
                forcedDownloadNetwork = Network.NotRoaming
                allowMobileDataOverride = false
                notifications = false
                canInstallNow = { false }
                this.logger = logger
                ui { themeMode = ThemeMode.Dark }
                ui { dynamicColor = false }
            }

        assertSame(source, config.versionSource)
        assertNull(config.versionUrl)
        assertEquals(mapOf("Authorization" to "token"), config.versionHeaders())
        assertEquals(15.minutes, config.checkInterval)
        assertEquals(false, config.autoDownload)
        assertEquals(Network.UnmeteredOnly, config.optionalDownloadNetwork)
        assertEquals(Network.NotRoaming, config.forcedDownloadNetwork)
        assertEquals(false, config.allowMobileDataOverride)
        assertEquals(false, config.notifications)
        assertEquals(false, config.canInstallNow())
        assertSame(logger, config.logger)
        // Two ui blocks add up instead of resetting each other.
        assertEquals(ThemeMode.Dark, config.ui.themeMode)
        assertEquals(false, config.ui.dynamicColor)
    }

    @Test
    fun `rejects both versionUrl and versionSource`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                build {
                    versionUrl = "https://example.com/version.json"
                    versionSource = VersionSource { UpdateInfo(2, "2.0", "https://example.com/app.apk") }
                }
            }
        assertEquals("Set either versionUrl or versionSource, not both.", error.message)
    }

    @Test
    fun `rejects neither versionUrl nor versionSource`() {
        val error = assertThrows(IllegalArgumentException::class.java) { build { } }
        assertEquals("Set versionUrl or versionSource.", error.message)
    }

    @Test
    fun `rejects a versionUrl that is not https`() {
        listOf("http://example.com/version.json", "example.com/version.json", "file:///version.json").forEach { bad ->
            assertThrows("Accepted \"$bad\"", IllegalArgumentException::class.java) { build { versionUrl = bad } }
        }
    }

    @Test
    fun `rejects a checkInterval below 15 minutes`() {
        assertThrows(IllegalArgumentException::class.java) {
            build {
                versionUrl = "https://example.com/version.json"
                checkInterval = 15.minutes - 1.seconds
            }
        }
    }

    @Test
    fun `engine settings carry the decision settings`() {
        val settings =
            build {
                versionUrl = "https://example.com/version.json"
                autoDownload = false
                forcedDownloadNetwork = Network.UnmeteredOnly
                notifications = false
            }.engineSettings()

        assertEquals(false, settings.autoDownload)
        assertEquals(Network.UnmeteredOnly, settings.forcedDownloadNetwork)
        assertEquals(false, settings.notifications)
    }
}
