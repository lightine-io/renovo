package io.lightine.renovo.internal

import io.lightine.renovo.UpdateInfo
import java.io.File

/**
 * Hands a verified APK to the system installer. One implementation per API range (24-25, 26-30,
 * 31-33, 34 and up), chosen once at wiring time.
 *
 * The implementing task must guarantee:
 * - [install] is only called from the foreground, after `canInstallNow` returned true and the
 *   [PermissionGate] reported the permission granted. A call while a session is open does nothing.
 * - It uses a `PackageInstaller` session, requests "user action not required" where the API allows
 *   it, and launches the system confirmation on "pending user action".
 * - It reports through the engine's event sink: `InstallStarted` once the session is committed,
 *   then exactly one `InstallFinished` with Success, Rejected (including developer verification
 *   failures, whose reason is logged), Cancelled (the user declined), or Blocked (policy, a user
 *   restriction, or a `SecurityException` from the session).
 * - The status callback uses an explicit intent in a mutable `PendingIntent` on API 31 and up.
 */
internal interface Installer {
    fun install(
        file: File,
        info: UpdateInfo,
    )
}
