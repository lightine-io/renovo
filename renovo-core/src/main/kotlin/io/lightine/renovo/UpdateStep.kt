package io.lightine.renovo

// Equality: see the note on UpdateState. The same rules apply here.
/**
 * What a forced update is doing while the host app stays blocked.
 *
 * It is the `step` of [UpdateState.Required]. A headless host app reads it to build its own
 * blocking screen.
 *
 * ```
 * when (val step = (Renovo.state.value as? UpdateState.Required)?.step) {
 *     is UpdateStep.Downloading -> showProgress(step.progress)
 *     UpdateStep.WaitingForUser -> showUpdateButton()
 *     else -> Unit
 * }
 * ```
 */
public sealed interface UpdateStep {
    /**
     * Waiting for the user to start the update, by calling [Renovo.startDownload] or
     * [Renovo.installIfReady]. The file may already be downloaded and verified.
     *
     * ```
     * if (step == UpdateStep.WaitingForUser) Renovo.startDownload()
     * ```
     */
    public data object WaitingForUser : UpdateStep

    /**
     * The update file is being downloaded.
     *
     * ```
     * if (step is UpdateStep.Downloading) bar.progress = step.progress.bytesDownloaded
     * ```
     */
    public class Downloading(
        /** How much has been downloaded. */
        public val progress: DownloadProgress,
    ) : UpdateStep {
        override fun equals(other: Any?): Boolean = other is Downloading && other.progress == progress

        override fun hashCode(): Int = progress.hashCode()

        override fun toString(): String = "Downloading(progress=$progress)"
    }

    /**
     * The download waits for a network that the settings allow.
     *
     * ```
     * if (step is UpdateStep.WaitingForNetwork && step.canUseMobileData) showMobileDataButton()
     * ```
     */
    public class WaitingForNetwork(
        /** True when the user may download with mobile data now, by calling [Renovo.useMobileDataOnce]. */
        public val canUseMobileData: Boolean,
    ) : UpdateStep {
        override fun equals(other: Any?): Boolean = other is WaitingForNetwork && other.canUseMobileData == canUseMobileData

        override fun hashCode(): Int = canUseMobileData.hashCode()

        override fun toString(): String = "WaitingForNetwork(canUseMobileData=$canUseMobileData)"
    }

    /**
     * The user must allow this app to install updates. [Renovo.installIfReady] opens the setting.
     *
     * ```
     * if (step == UpdateStep.PermissionNeeded) Renovo.installIfReady()
     * ```
     */
    public data object PermissionNeeded : UpdateStep

    /**
     * The update is with the system installer.
     *
     * ```
     * if (step == UpdateStep.Installing) showSpinner()
     * ```
     */
    public data object Installing : UpdateStep

    /**
     * The last attempt failed. [Renovo.startDownload] tries again.
     *
     * ```
     * if (step is UpdateStep.Failed) showReason(step.reason)
     * ```
     */
    public class Failed(
        /** Why the attempt failed. */
        public val reason: FailureReason,
    ) : UpdateStep {
        override fun equals(other: Any?): Boolean = other is Failed && other.reason == reason

        override fun hashCode(): Int = reason.hashCode()

        override fun toString(): String = "Failed(reason=$reason)"
    }

    /**
     * This device does not allow the app to update itself. Nothing is tried until the installed
     * version changes.
     *
     * ```
     * if (step == UpdateStep.Unsupported) showContactAdminText()
     * ```
     */
    public data object Unsupported : UpdateStep
}
