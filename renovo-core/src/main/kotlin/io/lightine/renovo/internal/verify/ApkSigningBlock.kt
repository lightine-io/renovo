package io.lightine.renovo.internal.verify

import java.io.File
import java.io.RandomAccessFile

/**
 * Finds the APK Signing Block of an APK file and returns its ID-value pairs.
 *
 * Layout from https://source.android.com/docs/security/features/apksigning/v2, "APK signing
 * block": the block sits immediately before the ZIP Central Directory, whose offset the ZIP End of
 * Central Directory (EOCD) record at the end of the file gives. All numbers are little-endian.
 */
internal object ApkSigningBlock {
    /**
     * The values of the block's ID-value pairs by ID, the first one for each ID, or null when the
     * file is a valid zip without an APK Signing Block (unsigned, or signed with JAR signing only).
     *
     * @throws ApkFormatException when the file is not a zip Renovo accepts.
     * @throws java.io.IOException when the file cannot be read.
     */
    fun read(file: File): Map<Int, ByteArray>? = RandomAccessFile(file, "r").use { apk ->
        val layout = readZipLayout(apk)
        val block = readBlock(apk, layout.centralDirectoryOffset) ?: return null
        readPairs(block)
    }

    private class ZipLayout(
        val centralDirectoryOffset: Long,
    )

    private fun readZipLayout(apk: RandomAccessFile): ZipLayout {
        val fileSize = apk.length()
        if (fileSize < EOCD_MIN_SIZE) {
            throw ApkFormatException("The file is too small to be a zip archive ($fileSize bytes).")
        }
        // The EOCD ends with a comment of up to 65535 bytes, so it starts somewhere in the last
        // 22 + 65535 bytes. Trying comment lengths from 0 upward, and requiring the record's own
        // comment-length field to agree, finds the record that ends exactly at the end of the file.
        // That also enforces the v2 rule "ZIP End of Central Directory is not followed by more data".
        val tailSize = minOf(fileSize, (EOCD_MIN_SIZE + MAX_COMMENT_SIZE).toLong()).toInt()
        val tail = ByteArray(tailSize)
        apk.seek(fileSize - tailSize)
        apk.readFully(tail)
        val maxCommentSize = tailSize - EOCD_MIN_SIZE
        var eocdInTail = -1
        for (commentSize in 0..maxCommentSize) {
            val candidate = tailSize - EOCD_MIN_SIZE - commentSize
            val reader = LittleEndianReader(tail, candidate)
            if (reader.int("EOCD signature") == EOCD_SIGNATURE &&
                readUInt16(tail, candidate + EOCD_COMMENT_LENGTH_OFFSET) == commentSize
            ) {
                eocdInTail = candidate
                break
            }
        }
        if (eocdInTail < 0) {
            throw ApkFormatException("The file is not a zip archive: no End of Central Directory record.")
        }
        val eocdOffset = fileSize - tailSize + eocdInTail

        // The platform rejects ZIP64 APKs too (ApkSigningBlockUtils.findSignature), and real APKs
        // never need it.
        if (eocdOffset >= ZIP64_LOCATOR_SIZE) {
            val locator = ByteArray(4)
            apk.seek(eocdOffset - ZIP64_LOCATOR_SIZE)
            apk.readFully(locator)
            if (LittleEndianReader(locator).int("ZIP64 locator signature") == ZIP64_LOCATOR_SIGNATURE) {
                throw ApkFormatException("ZIP64 archives are not supported.")
            }
        }

        val eocd = LittleEndianReader(tail, eocdInTail + EOCD_CENTRAL_DIRECTORY_SIZE_OFFSET)
        val centralDirectorySize = eocd.int("Central Directory size").toLong() and 0xffffffffL
        val centralDirectoryOffset = eocd.int("Central Directory offset").toLong() and 0xffffffffL
        // v2 verification rule: "ZIP Central Directory is immediately followed by ZIP End of
        // Central Directory record". Anything else means the offsets were tampered with.
        if (centralDirectoryOffset + centralDirectorySize != eocdOffset) {
            throw ApkFormatException(
                "The Central Directory ($centralDirectoryOffset + $centralDirectorySize) does not end where the " +
                    "End of Central Directory record starts ($eocdOffset).",
            )
        }
        return ZipLayout(centralDirectoryOffset)
    }

