package io.lightine.renovo.internal

/**
 * Knows whether this app may install updates, and opens the system screen that allows it. One
 * implementation per API range (24-25, 26 and up).
 *
 * An implementation guarantees:
 * - [status] is read again on every call, because the user can take the permission back at any time.
 * - [status] is [InstallPermission.Unsupported] when a user restriction forbids installs from
 *   unknown sources, or when `REQUEST_INSTALL_PACKAGES` is missing from the merged manifest.
 *   The missing permission is logged as a programming error.
 * - [openSettings] starts the right settings screen from the resumed activity. It returns false
 *   when no such screen exists, and the runtime then reports an unsupported device.
 */
internal interface PermissionGate {
    fun status(): InstallPermission

    fun openSettings(): Boolean
}

/** Whether this app may install updates. */
internal enum class InstallPermission {
    Granted,

    /** The user can allow it in the system settings. */
    NeedsUser,

    /** The device offers no way to allow it. */
    Unsupported,
}
