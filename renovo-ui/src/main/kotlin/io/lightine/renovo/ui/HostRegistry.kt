package io.lightine.renovo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.lang.ref.WeakReference

/**
 * The hosts that are resumed right now, in the order they resumed (technical specification, several
 * windows). The update state is process-wide, so every attached activity could draw the same
 * dialog. Only the host that resumed last draws it.
 *
 * A host is keyed by its [LifecycleOwner], which is the activity. The keys are held weakly: an
 * activity that is destroyed without a pause, which the system can do, must not stay in memory
 * because of this list.
 *
 * Call it from the main thread only. The list is snapshot state, so a composition that asks
 * [isActive] redraws when another host takes over.
 *
 * ```
 * registry.resumed(activityA)
 * registry.resumed(activityB)
 * registry.isActive(activityB) // true, and registry.isActive(activityA) is false
 * ```
 */
internal class HostRegistry {
    private var resumedHosts by mutableStateOf<List<WeakReference<Any>>>(emptyList())

    /** Makes [owner] the active host. A host that is already registered moves to the end. */
    fun resumed(owner: Any) {
        resumedHosts = withoutOwner(owner) + WeakReference(owner)
    }

    /** Takes [owner] out. The host that resumed before it, if it is still resumed, becomes active again. */
    fun paused(owner: Any) {
        resumedHosts = withoutOwner(owner)
    }

    /** True when [owner] is the most recently resumed host that is still alive. */
    fun isActive(owner: Any): Boolean = newestAlive() === owner

    private fun withoutOwner(owner: Any): List<WeakReference<Any>> = resumedHosts.filter { reference ->
        val held = reference.get()
        held != null && held !== owner
    }

    private fun newestAlive(): Any? {
        val hosts = resumedHosts
        for (index in hosts.indices.reversed()) {
            val held = hosts[index].get()
            if (held != null) return held
        }
        return null
    }

    internal companion object {
        /** The registry of this process, used by both entry points. */
        val process = HostRegistry()
    }
}

/**
 * Registers the calling host while [owner] is resumed and tells whether it is the one that should
 * draw the update UI.
 *
 * [owner] and [registry] are parameters so a test can run two hosts, which stand for two
 * activities, in one composition. The entry points use the defaults.
 *
 * ```
 * val active = rememberIsActiveHost()
 * if (active) UpdateUi(...)
 * ```
 */
@Composable
internal fun rememberIsActiveHost(
    owner: LifecycleOwner = LocalLifecycleOwner.current,
    registry: HostRegistry = HostRegistry.process,
): Boolean {
    DisposableEffect(owner, registry) {
        // The lifecycle replays the events it has already passed to a new observer, so a host that
        // is attached to an activity that is already resumed registers at once.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> registry.resumed(owner)
                Lifecycle.Event.ON_PAUSE -> registry.paused(owner)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            registry.paused(owner)
        }
    }
    return registry.isActive(owner)
}
