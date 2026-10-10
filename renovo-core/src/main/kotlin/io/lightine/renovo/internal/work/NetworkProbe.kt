package io.lightine.renovo.internal.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.work.NetworkType

/**
 * Tells whether the device's current default network satisfies a download's network constraint.
 *
 * WorkManager does not say why it holds work back, so the downloader asks the same question itself
 * to find out whether a queued download is waiting for a network.
 */
internal class NetworkProbe(
    context: Context,
    private val roaming: RoamingCapability = RoamingCapability.forDevice(),
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    fun satisfies(type: NetworkType): Boolean {
        val manager = connectivity ?: return true
        val capabilities =
            try {
                manager.getNetworkCapabilities(manager.activeNetwork ?: return false)
            } catch (_: SecurityException) {
                // ACCESS_NETWORK_STATE was removed by the host app. Without the answer, saying "waiting"
                // would put a wrong message in front of the user, so assume the network is fine.
                return true
            } ?: return false
        // VALIDATED is what WorkManager requires on API 28 and up as well: a captive portal is no network.
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            return false
        }
        return when (type) {
            NetworkType.UNMETERED -> capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            NetworkType.NOT_ROAMING -> roaming.isNotRoaming(capabilities)
            else -> true
        }
    }
}

/** Reads "not roaming" from the capabilities. The capability exists only from API 28. */
internal fun interface RoamingCapability {
    fun isNotRoaming(capabilities: NetworkCapabilities): Boolean

    companion object {
        fun forDevice(): RoamingCapability = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) CapabilityRoaming else AssumeNotRoaming
    }
}

/** Below API 28 nothing tells roaming from home on the network itself, so the network counts as fine. */
internal object AssumeNotRoaming : RoamingCapability {
    override fun isNotRoaming(capabilities: NetworkCapabilities): Boolean = true
}

@RequiresApi(Build.VERSION_CODES.P)
internal object CapabilityRoaming : RoamingCapability {
    override fun isNotRoaming(capabilities: NetworkCapabilities): Boolean = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
}
