package io.lightine.renovo

/**
 * The networks a download may use.
 *
 * Optional and forced updates each have their own setting.
 *
 * ```
 * Renovo.init(this) {
 *     versionUrl = "https://example.com/app/version.json"
 *     optionalDownloadNetwork = Network.UnmeteredOnly
 *     forcedDownloadNetwork = Network.Any
 * }
 * ```
 */
public enum class Network {
    /** Any connected network, including mobile data. */
    Any,

    /** Only networks that are not billed by usage, such as most Wi-Fi networks. */
    UnmeteredOnly,

    /** Any network except a mobile network while roaming. */
    NotRoaming,
}
