package io.lightine.renovo.internal.engine

/** The final outcome the system installer reported for a session. */
internal enum class InstallResult {
    /** The new version is installed. */
    Success,

    /** The system refused the APK, for example a failed developer verification or an incompatible file. */
    Rejected,

    /** The user cancelled the system confirmation. */
    Cancelled,

    /** A policy or restriction forbids the install on this device. Ends in Unsupported. */
    Blocked,
}
