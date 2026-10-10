package io.lightine.renovo.internal.notify

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import io.lightine.renovo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

// The expected values are copied from the UI spec, section 2.8. Do not reword them.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationStringsTest {
    private fun contextFor(language: String): Context {
        val base: Context = ApplicationProvider.getApplicationContext()
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return base.createConfigurationContext(configuration)
    }

    @Test
    fun `English strings match the UI spec`() {
        val context = contextFor("en")

        assertEquals("App updates", context.getString(R.string.renovo_notification_channel_name))
        assertEquals("Update ready", context.getString(R.string.renovo_notification_ready_title))
        assertEquals("Tap to install version 1.8.0.", context.getString(R.string.renovo_notification_ready_text, "1.8.0"))
    }

    @Test
    fun `Azerbaijani strings match the UI spec`() {
        val context = contextFor("az")

        assertEquals("Tətbiq yeniləmələri", context.getString(R.string.renovo_notification_channel_name))
        assertEquals("Yeniləmə hazırdır", context.getString(R.string.renovo_notification_ready_title))
        assertEquals("1.8.0 versiyasını quraşdırmaq üçün toxunun.", context.getString(R.string.renovo_notification_ready_text, "1.8.0"))
    }

    @Test
    fun `Azerbaijani strings are not the English fallback`() {
        val english = contextFor("en")
        val azerbaijani = contextFor("az")

        listOf(
            R.string.renovo_notification_channel_name,
            R.string.renovo_notification_ready_title,
        ).forEach { id ->
            assertNotEquals(english.getString(id), azerbaijani.getString(id))
        }
    }
}
