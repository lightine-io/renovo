package io.lightine.renovo.internal.work

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException

/**
 * How many bytes the app can still write to its internal storage.
 *
 * One implementation per API range, because `StorageManager.getAllocatableBytes` exists only from
 * API 26.
 */
internal fun interface FreeSpace {
    fun availableBytes(
        context: Context,
        directory: File,
    ): Long

    companion object {
        /** The implementation for the device the process runs on. */
        fun forDevice(): FreeSpace = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) AllocatableFreeSpace else UsableFreeSpace
    }
}

/** Below API 26 the only number the platform offers is what is free on the partition. */
internal object UsableFreeSpace : FreeSpace {
    override fun availableBytes(
        context: Context,
        directory: File,
    ): Long = directory.usableSpace
}

/**
 * API 26 and up. The allocatable number is larger than the free space of the partition, because the
 * system can clear cached files of other apps to make room, and a download should be allowed to
 * use that room.
 */
@RequiresApi(Build.VERSION_CODES.O)
internal object AllocatableFreeSpace : FreeSpace {
    override fun availableBytes(
        context: Context,
        directory: File,
    ): Long {
        val manager = context.getSystemService(StorageManager::class.java) ?: return directory.usableSpace
        return try {
            manager.getAllocatableBytes(manager.getUuidForPath(directory))
        } catch (_: IOException) {
            // The volume could not be identified. The partition's free space is a safe lower bound.
            directory.usableSpace
        }
    }
}
