package io.lightine.renovo.internal.version

import io.lightine.renovo.ReleaseNotes
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.internal.isHttpsUrl
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.floor

/**
 * Thrown when a version file cannot be turned into an [UpdateInfo].
 *
 * The message is plain text for the log. It never repeats the document or a URL from it, because
 * the document may be large, and a URL may carry a token.
 */
internal class VersionFileException(
    message: String,
) : Exception(message)

/** Reads the version file. The README describes its format. */
internal object VersionFileParser {
    /** Parses [json] or throws [VersionFileException]. Unknown fields are ignored. */
    fun parse(json: String): UpdateInfo {
        val root = readObject(json)
        val apkUrl = requiredString(root, "apkUrl")
        // Checked here, not left to the UpdateInfo constructor, because its message repeats the URL.
        if (!isHttpsUrl(apkUrl)) throw VersionFileException("apkUrl must be an https URL with a host.")
        try {
            return UpdateInfo(
                versionCode = requiredLong(root, "versionCode"),
                versionName = requiredString(root, "versionName"),
                apkUrl = apkUrl,
                sha256 = optionalString(root, "sha256"),
                size = optionalLong(root, "size"),
                mandatory = optionalBoolean(root, "mandatory") ?: false,
                minSupportedVersionCode = optionalLong(root, "minSupportedVersionCode"),
                releaseNotes = releaseNotes(root),
            )
        } catch (e: IllegalArgumentException) {
            throw VersionFileException("A value in the version file is not allowed: ${e.message}")
        }
    }

    private fun readObject(json: String): JSONObject = try {
        JSONObject(json)
    } catch (_: JSONException) {
        // org.json copies the whole input into its message, so it is not passed on.
        throw VersionFileException("The version file is not a valid JSON object.")
    }

    // org.json treats a missing key and an explicit null alike, and so does this parser.
    private fun value(
        root: JSONObject,
        name: String,
    ): Any? = if (root.isNull(name)) null else root.opt(name)

    private fun requiredString(
        root: JSONObject,
        name: String,
    ): String = optionalString(root, name) ?: throw VersionFileException("$name is missing.")

    // Only a JSON string counts. org.json's own getString would turn 1.8 into "1.8", and a
    // version name that was never a string is a sign of a broken file.
    private fun optionalString(
        root: JSONObject,
        name: String,
    ): String? = when (val v = value(root, name)) {
        null -> null
        is String -> v
        else -> throw VersionFileException("$name must be a string.")
    }

    private fun requiredLong(
        root: JSONObject,
        name: String,
    ): Long = optionalLong(root, name) ?: throw VersionFileException("$name is missing.")

    // org.json's getLong would cut 42.5 to 42 and read a long string through a double, which
    // loses digits above 2^53. Both are decided here instead.
    private fun optionalLong(
        root: JSONObject,
        name: String,
    ): Long? = when (val v = value(root, name)) {
        null -> null
        is Int, is Long, is Short, is Byte -> (v as Number).toLong()
        is Number -> wholeNumber(v.toDouble()) ?: throw VersionFileException("$name must be a whole number.")
        is String -> v.trim().toLongOrNull() ?: throw VersionFileException("$name must be a whole number.")
        else -> throw VersionFileException("$name must be a whole number.")
    }

    private fun wholeNumber(d: Double): Long? {
        // The upper bound is exclusive: 2^63 is the first double that does not fit in a Long.
        val fits = d >= -TWO_POW_63 && d < TWO_POW_63
        return if (fits && d == floor(d)) d.toLong() else null
    }

    private fun optionalBoolean(
        root: JSONObject,
        name: String,
    ): Boolean? = when (val v = value(root, name)) {
        null -> null
        is Boolean -> v
        else -> throw VersionFileException("$name must be true or false.")
    }

    private fun releaseNotes(root: JSONObject): ReleaseNotes? = when (val v = value(root, "releaseNotes")) {
        null -> null
        is String -> ReleaseNotes(v)
        is JSONObject -> perLanguage(v)
        else -> throw VersionFileException("releaseNotes must be a string or an object of strings.")
    }

    private fun perLanguage(notes: JSONObject): ReleaseNotes? {
        // An empty object means "no notes". Failing the whole update for it would be harsher
        // than the mistake deserves.
        if (notes.length() == 0) return null
        val entries =
            notes.keys().asSequence().map { language ->
                val text =
                    notes.opt(language) as? String
                        ?: throw VersionFileException("Each releaseNotes entry must be a string.")
                language to text
            }.toList()
        return ReleaseNotes.of(*entries.toTypedArray())
    }

    private const val TWO_POW_63 = 9.223372036854775807E18
}