    /** The whole block, both size fields and the magic included, or null when there is none. */
    private fun readBlock(
        apk: RandomAccessFile,
        centralDirectoryOffset: Long,
    ): ByteArray? {
        // Block format: uint64 size (excluding this field), ID-value pairs, uint64 size (same
        // value), 16-byte magic "APK Sig Block 42".
        if (centralDirectoryOffset < BLOCK_MIN_SIZE) return null
        val footer = ByteArray(BLOCK_FOOTER_SIZE)
        apk.seek(centralDirectoryOffset - BLOCK_FOOTER_SIZE)
        apk.readFully(footer)
        if (!footer.copyOfRange(8, BLOCK_FOOTER_SIZE).contentEquals(BLOCK_MAGIC)) return null

        val sizeInFooter = LittleEndianReader(footer).long("APK Signing Block size")
        // The cap keeps a hostile size field from allocating gigabytes. Real blocks are a few
        // kilobytes, padded to a multiple of 4096 bytes.
        if (sizeInFooter < BLOCK_FOOTER_SIZE || sizeInFooter > MAX_BLOCK_SIZE - 8) {
            throw ApkFormatException("The APK Signing Block size is out of range: ${sizeInFooter.toULong()}.")
        }
        val totalSize = (sizeInFooter + 8).toInt()
        val blockOffset = centralDirectoryOffset - totalSize
        if (blockOffset < 0) {
            throw ApkFormatException("The APK Signing Block would start before the file: $blockOffset.")
        }
        val block = ByteArray(totalSize)
        apk.seek(blockOffset)
        apk.readFully(block)
        val sizeInHeader = LittleEndianReader(block).long("APK Signing Block size")
        // v2 verification rule: "Two size fields of APK signing block contain the same value."
        if (sizeInHeader != sizeInFooter) {
            throw ApkFormatException(
                "The two size fields of the APK Signing Block differ: ${sizeInHeader.toULong()} and " +
                    "${sizeInFooter.toULong()}.",
            )
        }
        return block
    }

    private fun readPairs(block: ByteArray): Map<Int, ByteArray> {
        // Each pair: uint64 length, then uint32 ID and (length - 4) bytes of value. Unknown IDs
        // are skipped, as the format page requires.
        val pairs = LittleEndianReader(block, 8, block.size - BLOCK_FOOTER_SIZE)
        val values = HashMap<Int, ByteArray>()
        while (pairs.hasRemaining()) {
            val length = pairs.long("APK Signing Block entry length")
            if (length < 4 || length > pairs.remaining) {
                throw ApkFormatException(
                    "An APK Signing Block entry length is out of range: ${length.toULong()}, " +
                        "${pairs.remaining} bytes left.",
                )
            }
            val entry = pairs.slice(length.toInt(), "APK Signing Block entry")
            val id = entry.int("APK Signing Block entry ID")
            // Like the platform, the first entry with an ID wins.
            if (id !in values) values[id] = entry.slice(entry.remaining, "APK Signing Block entry value").toByteArray()
        }
        return values
    }

    private fun readUInt16(
        bytes: ByteArray,
        offset: Int,
    ): Int = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private const val EOCD_SIGNATURE = 0x06054b50
    private const val EOCD_MIN_SIZE = 22
    private const val EOCD_CENTRAL_DIRECTORY_SIZE_OFFSET = 12
    private const val EOCD_COMMENT_LENGTH_OFFSET = 20
    private const val MAX_COMMENT_SIZE = 0xffff
    private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50
    private const val ZIP64_LOCATOR_SIZE = 20L
    private const val BLOCK_FOOTER_SIZE = 24
    private const val BLOCK_MIN_SIZE = 32L
    private const val MAX_BLOCK_SIZE = 16L * 1024 * 1024
    private val BLOCK_MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
}
