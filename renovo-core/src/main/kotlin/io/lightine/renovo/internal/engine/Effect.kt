package io.lightine.renovo.internal.engine

import io.lightine.renovo.Network
import io.lightine.renovo.UpdateInfo

/**
 * Work the engine asks the runtime to do. The engine never does I/O itself.
 *
 * The runtime runs effects in list order. [Persist] always comes first, so the new state is on
 * disk before any work starts that a killed process could interrupt.
 */
internal sealed interface Effect {
    /** Save [snapshot] with the [Store]. */
    data class Persist(
        val snapshot: Snapshot,
    ) : Effect

    /** Run a check now. The runtime reports [EngineEvent.CheckStarted] with the same flag. */
    data class StartCheck(
        val userInitiated: Boolean,
    ) : Effect

    /** Make sure the periodic background check is scheduled. Doing it again changes nothing. */
    data object ScheduleChecks : Effect

    /**
     * Download [info] on [network]. A download the user started while the app is in the
     * foreground runs as a foreground worker. Queuing the version that is already downloading
     * continues that download. It never starts a second one.
     */
    data class EnqueueDownload(
        val info: UpdateInfo,
        val network: Network,
        val userInitiated: Boolean,
    ) : Effect

    /** Stop the running download and delete its partial file. */
    data object CancelDownload : Effect

    /** Check the downloaded file against [info]. Report the result with the verification events. */
    data class Verify(
        val filePath: String,
        val info: UpdateInfo,
    ) : Effect

    data class DeleteFile(
        val filePath: String,
    ) : Effect

    /** Ask `canInstallNow`, check the permission, and hand the file to the system installer. */
    data class Install(
        val filePath: String,
        val info: UpdateInfo,
    ) : Effect

    /** Open the system screen where the user allows this app to install updates. */
    data object OpenInstallPermission : Effect

    /** Show the "update ready" notification if the app is in the background and notifications are allowed. */
    data class ShowReadyNotification(
        val info: UpdateInfo,
    ) : Effect

    data object CancelReadyNotification : Effect
}
