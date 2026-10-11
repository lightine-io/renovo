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
    /** No connection, the server could not be reached, or the server answered with an error. */
    Network,

    /** The version information could not be read, or it holds a value that is not allowed. */
    Parse,

    /**
     * The downloaded file is not the file the version information describes. Its SHA-256 hash or
     * its version code differs, or it is not a valid APK with an intact signature.
     */
    HashMismatch,

    /** The downloaded file is for a different app, or it is signed with a different key than the installed app. */
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
