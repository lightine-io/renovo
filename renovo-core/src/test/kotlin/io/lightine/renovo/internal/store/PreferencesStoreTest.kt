package io.lightine.renovo.internal.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.lightine.renovo.FailureReason
import io.lightine.renovo.ReleaseNotes
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.internal.engine.DownloadRecord
import io.lightine.renovo.internal.engine.Rejection
import io.lightine.renovo.internal.engine.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class PreferencesStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val logged = mutableListOf<String>()
    private val logger = RenovoLogger { message, _ -> logged += message }

    private val rawPreferences get() = context.getSharedPreferences("renovo", Context.MODE_PRIVATE)

    @Before
    fun startEmpty() {
        rawPreferences.edit().clear().commit()
    }

    private fun store() = PreferencesStore(context, logger)

    private fun fullInfo(
        versionCode: Long,
        notes: ReleaseNotes?,
    ) = UpdateInfo(
        versionCode = versionCode,
        versionName = "1.$versionCode.0",
        apkUrl = "https://example.com/app/app-$versionCode.apk",
        sha256 = "AB".repeat(32),
        size = 12_345_678_901L,
        mandatory = true,
        minSupportedVersionCode = 38,
        releaseNotes = notes,
    )

    // Quotes, a backslash, a line separator, and non-ASCII letters: the characters a JSON writer
    // has to escape.
    private val multiLanguage = ReleaseNotes.of(
        "az" to "- Xətalar aradan qaldırıldı\n- **Yeni** görünüş \"dırnaq\" \\  ",
        "en" to "- Bug fixes",
        "ru" to "- Исправления",
    )

    // "ru" first: the first entry is the last fallback, so the order must survive.
    private val reordered = ReleaseNotes.of("ru" to "r", "en" to "e", "az" to "a")

    private fun fullSnapshot() = Snapshot(
        etag = "W/\"abc-123\"",
        latest = fullInfo(42, multiLanguage),
        download = DownloadRecord(
            fullInfo(41, ReleaseNotes("- Sync is **faster**")),
            "/data/user/0/app/files/renovo/app.apk",
            true,
        ),
        mobileDataVersionCode = 41,
        rejected = Rejection(fullInfo(40, reordered), FailureReason.HashMismatch),
        unsupportedAtVersionCode = 39,
        lastInstalledVersionCode = 38,
    )

    private fun storeRaw(
        text: String,
        version: Int = 1,
    ) {
        check(rawPreferences.edit().putString("snapshot", text).putInt("format_version", version).commit())
    }

    @Test
    fun loadOnFreshStoreIsNull() {
        assertNull(store().load())
        assertTrue(logged.isEmpty())
    }

    @Test
    fun fullSnapshotRoundTrips() {
        val snapshot = fullSnapshot()
        val store = store()
        store.save(snapshot)
        assertEquals(snapshot, store.load())
    }

    @Test
    fun everyUpdateInfoFieldSurvives() {
        val info = fullInfo(42, multiLanguage)
        val store = store()
        store.save(Snapshot(latest = info))
        val loaded = checkNotNull(store.load()?.latest)
        assertEquals(42L, loaded.versionCode)
        assertEquals("1.42.0", loaded.versionName)
        assertEquals("https://example.com/app/app-42.apk", loaded.apkUrl)
        assertEquals("ab".repeat(32), loaded.sha256)
        assertEquals(12_345_678_901L, loaded.size)
        assertTrue(loaded.mandatory)
        assertEquals(38L, loaded.minSupportedVersionCode)
        assertEquals(info.releaseNotes, loaded.releaseNotes)
    }

    @Test
    fun updateInfoWithOnlyRequiredFieldsRoundTrips() {
        val snapshot = Snapshot(latest = UpdateInfo(1, "1.0", "https://example.com/a.apk"))
        val store = store()
        store.save(snapshot)
        assertEquals(snapshot, store.load())
    }

    @Test
    fun multiLanguageNotesKeepTheirOrder() {
        val store = store()
        store.save(Snapshot(latest = fullInfo(5, reordered)))
        val loaded = checkNotNull(store.load()?.latest?.releaseNotes)
        assertEquals(reordered, loaded)
        assertEquals(listOf("ru", "en", "az"), loaded.entries.map { it.first })
        // "en" is the fallback for a language nobody wrote; without "en" the first entry is.
        assertEquals("e", loaded.forLanguage("tr"))
        val withoutEnglish = ReleaseNotes.of("ru" to "r", "az" to "a")
        store.save(Snapshot(latest = fullInfo(5, withoutEnglish)))
        assertEquals("r", store.load()?.latest?.releaseNotes?.forLanguage("tr"))
        assertNotEquals(ReleaseNotes.of("az" to "a", "en" to "e", "ru" to "r"), loaded)
    }

    @Test
    fun singleLanguageNotesRoundTrip() {
        val notes = ReleaseNotes("- Sync is **faster**\n- Yeni: ə ı ö ü ç ş ğ")
        val store = store()
        store.save(Snapshot(latest = fullInfo(5, notes)))
        assertEquals(notes, store.load()?.latest?.releaseNotes)
    }

    @Test
    fun singleLanguageNotesWithEmptyTextRoundTrip() {
        val notes = ReleaseNotes("")
        val store = store()
        store.save(Snapshot(latest = fullInfo(5, notes)))
        assertEquals(notes, store.load()?.latest?.releaseNotes)
    }

    @Test
    fun everyFailureReasonRoundTrips() {
        val store = store()
        FailureReason.entries.forEach { reason ->
            val snapshot = Snapshot(rejected = Rejection(fullInfo(7, null), reason))
            store.save(snapshot)
            assertEquals(snapshot, store.load())
        }
    }

    @Test
    fun downloadWithoutFileRoundTrips() {
        val snapshot = Snapshot(download = DownloadRecord(fullInfo(7, null)))
        val store = store()
        store.save(snapshot)
        assertEquals(snapshot, store.load())
    }

    @Test
    fun emptySnapshotRoundTrips() {
        val store = store()
        store.save(Snapshot())
        assertEquals(Snapshot(), store.load())
    }

    @Test
    fun saveReplacesThePreviousSnapshot() {
        val store = store()
        store.save(fullSnapshot())
        store.save(Snapshot(etag = "only"))
        assertEquals(Snapshot(etag = "only"), store.load())
    }

    @Test
    fun clearRemovesTheSnapshot() {
        val store = store()
        store.save(fullSnapshot())
        store.clear()
        assertNull(store.load())
        assertTrue(rawPreferences.all.isEmpty())
        // Clearing an empty store is harmless.
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun aSecondStoreReadsWhatTheFirstSaved() {
        val snapshot = fullSnapshot()
        store().save(snapshot)
        assertEquals(snapshot, store().load())
    }

    @Test
    fun saveIsWrittenWhenItReturns() {
        // Read through a separate handle to the same file, with no wait after save.
        store().save(fullSnapshot())
        assertTrue(rawPreferences.contains("snapshot"))
        assertEquals(1, rawPreferences.getInt("format_version", 0))
    }

    @Test
    fun storesInAPrivateFileNamedRenovo() {
        store().save(Snapshot())
        assertTrue(File(context.applicationInfo.dataDir, "shared_prefs/renovo.xml").exists())
    }

    @Test
    fun textThatIsNotJsonReturnsNullAndLogsWithoutContent() {
        storeRaw("{this is /secret/path not json")
        assertNull(store().load())
        assertEquals(1, logged.size)
        assertTrue(logged.none { it.contains("secret") })
    }

    @Test
    fun jsonOfTheWrongShapeReturnsNull() {
        storeRaw("""{"latest":{"versionCode":"x"}}""")
        assertNull(store().load())
        storeRaw("[1,2,3]")
        assertNull(store().load())
        assertEquals(2, logged.size)
    }

    @Test
    fun valuesThatBreakTheUpdateInfoRulesReturnNull() {
        storeRaw(
            """{"latest":{"versionCode":0,"versionName":"1","apkUrl":"https://example.com/a.apk","mandatory":false}}""",
        )
        assertNull(store().load())
        storeRaw(
            """{"latest":{"versionCode":1,"versionName":"1","apkUrl":"http://example.com/a.apk","mandatory":false}}""",
        )
        assertNull(store().load())
        assertEquals(2, logged.size)
    }

    @Test
    fun unknownFailureReasonReturnsNull() {
        storeRaw(
            """{"rejected":{"info":{"versionCode":1,"versionName":"1","apkUrl":"https://example.com/a.apk",""" +
                """"mandatory":false},"reason":"FromTheFuture"}}""",
        )
        assertNull(store().load())
        assertEquals(1, logged.size)
    }

    @Test
    fun malformedReleaseNotesReturnNull() {
        val head = """{"latest":{"versionCode":1,"versionName":"1","apkUrl":"https://example.com/a.apk","mandatory":false,"""
        val tails = listOf(
            """"releaseNotes":[]}}""",
            """"releaseNotes":[["en"]]}}""",
            """"releaseNotes":[["en","a"],["EN","b"]]}}""",
            """"releaseNotes":[["","a"],["en","b"]]}}""",
            """"releaseNotes":"text"}}""",
        )
        tails.forEach {
            storeRaw(head + it)
            assertNull(it, store().load())
        }
        assertEquals(tails.size, logged.size)
    }

    @Test
    fun unknownFormatVersionReturnsNullAndLogs() {
        storeRaw("{}", version = 2)
        assertNull(store().load())
        assertEquals(1, logged.size)
        assertTrue(logged.single().contains("format version"))
    }

    @Test
    fun missingFormatVersionReturnsNull() {
        check(rawPreferences.edit().putString("snapshot", "{}").commit())
        assertNull(store().load())
        assertEquals(1, logged.size)
    }

    @Test
    fun saveAfterUnreadableDataReplacesIt() {
        storeRaw("garbage")
        val store = store()
        assertNull(store.load())
        store.save(Snapshot(etag = "fresh"))
        assertEquals(Snapshot(etag = "fresh"), store.load())
    }

    @Test
    fun worksWithoutALogger() {
        storeRaw("garbage")
        assertNull(PreferencesStore(context, null).load())
    }

    @Test
    fun systemClockReturnsWallClockTime() {
        val before = System.currentTimeMillis()
        val now = SystemClock.nowMillis()
        assertTrue(now in before..System.currentTimeMillis())
    }
}
