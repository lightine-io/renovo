package io.lightine.renovo.internal

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException

/**
 * Whether this process is the host app's main process: the one whose name is the package name.
 *
 * `Application.onCreate` runs in every process of the app, such as a `:remote` service. Each would
 * build its own engine and write the same stored state, so only the main process runs Renovo.
 */
internal fun isMainProcess(context: Context): Boolean {
    // A name that cannot be read counts as the main process. Most apps have no other process, and
    // a Renovo that silently does nothing there would be worse than the rare second engine.
    val name = currentProcessName() ?: return true
    return name == context.packageName
}

private fun currentProcessName(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    Application.getProcessName()
} else {
    // No public API names the process below API 28. The kernel's command line of the process
    // starts with the name the system gave it.
    readProcessNameFromProc()
}

private fun readProcessNameFromProc(): String? = try {
    processNameFromCmdline(File("/proc/self/cmdline").readBytes())
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}

/** The first NUL-terminated token of a `/proc/<pid>/cmdline` file, or null when it is empty. */
internal fun processNameFromCmdline(bytes: ByteArray): String? {
    val end = bytes.indexOf(0.toByte()).let { if (it < 0) bytes.size else it }
    return String(bytes, 0, end, Charsets.UTF_8).trim().ifEmpty { null }
}
