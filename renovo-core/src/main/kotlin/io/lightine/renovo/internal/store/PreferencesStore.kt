package io.lightine.renovo.internal.store

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.lightine.renovo.FailureReason
import io.lightine.renovo.ReleaseNotes
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.internal.engine.DownloadRecord
import io.lightine.renovo.internal.engine.Rejection
import io.lightine.renovo.internal.engine.Snapshot
import io.lightine.renovo.internal.engine.Store
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Keeps the [Snapshot] as one JSON string in a private SharedPreferences file.
 *
 * One string under one key means a snapshot is replaced as a whole. The format version lives in
 * the same file and is written in the same commit, so the two can never disagree.
 *
 * The stored JSON looks like this (a field that is null is left out):
 * ```
 * {"etag":"...","latest":{...info...},"download":{"info":{...},"filePath":"...","verified":true},
 *  "mobileDataVersionCode":42,"rejected":{"info":{...},"reason":"HashMismatch"},
 *  "unsupportedAtVersionCode":41,"lastInstalledVersionCode":40}
 * ```
 * where `{...info...}` holds every [UpdateInfo] field and `releaseNotes` is an array of
 * `[language, text]` pairs.
 */
internal class PreferencesStore(
    context: Context,
    private val logger: RenovoLogger?,
) : Store {
    // The application context cannot leak an activity and stays valid for the process lifetime.
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun load(): Snapshot? {
        val text = preferences.getString(KEY_SNAPSHOT, null) ?: return null
        val version = preferences.getInt(KEY_FORMAT_VERSION, 0)
        if (version != FORMAT_VERSION) {
            logger?.log("Stored update state has an unknown format version ($version) and was ignored.", null)
            return null
        }
        return try {
            snapshotFromJson(JSONObject(text))
        } catch (_: JSONException) {
            logUnreadable()
            null
        } catch (_: IllegalArgumentException) {
            // UpdateInfo and ReleaseNotes validate their values; an unknown FailureReason ends up here too.
            logUnreadable()
            null
        }
    }

    override fun save(snapshot: Snapshot) {
        // commit() blocks until the file is on disk. apply() would return first, and a process
        // killed right after could lose the snapshot, which the Store contract forbids.
        val saved = preferences.edit()
            .putString(KEY_SNAPSHOT, snapshotToJson(snapshot).toString())
            .putInt(KEY_FORMAT_VERSION, FORMAT_VERSION)
            .commit()
        if (!saved) logger?.log("Saving the update state failed.", null)
    }

    override fun clear() {
        preferences.edit(commit = true) {
            remove(KEY_SNAPSHOT)
            remove(KEY_FORMAT_VERSION)
        }
    }

    // The message names no content: the stored text holds file paths and URLs.
    private fun logUnreadable() {
        logger?.log("Stored update state could not be read and was ignored.", null)
    }

    private companion object {
        const val FILE_NAME = "renovo"
        const val KEY_SNAPSHOT = "snapshot"
        const val KEY_FORMAT_VERSION = "format_version"
        const val FORMAT_VERSION = 1
    }
}

private fun snapshotToJson(snapshot: Snapshot): JSONObject = JSONObject().apply {
    snapshot.etag?.let { put("etag", it) }
    snapshot.latest?.let { put("latest", infoToJson(it)) }
    snapshot.download?.let { put("download", downloadToJson(it)) }
    snapshot.mobileDataVersionCode?.let { put("mobileDataVersionCode", it) }
    snapshot.rejected?.let { put("rejected", rejectionToJson(it)) }
    snapshot.unsupportedAtVersionCode?.let { put("unsupportedAtVersionCode", it) }
    snapshot.lastInstalledVersionCode?.let { put("lastInstalledVersionCode", it) }
}

private fun downloadToJson(record: DownloadRecord): JSONObject = JSONObject().apply {
    put("info", infoToJson(record.info))
    record.filePath?.let { put("filePath", it) }
    put("verified", record.verified)
}

