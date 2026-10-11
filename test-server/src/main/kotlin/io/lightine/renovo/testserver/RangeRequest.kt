package io.lightine.renovo.testserver

/** What a `Range` request header asks for, measured against a file of a known length. */
internal sealed interface RangeRequest {
    /** No usable range: send the whole file with 200. */
    data object Whole : RangeRequest

    /** Send bytes [start] to [end], both included, with 206. */
    data class Part(
        val start: Long,
        val end: Long,
    ) : RangeRequest {
        val length: Long get() = end - start + 1
    }

    /** The range lies outside the file: answer 416. */
    data object Unsatisfiable : RangeRequest
}

private val SINGLE_RANGE = Regex("""bytes=(\d*)-(\d*)""")

/**
 * Reads `bytes=<start>-`, `bytes=<start>-<end>` and `bytes=-<count>`.
 *
 * Anything else, including several ranges at once, is ignored and the whole file is sent. RFC 9110
 * allows that, and the library's client never asks for more than one range.
 */
internal fun parseRange(
    header: String?,
    length: Long,
): RangeRequest {
    val match = header?.trim()?.let { SINGLE_RANGE.matchEntire(it) } ?: return RangeRequest.Whole
    val first = match.groupValues[1]
    val last = match.groupValues[2]
    return when {
        first.isEmpty() && last.isEmpty() -> RangeRequest.Whole

        // The last <count> bytes.
        first.isEmpty() -> {
            val count = last.toLongOrNull() ?: length
            if (count == 0L || length == 0L) RangeRequest.Unsatisfiable else RangeRequest.Part(maxOf(0L, length - count), length - 1)
        }

        else -> {
            // A start too large for a Long is also past the end of any file.
            val start = first.toLongOrNull() ?: return RangeRequest.Unsatisfiable
            val end = if (last.isEmpty()) length - 1 else minOf(last.toLongOrNull() ?: Long.MAX_VALUE, length - 1)
            when {
                // An end before the start is a malformed range, which RFC 9110 says to ignore.
                last.isNotEmpty() && (last.toLongOrNull() ?: Long.MAX_VALUE) < start -> RangeRequest.Whole

                start >= length -> RangeRequest.Unsatisfiable

                else -> RangeRequest.Part(start, end)
            }
        }
    }
}
