package io.lightine.renovo.internal.verify

/**
 * Reads the little-endian, length-prefixed structures of the APK Signing Block from a byte array.
 *
 * A plain array and an offset instead of [java.nio.ByteBuffer]: newer SDKs declare covariant
 * `ByteBuffer.position(Int)` and `limit(Int)` overrides that older devices lack, so code compiled
 * against them can fail at run time on old Android versions.
 *
 * Every read checks the bounds and throws [ApkFormatException], so a truncated or hostile block
 * can never read outside its own region.
 */
internal class LittleEndianReader(
    private val bytes: ByteArray,
    private val start: Int = 0,
    private val end: Int = bytes.size,
) {
    private var position = start

    init {
        require(start in 0..end && end <= bytes.size) { "Region $start..$end outside ${bytes.size} bytes." }
    }

    val remaining: Int
        get() = end - position

    fun hasRemaining(): Boolean = position < end

    /** An unsigned 32-bit field as a Kotlin [Int]; values above [Int.MAX_VALUE] come out negative. */
    fun int(what: String): Int {
        need(4, what)
        val value = (bytes[position].toInt() and 0xff) or
            ((bytes[position + 1].toInt() and 0xff) shl 8) or
            ((bytes[position + 2].toInt() and 0xff) shl 16) or
            ((bytes[position + 3].toInt() and 0xff) shl 24)
        position += 4
        return value
    }

    fun long(what: String): Long {
        val low = int(what).toLong() and 0xffffffffL
        val high = int(what).toLong() and 0xffffffffL
        return low or (high shl 32)
    }

    /** A uint32 length followed by that many bytes, as a reader of its own. */
    fun lengthPrefixed(what: String): LittleEndianReader {
        val length = int("length of $what")
        if (length < 0 || length > remaining) {
            throw ApkFormatException("The length of $what is out of range: ${length.toUInt()}, $remaining bytes left.")
        }
        val slice = LittleEndianReader(bytes, position, position + length)
        position += length
        return slice
    }

    fun lengthPrefixedBytes(what: String): ByteArray = lengthPrefixed(what).toByteArray()

    /** A reader over the next [length] bytes. */
    fun slice(
        length: Int,
        what: String,
    ): LittleEndianReader {
        need(length, what)
        val slice = LittleEndianReader(bytes, position, position + length)
        position += length
        return slice
    }

    /** The whole region of this reader, independent of how much of it was read. */
    fun toByteArray(): ByteArray = bytes.copyOfRange(start, end)

    private fun need(
        count: Int,
        what: String,
    ) {
        if (count < 0 || count > remaining) {
            throw ApkFormatException("Not enough data for $what: needs $count bytes, $remaining left.")
        }
    }
}
