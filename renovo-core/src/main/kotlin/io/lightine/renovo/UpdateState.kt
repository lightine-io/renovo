package io.lightine.renovo

// Equality: states without data are data objects. States with data are plain classes with
// hand-written equals, hashCode, and toString instead of data classes, because a data class puts
// copy() and componentN() into the public binary API, and adding a property later would break
// every host app compiled against them. Value equality keeps StateFlow from emitting a state equal
// to the current one, and lets tests compare states directly.
/**
 * Where the update stands. [Renovo.state] always holds one of these.
 *
 * A forced update (one the user cannot postpone) is always [Required], whatever it is doing; its
 * [UpdateStep] says what. The other states describe an optional update.
 *
 * ```
 * Renovo.state.collect { state ->
 *     when (state) {
 *         is UpdateState.Available -> offerUpdate(state.info)
 *         is UpdateState.ReadyToInstall -> offerInstall(state.info)
 *         is UpdateState.Required -> blockApp(state.info, state.step)
 *         else -> Unit
 *     }
 * }
 * ```
 */
public sealed interface UpdateState {
    /**
     * Nothing is known yet: no check has finished since the app started.
     *
     * ```
     * if (Renovo.state.value == UpdateState.Idle) Renovo.checkNow()
     * ```
     */
    public data object Idle : UpdateState

    /**
     * A check is running and no update is known yet.
     *
     * ```
     * val busy = Renovo.state.value == UpdateState.Checking
     * ```
     */
    public data object Checking : UpdateState

    /**
     * The installed version is the latest.
     *
     * ```
     * if (Renovo.state.value == UpdateState.UpToDate) showMessage("You have the latest version")
     * ```
     */
    public data object UpToDate : UpdateState

    /**
     * A newer optional version exists and waits for [Renovo.startDownload].
     *
     * ```
     * if (state is UpdateState.Available) showDialog(state.info.versionName)
     * ```
     */
    public class Available(
        /** The newer version. */
        public val info: UpdateInfo,
    ) : UpdateState {
        override fun equals(other: Any?): Boolean = other is Available && other.info == info

        override fun hashCode(): Int = info.hashCode()

        override fun toString(): String = "Available(info=$info)"
    }

    /**
     * The update file is being downloaded.
     *
     * The update UI shows the download only when [userInitiated] is true; a download started in
     * the background runs silently.
     *
     * ```
     * if (state is UpdateState.Downloading && state.userInitiated) showProgress(state.progress)
     * ```
     */
    public class Downloading(
        /** The version being downloaded. */
        public val info: UpdateInfo,
        /** How much has been downloaded. */
        public val progress: DownloadProgress,
        /** True when the user started this download, so its progress should be shown. */
        public val userInitiated: Boolean,
    ) : UpdateState {
        override fun equals(other: Any?): Boolean = other is Downloading &&
            other.info == info &&
            other.progress == progress &&
            other.userInitiated == userInitiated

        override fun hashCode(): Int {
            var result = info.hashCode()
            result = 31 * result + progress.hashCode()
            result = 31 * result + userInitiated.hashCode()
            return result
        }

        override fun toString(): String = "Downloading(info=$info, progress=$progress, userInitiated=$userInitiated)"
    }

    /**
     * The download waits for a network that the settings allow.
     *
     * ```
     * if (state is UpdateState.WaitingForNetwork) showMessage("Waiting for Wi-Fi")
     * ```
     */
    public class WaitingForNetwork(
        /** The version waiting to be downloaded. */
        public val info: UpdateInfo,
    ) : UpdateState {
        override fun equals(other: Any?): Boolean = other is WaitingForNetwork && other.info == info

        override fun hashCode(): Int = info.hashCode()

        override fun toString(): String = "WaitingForNetwork(info=$info)"
    }

    /**
     * The file is downloaded and verified. [Renovo.installIfReady] installs it.
     *
     * ```
     * if (state is UpdateState.ReadyToInstall) installButton.setOnClickListener { Renovo.installIfReady() }
     * ```
     */
    public class ReadyToInstall(
        /** The version ready to install. */
        public val info: UpdateInfo,
    ) : UpdateState {
        override fun equals(other: Any?): Boolean = other is ReadyToInstall && other.info == info

        override fun hashCode(): Int = info.hashCode()

        override fun toString(): String = "ReadyToInstall(info=$info)"
    }

    /**
     * The user must allow this app to install updates. [Renovo.installIfReady] opens the setting.
     *
     * ```
     * if (state is UpdateState.PermissionNeeded) openSettingsButton.setOnClickListener { Renovo.installIfReady() }
     * ```
     */
    public class PermissionNeeded(
        /** The version waiting to be installed. */
        public val info: UpdateInfo,
    ) : UpdateState {
        override fun equals(other: Any?): Boolean = other is PermissionNeeded && other.info == info

        override fun hashCode(): Int = info.hashCode()

        override fun toString(): String = "PermissionNeeded(info=$info)"
    }

    /**
     * The update is with the system installer.
     *
     * ```
     * if (state is UpdateState.Installing) showSpinner()
     * ```
     */
    public class Installing(
        /** The version being installed. */
        public val info: UpdateInfo,
    ) : UpdateState {
        override fun equals(other: Any?): Boolean = other is Installing && other.info == info

        override fun hashCode(): Int = info.hashCode()

        override fun toString(): String = "Installing(info=$info)"
    }

    /**
     * A check, download, or install failed.
     *
     * The update UI shows a failure only when [userInitiated] is true. [Renovo.startDownload]
     * tries again; for a failed check with no [info], [Renovo.checkNow] does.
     *
     * ```
     * if (state is UpdateState.Failed && state.userInitiated) showError(state.reason)
     * ```
     */
    public class Failed(
        /** Why it failed. */
        public val reason: FailureReason,
        /** The version concerned, or null when a check failed before any version was known. */
        public val info: UpdateInfo?,
        /** True when the user started the action that failed, so the failure should be shown. */
        public val userInitiated: Boolean,
    ) : UpdateState {
        override fun equals(other: Any?): Boolean = other is Failed &&
            other.reason == reason &&
            other.info == info &&
            other.userInitiated == userInitiated

        override fun hashCode(): Int {
            var result = reason.hashCode()
            result = 31 * result + info.hashCode()
            result = 31 * result + userInitiated.hashCode()
            return result
        }

        override fun toString(): String = "Failed(reason=$reason, info=$info, userInitiated=$userInitiated)"
    }

    /**
     * This device does not allow the app to update itself, and no forced update is known.
     * Nothing is tried until the installed version changes.
     *
     * ```
     * val canUpdate = Renovo.state.value != UpdateState.Unsupported
     * ```
     */
    public data object Unsupported : UpdateState

    /**
     * A forced update exists: the user cannot postpone it, and the host app should stay blocked.
     *
     * It stays set through download and install; [step] says what is happening.
     *
     * ```
     * if (state is UpdateState.Required) showBlockingScreen(state.info, state.step)
     * ```
     */
    public class Required(
        /** The version the user must install. */
        public val info: UpdateInfo,
        /** What the forced update is doing now. */
        public val step: UpdateStep,
    ) : UpdateState {
        override fun equals(other: Any?): Boolean = other is Required && other.info == info && other.step == step

        override fun hashCode(): Int = 31 * info.hashCode() + step.hashCode()

        override fun toString(): String = "Required(info=$info, step=$step)"
    }
}