private fun rejectionToJson(rejection: Rejection): JSONObject = JSONObject().apply {
    put("info", infoToJson(rejection.info))
    // The name, not the ordinal: reordering the enum must not change what an old file means.
    put("reason", rejection.reason.name)
}

private fun infoToJson(info: UpdateInfo): JSONObject = JSONObject().apply {
    put("versionCode", info.versionCode)
    put("versionName", info.versionName)
    put("apkUrl", info.apkUrl)
    info.sha256?.let { put("sha256", it) }
    info.size?.let { put("size", it) }
    put("mandatory", info.mandatory)
    info.minSupportedVersionCode?.let { put("minSupportedVersionCode", it) }
    info.releaseNotes?.let { put("releaseNotes", notesToJson(it)) }
}

// An array of pairs, not an object: JSON objects do not promise key order on every implementation,
// and the order decides the fallback language.
private fun notesToJson(notes: ReleaseNotes): JSONArray = JSONArray().apply {
    notes.entries.forEach { (language, text) -> put(JSONArray().put(language).put(text)) }
}

private fun snapshotFromJson(json: JSONObject): Snapshot = Snapshot(
    etag = json.optionalString("etag"),
    latest = json.optionalObject("latest")?.let(::infoFromJson),
    download = json.optionalObject("download")?.let(::downloadFromJson),
    mobileDataVersionCode = json.optionalLong("mobileDataVersionCode"),
    rejected = json.optionalObject("rejected")?.let(::rejectionFromJson),
    unsupportedAtVersionCode = json.optionalLong("unsupportedAtVersionCode"),
    lastInstalledVersionCode = json.optionalLong("lastInstalledVersionCode"),
)

private fun downloadFromJson(json: JSONObject): DownloadRecord = DownloadRecord(
    info = infoFromJson(json.getJSONObject("info")),
    filePath = json.optionalString("filePath"),
    verified = json.getBoolean("verified"),
)

private fun rejectionFromJson(json: JSONObject): Rejection = Rejection(
    info = infoFromJson(json.getJSONObject("info")),
    reason = FailureReason.valueOf(json.getString("reason")),
)

private fun infoFromJson(json: JSONObject): UpdateInfo = UpdateInfo(
    versionCode = json.getLong("versionCode"),
    versionName = json.getString("versionName"),
    apkUrl = json.getString("apkUrl"),
    sha256 = json.optionalString("sha256"),
    size = json.optionalLong("size"),
    mandatory = json.getBoolean("mandatory"),
    minSupportedVersionCode = json.optionalLong("minSupportedVersionCode"),
    releaseNotes = json.optionalArray("releaseNotes")?.let(::notesFromJson),
)

private fun notesFromJson(array: JSONArray): ReleaseNotes {
    val entries = List(array.length()) { index ->
        val pair = array.getJSONArray(index)
        if (pair.length() != 2) throw JSONException("A release notes entry needs a language and a text.")
        pair.getString(0) to pair.getString(1)
    }
    val single = entries.singleOrNull()
    return if (single != null && single.first.isEmpty()) {
        ReleaseNotes(single.second)
    } else {
        // `of` rejects an empty list, a blank language, and a repeated language: stored data that
        // breaks those rules is unreadable, not repaired.
        ReleaseNotes.of(*entries.toTypedArray())
    }
}

// JSONObject.getString turns a number or a boolean into text; the typed check keeps wrong types
// from being read as valid data.
private fun JSONObject.optionalString(key: String): String? = if (isNull(key)) null else get(key) as? String ?: throw JSONException("$key is not text.")

private fun JSONObject.optionalLong(key: String): Long? = if (isNull(key)) null else getLong(key)

private fun JSONObject.optionalObject(key: String): JSONObject? = if (isNull(key)) null else getJSONObject(key)

private fun JSONObject.optionalArray(key: String): JSONArray? = if (isNull(key)) null else getJSONArray(key)
