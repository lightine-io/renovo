package io.lightine.renovo.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class StringsTest {
    private val prefix = "renovo_ui_"

    // Gradle runs unit tests with the module directory as the working directory.
    private fun keysOf(file: String): Set<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$file"))
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("name").nodeValue }.toSet()
    }

    private fun contextFor(language: String): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return base.createConfigurationContext(configuration)
    }

    private fun resourceId(key: String): Int = R.string::class.java.getField(prefix + key).getInt(null)

    // A value with placeholders is formatted with "A" and "B" so the comparison covers the placeholders too.
    private fun format(
        context: Context,
        key: String,
    ): String = context.getString(resourceId(key), "A", "B")

    private fun expected(text: String): String = text.replace("%1\$s", "A").replace("%2\$s", "B")

    @Test
    fun everyKeyExistsInBothLocales() {
        val english = keysOf("values/strings.xml")
        val azerbaijani = keysOf("values-az/strings.xml")
        assertEquals(english, azerbaijani)
        assertTrue(english.all { it.startsWith(prefix) })
    }

    @Test
    fun theKeysAreExactlyTheStringsOfUiSpecPart2() {
        val expectedKeys = SpecStrings.english.keys.map { prefix + it }.toSet()
        assertEquals(expectedKeys, keysOf("values/strings.xml"))
        assertEquals(SpecStrings.english.keys, SpecStrings.azerbaijani.keys)
    }

    @Test
    fun everyResourceOfTheLibraryIsAnExistingKey() {
        val generated = R.string::class.java.fields.map { it.name }.filter { it.startsWith(prefix) }.toSet()
        assertEquals(keysOf("values/strings.xml"), generated)
    }

    @Test
    fun placeholdersAreTheSameInBothLocales() {
        val placeholder = Regex("%\\d\\$" + "s")
        for ((key, english) in SpecStrings.english) {
            val azerbaijani = SpecStrings.azerbaijani.getValue(key)
            assertEquals(
                key,
                placeholder.findAll(english).map { it.value }.sorted().toList(),
                placeholder.findAll(azerbaijani).map { it.value }.sorted().toList(),
            )
        }
    }

    @Test
    fun everyEnglishStringMatchesTheSpec() {
        val context = contextFor("en")
        for ((key, text) in SpecStrings.english) {
            assertEquals(key, expected(text), format(context, key))
        }
    }

    @Test
    fun everyAzerbaijaniStringMatchesTheSpec() {
        val context = contextFor("az")
        for ((key, text) in SpecStrings.azerbaijani) {
            assertEquals(key, expected(text), format(context, key))
        }
    }

    // Spelled out here as well, so a mistake in the generated table cannot hide a mistake in a resource.
    @Test
    fun selectedAzerbaijaniStringsAreExactlyTheSpecText() {
        val context = contextFor("az")
        val table =
            listOf(
                Triple(R.string.renovo_ui_available_headline, emptyList<String>(), "Yeniləmə mövcuddur"),
                Triple(
                    R.string.renovo_ui_available_body,
                    listOf("1.8.0"),
                    "1.8.0 versiyası yüklənməyə hazırdır. Saxlanılmış məlumatlarınız bu cihazda qalır.",
                ),
                Triple(R.string.renovo_ui_version_line, listOf("1.8.0", "24 MB"), "Versiya 1.8.0 - 24 MB"),
                Triple(R.string.renovo_ui_keyboard_hint, emptyList<String>(), "Enter - yenilə, Esc - sonra"),
                Triple(R.string.renovo_ui_download_size, listOf("6 MB", "24 MB"), "6 MB / 24 MB"),
                Triple(R.string.renovo_ui_download_progress_description, listOf("25"), "Yükləmə gedişatı, 25 faiz"),
                Triple(R.string.renovo_ui_ready_headline, emptyList<String>(), "Quraşdırmağa hazırdır"),
                Triple(
                    R.string.renovo_ui_ready_verified,
                    emptyList<String>(),
                    "Yoxlanıldı - fayl bütövdür və eyni naşirə məxsusdur",
                ),
                Triple(R.string.renovo_ui_not_now, emptyList<String>(), "İndi yox"),
                Triple(
                    R.string.renovo_ui_permission_step_1,
                    emptyList<String>(),
                    "\"Parametrləri aç\" düyməsinə toxunun",
                ),
                Triple(
                    R.string.renovo_ui_permission_step_2_legacy,
                    emptyList<String>(),
                    "Naməlum mənbələrdən quraşdırmanı aktiv edin",
                ),
                Triple(R.string.renovo_ui_cancel, emptyList<String>(), "Ləğv et"),
                Triple(R.string.renovo_ui_update_now, emptyList<String>(), "İndi yenilə"),
                Triple(
                    R.string.renovo_ui_forced_mobile_data_button,
                    listOf("24 MB"),
                    "Mobil internetlə yüklə (24 MB)",
                ),
                Triple(R.string.renovo_ui_failed_reason_label, emptyList<String>(), "Səbəb:"),
                Triple(R.string.renovo_ui_failed_reason_parse, emptyList<String>(), "versiya məlumatı oxuna bilmədi"),
                Triple(
                    R.string.renovo_ui_failed_reason_not_newer,
                    emptyList<String>(),
                    "yüklənmiş versiya daha yeni deyil",
                ),
                Triple(R.string.renovo_ui_try_again, emptyList<String>(), "Yenidən cəhd et"),
            )
        for ((id, arguments, text) in table) {
            assertEquals(text, context.getString(id, *arguments.toTypedArray()))
        }
    }

    @Test
    fun theEnglishApostropheIsAPlainApostrophe() {
        assertEquals("What's new", contextFor("en").getString(R.string.renovo_ui_whats_new))
    }
}
