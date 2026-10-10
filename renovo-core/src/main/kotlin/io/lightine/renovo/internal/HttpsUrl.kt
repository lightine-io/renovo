package io.lightine.renovo.internal

import java.net.URI
import java.net.URISyntaxException

/**
 * True when [url] is an absolute https URL with a host.
 *
 * The scheme is checked on the parsed URI rather than by prefix, so a value such as
 * "https:relative" or "https:///path" without a host is rejected too.
 */
internal fun isHttpsUrl(url: String): Boolean {
    val uri =
        try {
            URI(url)
        } catch (_: URISyntaxException) {
            return false
        }
    return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty()
}
