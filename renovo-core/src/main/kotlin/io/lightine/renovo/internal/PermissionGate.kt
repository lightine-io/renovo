package io.lightine.renovo.internal

/**
 * Knows whether this app may install updates, and opens the system screen that allows it. One
 * implementation per API range (24-25, 26 and up).
 *
 * The implementing task must guarantee:
 * - [status] is read fresh on every call, because the user can revoke the permission at any time.
 * - [status] is [InstallPermission.Unsupported] when a user restriction forbids installs from
 *   unknown sources, or when `REQUEST_INSTALL_PACKAGES` is missing from the merged manifest
 *   (logged as a programming error).
 * - [openSettings] starts the right settings screen from the resumed activity and returns false
 *   when no such screen exists; the runtime then reports an unsupported device.
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
