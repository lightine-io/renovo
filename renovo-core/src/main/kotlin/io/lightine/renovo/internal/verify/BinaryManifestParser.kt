package io.lightine.renovo.internal.verify

/**
 * The attributes of an APK's `<manifest>` element that the verifier needs.
 *
 * @property versionCode The `android:versionCode` attribute, the lower 32 bits.
 * @property versionCodeMajor The `android:versionCodeMajor` attribute, 0 when absent.
 */
internal class ApkManifest(
    val packageName: String,
    val versionCode: Int,
    val versionCodeMajor: Int,
) {
    /**
     * The combined version code the system compares, as `PackageInfo.getLongVersionCode()`
     * defines it: versionCodeMajor in the upper 32 bits, versionCode as unsigned lower 32 bits.
     */
    val longVersionCode: Long
        get() = (versionCodeMajor.toLong() shl 32) or (versionCode.toLong() and 0xffffffffL)
}

/**
 * Reads the root `<manifest>` element of a compiled (binary XML) AndroidManifest.xml.
 *
 * The format has no page of its own on source.android.com. The layout is the one of the
 * platform's resource headers, frameworks/base libs/androidfw/include/androidfw/ResourceTypes.h
 * (ResChunk_header, ResStringPool_header, ResXMLTree_node, ResXMLTree_attrExt,
 * ResXMLTree_attribute, Res_value). All numbers are little-endian.
 *
 * The parser stops at the first element, so it only needs the string pool, the resource map,
 * and namespace chunks before it. Any other chunk there, a root element other than `manifest`, or
 * a value of an unexpected type throws [ApkFormatException]: a manifest that surprises this parser
 * is not one Renovo should vouch for.
 */
