package io.lightine.renovo

/**
 * Why a check, download, or install failed.
 *
 * ```
 * when (val state = Renovo.state.value) {
 *     is UpdateState.Failed -> if (state.reason == FailureReason.Storage) askToFreeSpace()
 *     else -> Unit
 * }
 * ```
 */
public enum class FailureReason {
    /** No connection, or the server could not be reached. */
    Network,

    /** The version information could not be read. */
    Parse,

    /** The downloaded file does not match the SHA-256 hash in the version information. */
    HashMismatch,

    /** The downloaded file is from a different publisher or a different app. */
    SignatureMismatch,

    /** The downloaded file is not newer than the installed version. */
    NotNewer,

    /** Not enough free space, or the file could not be written or read. */
    Storage,

    /** The system rejected the install. */
    InstallRejected,

    /**
     * The user cancelled the install. A download the user cancels from its notification is not a
     * failure: the update goes back to available, and [Renovo.startDownload] starts it again.
     */
    Cancelled,
}
