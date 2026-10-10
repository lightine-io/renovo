package io.lightine.renovo.internal.work

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * The files of downloads in `filesDir/renovo`.
 *
 * - `<versionCode>.apk.part` is the partial file, appended to across runs.
 * - `<versionCode>.apk.part.validator` holds the ETag or Last-Modified of the bytes in the partial
 *   file. It is sent as `If-Range`, so a file that changed on the server is not glued together from
 *   two versions.
 * - `<versionCode>.apk` is the complete file.
 *
 * Only files with these names are ever touched, because other components keep their own files in
 * the same folder.
 */
internal class DownloadFiles(
    context: Context,
) {
    val directory = File(context.filesDir, "renovo")

    fun apk(versionCode: Long): File = File(directory, "$versionCode.apk")

    fun part(versionCode: Long): File = File(directory, "$versionCode.apk.part")

    private fun validator(versionCode: Long): File = File(directory, "$versionCode.apk.part.validator")

    fun ensureDirectory(): Boolean = directory.isDirectory || directory.mkdirs() || directory.isDirectory

    /** The `If-Range` value saved with the partial file, or null when there is none. */
    fun readValidator(versionCode: Long): String? {
        val file = validator(versionCode)
        if (!file.isFile) return null
        return try {
            file.readText().trim().ifEmpty { null }
        } catch (_: IOException) {
            null
        }
    }

    /** Saves [value], or removes the saved one when [value] is null. */
    fun writeValidator(
        versionCode: Long,
        value: String?,
    ) {
        val file = validator(versionCode)
        if (value == null) {
            file.delete()
        } else {
            file.writeText(value)
        }
    }

    fun deletePartial(versionCode: Long) {
        part(versionCode).delete()
        validator(versionCode).delete()
    }

    /** Deletes the partial files of every version. */
    fun deleteAllPartials() {
        for ((file, _) in downloadFiles()) {
            if (file.name.endsWith(PART_SUFFIX) || file.name.endsWith(VALIDATOR_SUFFIX)) file.delete()
        }
    }

    /** Deletes every download file whose version code is not [keep]. */
    fun deleteOtherVersions(keep: Long) {
        for ((file, versionCode) in downloadFiles()) {
            if (versionCode != keep) file.delete()
        }
    }

    /**
     * Turns the partial file of [versionCode] into the complete file and returns it.
     *
     * @throws StorageException when the file cannot be moved into place.
     */
    fun complete(versionCode: Long): File {
        val target = apk(versionCode)
        val source = part(versionCode)
        // renameTo does not replace an existing file on every filesystem.
        if (target.exists()) target.delete()
        if (!source.renameTo(target)) throw StorageException("The finished download could not be moved into place.")
        validator(versionCode).delete()
        return target
    }

    private fun downloadFiles(): List<Pair<File, Long>> {
        val all = directory.listFiles() ?: return emptyList()
        return all.mapNotNull { file ->
            val match = NAME.matchEntire(file.name) ?: return@mapNotNull null
            val versionCode = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            file to versionCode
        }
    }

    private companion object {
        const val PART_SUFFIX = ".apk.part"
        const val VALIDATOR_SUFFIX = ".apk.part.validator"
        val NAME = Regex("""(\d+)\.apk(?:\.part(?:\.validator)?)?""")
    }
}

/** The file system refused a write, or there is no room: the download cannot go on, and trying again will not help. */
internal class StorageException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** The server or a redirect sends the download somewhere it must not go. Trying again changes nothing. */
internal class RejectedDownloadException(
    message: String,
) : IOException(message)
