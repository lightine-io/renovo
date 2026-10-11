package io.lightine.renovo.ui

import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Tells whether the device has a network right now, and follows it. The forced update screen shows
 * its offline note from this value.
 *
 * It watches the default network. Any network counts, because the note only promises that the
 * update starts "when the device is online"; the engine decides whether the network may be used.
 * The default-network callback exists from API 24, which is the minimum of this module, so no
 * version branch is needed. Needs `ACCESS_NETWORK_STATE`, which `renovo-core` declares.
 *
 * ```
 * val online = rememberIsOnline()
 * ```
 */
@Composable
internal fun rememberIsOnline(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.applicationContext.getSystemService(ConnectivityManager::class.java) }
    var online by remember(manager) { mutableStateOf(manager.activeNetwork != null) }
    DisposableEffect(manager) {
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                // The callback remembers which network it saw, because the loss of an old default
                // network can be reported after the new default network is already available.
                private var current: Network? = null

                override fun onAvailable(network: Network) {
                    current = network
                    online = true
                }

                override fun onLost(network: Network) {
                    if (network == current) {
                        current = null
                        online = false
                    }
                }
            }
        manager.registerDefaultNetworkCallback(callback)
        // The state may have changed between the first read and the registration.
        online = manager.activeNetwork != null
        onDispose { manager.unregisterNetworkCallback(callback) }
    }
    return online
}
