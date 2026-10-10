package io.lightine.renovo

/**
 * Receives Renovo's diagnostic messages. Logging is off until the host app sets one.
 *
 * Messages never contain personal data.
 *
 * ```
 * Renovo.init(this) {
 *     versionUrl = "https://example.com/app/version.json"
 *     logger = RenovoLogger { message, error -> Log.d("Renovo", message, error) }
 * }
 * ```
 */
public fun interface RenovoLogger {
    /**
     * Logs one message, with the error that caused it when there is one.
     *
     * ```
     * val logger = RenovoLogger { message, error -> println("$message ${error ?: ""}") }
     * ```
     */
    public fun log(
        message: String,
        error: Throwable?,
    )
}
