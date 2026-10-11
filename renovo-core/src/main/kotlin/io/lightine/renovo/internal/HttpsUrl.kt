package io.lightine.renovo.internal

import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

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

/**
 * Parses [spec] as an absolute URL, or as a redirect target relative to [base] when one is given.
 * Returns null when the text is blank or is not a valid URL.
 *
 * The text goes through [URI] and not through a [URL] constructor. Java 20 deprecated those
 * constructors because they accept text that is not a valid address, so a target with a raw space
 * or another illegal character is refused here instead of being sent on.
 */
internal fun parseUrl(
    spec: String,
    base: URL? = null,
): URL? {
    val text = spec.trim()
    if (text.isEmpty()) return null
    return try {
        val uri = if (base == null) URI(text) else base.toURI().resolve(text)
        uri.toURL()
    } catch (_: URISyntaxException) {
        null
    } catch (_: IllegalArgumentException) {
        // resolve() reports a syntax error this way, and toURL() a URI that is not absolute.
        null
    } catch (_: MalformedURLException) {
        // A scheme the platform has no handler for.
        null
    }
}
