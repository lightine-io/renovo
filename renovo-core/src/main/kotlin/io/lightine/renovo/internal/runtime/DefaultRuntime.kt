package io.lightine.renovo.internal.runtime

import android.app.Activity
import io.lightine.renovo.FailureReason
import io.lightine.renovo.RenovoConfig
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateListener
import io.lightine.renovo.UpdateState
import io.lightine.renovo.UpdateStep
import io.lightine.renovo.internal.ForegroundTracker
import io.lightine.renovo.internal.InstallPermission
import io.lightine.renovo.internal.RenovoRuntime
import io.lightine.renovo.internal.VerificationResult
import io.lightine.renovo.internal.engine.Effect
import io.lightine.renovo.internal.engine.EngineEvent
import io.lightine.renovo.internal.engine.EventSink
import io.lightine.renovo.internal.engine.InstallResult
import io.lightine.renovo.internal.engine.UpdateEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Connects the [UpdateEngine] to the components. It holds no update rules: it feeds events to the
 * engine one at a time and does what the returned [Effect]s say.
 *
 * Every event goes through one [EventQueue] and one loop, so the engine sees them in arrival order
 * and the effects of one event finish before the next event is handled. A component that fails is
 * logged and, where the engine is waiting for an answer, turned into the matching failure event.
 * Nothing a component does can crash the host app through this class.
 *
 * @param scope Owned by the caller. The loop, the verification, and the listeners run in it.
 * @param mainDispatcher Where the install, the settings screen, and the listeners run. Android
 *   requires the main thread for them.
 */