internal class BinaryManifestParser(
    private val data: ByteArray,
) {
    private var strings: StringPool? = null
    private var resourceIds = IntArray(0)

    fun parse(): ApkManifest {
        val fileType = u16(0)
        val fileHeaderSize = u16(2)
        val fileSize = u32(4)
        if (fileType != RES_XML_TYPE || fileHeaderSize < CHUNK_HEADER_SIZE || fileSize.toLong() != data.size.toLong()) {
            throw ApkFormatException("AndroidManifest.xml is not a compiled binary XML file.")
        }
        var offset = fileHeaderSize
        while (offset < data.size) {
            val type = u16(offset)
            val headerSize = u16(offset + 2)
            val size = u32(offset + 4)
            if (headerSize < CHUNK_HEADER_SIZE || size < headerSize || size > data.size - offset) {
                throw ApkFormatException("AndroidManifest.xml has a chunk with a broken size at offset $offset.")
            }
            when (type) {
                RES_STRING_POOL_TYPE -> {
                    if (strings != null) throw ApkFormatException("AndroidManifest.xml has two string pools.")
                    strings = StringPool(offset, headerSize, size)
                }

                RES_XML_RESOURCE_MAP_TYPE -> {
                    resourceIds = IntArray((size - headerSize) / 4) { u32(offset + headerSize + it * 4) }
                }

                RES_XML_START_NAMESPACE_TYPE -> Unit

                RES_XML_START_ELEMENT_TYPE -> return readManifestElement(offset, headerSize, size)

                else -> throw ApkFormatException(
                    "AndroidManifest.xml has an unexpected chunk 0x${type.toString(16)} before its root element.",
                )
            }
            offset += size
        }
        throw ApkFormatException("AndroidManifest.xml has no root element.")
    }

    private fun readManifestElement(
        offset: Int,
        headerSize: Int,
        size: Int,
    ): ApkManifest {
        val pool = strings ?: throw ApkFormatException("AndroidManifest.xml has no string pool.")
        // ResXMLTree_attrExt follows the node header: ns, name (string references),
        // attributeStart, attributeSize, attributeCount (uint16), then id, class, style indexes.
        val ext = offset + headerSize
        val end = offset + size
        if (ext + ATTR_EXT_SIZE > end) throw ApkFormatException("The root element of AndroidManifest.xml is truncated.")
        val elementNamespace = u32(ext)
        val elementName = pool.get(u32(ext + 4))
        if (elementNamespace != NO_REFERENCE || elementName != "manifest") {
            throw ApkFormatException("The root element of AndroidManifest.xml is not <manifest>.")
        }
        val attributeStart = u16(ext + 8)
        val attributeSize = u16(ext + 10)
        val attributeCount = u16(ext + 12)
        if (attributeSize < ATTRIBUTE_SIZE || ext + attributeStart + attributeSize.toLong() * attributeCount > end) {
            throw ApkFormatException("The attributes of <manifest> do not fit in their element.")
        }

        var packageName: String? = null
        var versionCode: Int? = null
        var versionCodeMajor: Int? = null
        for (index in 0 until attributeCount) {
            // ResXMLTree_attribute: ns, name, rawValue (string references), then Res_value: size
            // (uint16), res0 (uint8), dataType (uint8), data (uint32).
            val attribute = ext + attributeStart + index * attributeSize
            val namespace = u32(attribute)
            val nameIndex = u32(attribute + 4)
            val dataType = byte(attribute + 15)
            val value = u32(attribute + 16)
            // The platform reads android: attributes by resource ID (TypedArray), and the plain
            // package attribute by name without namespace (PackageParser), so this does too.
            val resourceId = if (nameIndex in resourceIds.indices) resourceIds[nameIndex] else 0
            when {
                resourceId == VERSION_CODE_ID -> {
                    versionCode = unique(versionCode, "versionCode") { integer(dataType, value, "versionCode") }
                }

                resourceId == VERSION_CODE_MAJOR_ID -> {
                    val major = integer(dataType, value, "versionCodeMajor")
                    versionCodeMajor = unique(versionCodeMajor, "versionCodeMajor") { major }
                }

                resourceId == 0 && namespace == NO_REFERENCE && pool.get(nameIndex) == "package" -> {
                    if (dataType != TYPE_STRING) throw ApkFormatException("The package attribute is not a string.")
                    packageName = unique(packageName, "package") { pool.get(value) }
                }
            }
        }
        val name = packageName
        if (name.isNullOrEmpty()) throw ApkFormatException("AndroidManifest.xml has no package name.")
        // A missing versionCode is 0 on the platform (PackageParser reads it with default 0).
        return ApkManifest(name, versionCode ?: 0, versionCodeMajor ?: 0)
    }

    private inline fun <T : Any> unique(
        current: T?,
        what: String,
        read: () -> T,
    ): T {
        if (current != null) throw ApkFormatException("<manifest> has the $what attribute twice.")
        return read()
    }

    private fun integer(
        dataType: Int,
        value: Int,
        what: String,
    ): Int {
        // aapt2 resolves a literal; a reference to an integer resource is not something the
        // installer resolves for these attributes either.
        if (dataType != TYPE_INT_DEC && dataType != TYPE_INT_HEX) {
            throw ApkFormatException("The $what attribute is not an integer (type 0x${dataType.toString(16)}).")
        }
        return value
    }

    /** A ResStringPool chunk. Strings are decoded only when asked for. */
    private inner class StringPool(
        private val offset: Int,
        private val headerSize: Int,
        private val size: Int,
    ) {
        // ResStringPool_header after the chunk header: stringCount, styleCount, flags,
        // stringsStart, stylesStart (uint32 each).
        private val count: Int
        private val utf8: Boolean
        private val stringsStart: Int

        init {
            if (headerSize < STRING_POOL_HEADER_SIZE) throw ApkFormatException("The string pool header is too short.")
            count = u32(offset + 8)
            utf8 = u32(offset + 16) and UTF8_FLAG != 0
            stringsStart = u32(offset + 20)
            if (count < 0 || headerSize + count.toLong() * 4 > size || stringsStart < 0 || stringsStart > size) {
                throw ApkFormatException("The string pool sizes are out of range.")
            }
        }

        fun get(index: Int): String {
            if (index !in 0 until count) throw ApkFormatException("A string reference is out of range: $index.")
            val stringOffset = u32(offset + headerSize + index * 4)
            val start = offset.toLong() + stringsStart + stringOffset
            if (stringOffset < 0 || start >= offset.toLong() + size) {
                throw ApkFormatException("String $index starts outside the string pool.")
            }
            return if (utf8) utf8String(start.toInt()) else utf16String(start.toInt())
        }

        private fun utf8String(start: Int): String {
            // Two lengths, each one byte, or two with the high bit set: UTF-16 units, then bytes.
            var position = start
            val charsFirst = byte(position++)
            if (charsFirst and 0x80 != 0) position++
            val first = byte(position++)
            val length = if (first and 0x80 != 0) ((first and 0x7f) shl 8) or byte(position++) else first
            checkInPool(position, length)
            return String(data, position, length, Charsets.UTF_8)
        }

        private fun utf16String(start: Int): String {
            // Length in UTF-16 units, one uint16, or two with the high bit set in the first.
            var position = start
            val first = u16(position)
            position += 2
            val length =
                if (first and 0x8000 != 0) {
                    val second = u16(position)
                    position += 2
                    ((first and 0x7fff) shl 16) or second
                } else {
                    first
                }
            checkInPool(position, length * 2)
            return String(data, position, length * 2, Charsets.UTF_16LE)
        }

        private fun checkInPool(
            position: Int,
            length: Int,
        ) {
            if (length < 0 || position.toLong() + length > offset.toLong() + size) {
                throw ApkFormatException("A string runs past the end of the string pool.")
            }
        }
    }

    private fun byte(offset: Int): Int {
        if (offset !in data.indices) throw ApkFormatException("AndroidManifest.xml is truncated.")
        return data[offset].toInt() and 0xff
    }

    private fun u16(offset: Int): Int = byte(offset) or (byte(offset + 1) shl 8)

    /** A uint32 field; a value above [Int.MAX_VALUE] comes out negative and fails range checks. */
    private fun u32(offset: Int): Int = u16(offset) or (u16(offset + 2) shl 16)

    private companion object {
        const val CHUNK_HEADER_SIZE = 8
        const val STRING_POOL_HEADER_SIZE = 28
        const val ATTR_EXT_SIZE = 20
        const val ATTRIBUTE_SIZE = 20
        const val RES_STRING_POOL_TYPE = 0x0001
        const val RES_XML_TYPE = 0x0003
        const val RES_XML_START_NAMESPACE_TYPE = 0x0100
        const val RES_XML_START_ELEMENT_TYPE = 0x0102
        const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
        const val UTF8_FLAG = 1 shl 8
        const val NO_REFERENCE = -1
        const val TYPE_STRING = 0x03
        const val TYPE_INT_DEC = 0x10
        const val TYPE_INT_HEX = 0x11

        // android.R.attr.versionCode and android.R.attr.versionCodeMajor.
        const val VERSION_CODE_ID = 0x0101021b
        const val VERSION_CODE_MAJOR_ID = 0x01010576
    }
}