internal class DefaultRuntime(
    override val config: RenovoConfig,
    private val components: Components,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher,
    private val engine: UpdateEngine = UpdateEngine(config.engineSettings()),
    private val queue: EventQueue = EventQueue(),
) : RenovoRuntime {
    override val logger: RenovoLogger? = config.logger

    override val state: StateFlow<UpdateState> = engine.state

    override val events: EventSink = queue

    override val foreground: ForegroundTracker = components.foreground

    private val started = AtomicBoolean(false)
    private val listeners = ConcurrentHashMap<UpdateListener, Job>()

    // An install that came due while the app was in the background. The installer needs the
    // foreground, so it waits here for the next foreground callback.
    private val deferredInstall = AtomicReference<Effect.Install?>(null)

    @Volatile
    private var installedVersionCode = 0L

    /**
     * Starts the loop. The first event the engine hears is the process start, whatever the
     * components sent before; the engine ignores everything that comes before it.
     *
     * Calling it again does nothing.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        try {
            components.foreground.onForeground(::onForeground)
        } catch (e: Exception) {
            log("The foreground callback could not be registered.", e)
        }
        scope.launch {
            restoredEvent()?.let(::process)
            for (event in queue.channel) process(event)
        }
    }

    override fun addListener(listener: UpdateListener) {
        // The collector runs on the main dispatcher: with an immediate one, a call from the main
        // thread delivers the current state before this function returns.
        val job =
            scope.launch(mainDispatcher) {
                state.collect { current ->
                    try {
                        listener.onStateChanged(current)
                    } catch (e: Exception) {
                        // Host code. One listener that throws must not silence the others or stop its own updates.
                        log("An update listener threw an exception.", e)
                    }
                }
            }
        listeners.put(listener, job)?.cancel()
    }

    override fun removeListener(listener: UpdateListener) {
        listeners.remove(listener)?.cancel()
    }

    override fun checkNow() = queue.send(EngineEvent.CheckNow)

    override fun startDownload() = queue.send(EngineEvent.StartDownload)

    override fun installIfReady() = queue.send(EngineEvent.InstallIfReady)

    override fun submit(info: UpdateInfo) = queue.send(EngineEvent.Submit(info))

    override fun useMobileDataOnce() = queue.send(EngineEvent.UseMobileDataOnce)

    override fun requestNotificationPermission(activity: Activity) {
        try {
            components.notifier.requestPermission(activity)
        } catch (e: Exception) {
            log("Asking for the notification permission failed.", e)
        }
    }

    // ---- Start ---------------------------------------------------------------------------------

    private fun restoredEvent(): EngineEvent.Restored? {
        val installed =
            try {
                components.installedVersionCode()
            } catch (e: Exception) {
                // Without it the engine cannot tell a newer version from the running one. It stays
                // idle, because guessing could install an older version over a newer one.
                log("The installed version could not be read. Renovo does nothing in this process.", e)
                return null
            }
        installedVersionCode = installed
        val snapshot =
            try {
                components.store.load()
            } catch (e: Exception) {
                log("The stored update state could not be loaded and was ignored.", e)
                null
            }
        val path = snapshot?.download?.filePath
        return EngineEvent.Restored(snapshot, installed, fileExists = path != null && File(path).isFile)
    }

    // ---- The loop ------------------------------------------------------------------------------

    private fun process(event: EngineEvent) {
        val effects =
            try {
                engine.handle(event)
            } catch (e: Exception) {
                // The engine does no I/O and should not throw. If it does, dropping the one event
                // keeps the loop alive for the next.
                log("The update engine failed on an event. The event was dropped.", e)
                return
            }
        for (effect in effects) {
            try {
                execute(effect)
            } catch (e: Exception) {
                log("An effect failed: ${effect::class.simpleName}.", e)
            }
        }
    }

    private fun execute(effect: Effect) {
        when (effect) {
            is Effect.Persist -> persist(effect)
            is Effect.StartCheck -> startCheck(effect)
            Effect.ScheduleChecks -> scheduleChecks()
            is Effect.EnqueueDownload -> enqueueDownload(effect)
            Effect.CancelDownload -> cancelDownload()
            is Effect.Verify -> verify(effect)
            is Effect.DeleteFile -> deleteFile(effect)
            is Effect.Install -> scope.launch(mainDispatcher) { install(effect) }
            Effect.OpenInstallPermission -> scope.launch(mainDispatcher) { openInstallPermission() }
            is Effect.ShowReadyNotification -> showReadyNotification(effect)
            Effect.CancelReadyNotification -> cancelReadyNotification()
        }
    }

    // On the loop and not in the background, so the snapshot is on disk before the next effect starts work.
    private fun persist(effect: Effect.Persist) {
        try {
            components.store.save(effect.snapshot)
        } catch (e: Exception) {
            log("Saving the update state failed.", e)
        }
    }

    private fun startCheck(effect: Effect.StartCheck) {
        try {
            components.scheduler.checkNow(effect.userInitiated)
        } catch (e: Exception) {
            log("Starting the check failed.", e)
            // The engine waits for an outcome only after CheckStarted. Reporting both gives a
            // check the user asked for a visible failure, and leaves a background check unnoticed.
            queue.send(EngineEvent.CheckStarted(effect.userInitiated))
            queue.send(EngineEvent.CheckFailed(FailureReason.Parse))
        }
    }

    private fun scheduleChecks() {
        try {
            components.scheduler.schedulePeriodicCheck(config.checkInterval)
        } catch (e: Exception) {
            log("Scheduling the periodic check failed.", e)
        }
    }

    private fun enqueueDownload(effect: Effect.EnqueueDownload) {
        try {
            components.downloader.enqueue(effect.info, effect.network, effect.userInitiated)
        } catch (e: Exception) {
            log("Queueing the download failed.", e)
            // The engine waits for this download to finish or fail.
            queue.send(EngineEvent.DownloadFailed(effect.info.versionCode, FailureReason.Network))
        }
    }

    private fun cancelDownload() {
        try {
            components.downloader.cancel()
        } catch (e: Exception) {
            log("Cancelling the download failed.", e)
        }
    }

    private fun verify(effect: Effect.Verify) {
        val versionCode = effect.info.versionCode
        // Hashing and reading the signing block take time, so this is not done on the loop.
        scope.launch {
            val event =
                try {
                    when (val result = components.verifier.verify(File(effect.filePath), effect.info, installedVersionCode)) {
                        VerificationResult.Passed -> EngineEvent.VerificationPassed(versionCode, effect.filePath)
                        is VerificationResult.Failed -> EngineEvent.VerificationFailed(versionCode, effect.filePath, result.reason)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Verifying the download failed unexpectedly.", e)
                    // The same answer the verifier itself gives for what it cannot explain: the
                    // file is the thing in doubt, so it is not installed.
                    EngineEvent.VerificationFailed(versionCode, effect.filePath, FailureReason.HashMismatch)
                }
            queue.send(event)
        }
    }

    // The complete file has no sidecar: the downloader removes the validator when it moves the
    // partial file into place, and keeps the files of other versions out on its own.
    private fun deleteFile(effect: Effect.DeleteFile) {
        deferredInstall.updateAndGet { if (it?.filePath == effect.filePath) null else it }
        val file = File(effect.filePath)
        if (file.exists() && !file.delete()) log("A downloaded file could not be deleted.", null)
    }

    private fun showReadyNotification(effect: Effect.ShowReadyNotification) {
        // The engine checked the setting. Whether the app is in front is only known here.
        if (components.foreground.isInForeground) return
        try {
            components.notifier.showReady(effect.info)
        } catch (e: Exception) {
            log("Showing the update ready notification failed.", e)
        }
    }

    private fun cancelReadyNotification() {
        try {
            components.notifier.cancelReady()
        } catch (e: Exception) {
            log("Removing the update ready notification failed.", e)
        }
    }

    // ---- Main thread: install and settings -----------------------------------------------------

    private fun install(effect: Effect.Install) {
        try {
            installOnMainThread(effect)
        } catch (e: Exception) {
            log("Starting the install failed.", e)
        }
    }

    private fun installOnMainThread(effect: Effect.Install) {
        if (!isStillWanted(effect)) {
            log("An install request was dropped: its file is no longer the verified download.", null)
            return
        }
        // Checked before canInstallNow, which the host app answers for an install that is about to
        // start. The installer launches the system screen and must run in front of the user.
        if (!components.foreground.isInForeground) {
            deferredInstall.set(effect)
            log("The install waits until the app is in the foreground.", null)
            return
        }
        deferredInstall.updateAndGet { if (it?.filePath == effect.filePath) null else it }

        // Host code. An exception counts as "not now", the safe answer.
        val allowed =
            try {
                config.canInstallNow()
            } catch (e: Exception) {
                log("canInstallNow threw an exception. The install is postponed.", e)
                false
            }
        if (!allowed) {
            queue.send(EngineEvent.InstallNotAllowedNow)
            return
        }

        val permission =
            try {
                components.permissionGate.status()
            } catch (e: Exception) {
                // Not "unsupported": that is permanent until the next version, and this is a hiccup.
                log("Reading the install permission failed. The install is postponed.", e)
                queue.send(EngineEvent.InstallNotAllowedNow)
                return
            }
        when (permission) {
            InstallPermission.Unsupported -> queue.send(EngineEvent.DeviceUnsupported)
            InstallPermission.NeedsUser -> queue.send(EngineEvent.PermissionNeeded)
            InstallPermission.Granted -> startInstaller(effect)
        }
    }

    private fun startInstaller(effect: Effect.Install) {
        try {
            components.installer.install(File(effect.filePath), effect.info)
        } catch (e: Exception) {
            log("The installer failed to start.", e)
            // The installer reports its own outcomes. If it throws instead, the engine would stay
            // in the ready state with no feedback, so the user is told the install did not work.
            queue.send(EngineEvent.InstallFinished(InstallResult.Rejected))
        }
    }

    // The deferred request was valid when it was made. The download can have been replaced since.
    private fun isStillWanted(effect: Effect.Install): Boolean {
        val record = engine.currentSnapshot().download ?: return false
        return record.verified && record.filePath == effect.filePath
    }

    private fun openInstallPermission() {
        // The gate also answers false when no activity is resumed, for example when the user left
        // right after the tap. That says nothing about the device, and "unsupported" would stop
        // updates until the next app version, so the tap is dropped and the next one tries again.
        if (components.foreground.resumedActivity == null) {
            log("No activity is resumed, so the install permission screen was not opened.", null)
            return
        }
        val opened =
            try {
                components.permissionGate.openSettings()
            } catch (e: Exception) {
                // Not "unsupported": an exception says nothing about whether the screen exists.
                log("Opening the install permission screen failed.", e)
                return
            }
        if (!opened) queue.send(EngineEvent.DeviceUnsupported)
    }

    // ---- Foreground ----------------------------------------------------------------------------

    // Runs on the main thread, every time the app comes to the foreground.
    private fun onForeground() {
        deferredInstall.getAndSet(null)?.let(::install)
        try {
            if (waitsForInstallPermission() && components.permissionGate.status() == InstallPermission.Granted) {
                queue.send(EngineEvent.PermissionGranted)
            }
        } catch (e: Exception) {
            log("Checking the install permission on return failed.", e)
        }
    }

    private fun waitsForInstallPermission(): Boolean = when (val current = state.value) {
        is UpdateState.PermissionNeeded -> true
        is UpdateState.Required -> current.step == UpdateStep.PermissionNeeded
        else -> false
    }

    // The logger is host code too, and it is called from catch blocks. A logger that throws must not undo the catch.
    private fun log(
        message: String,
        error: Throwable?,
    ) {
        try {
            logger?.log(message, error)
        } catch (_: Exception) {
            // Nowhere left to report it.
        }
    }
}
