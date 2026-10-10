package io.lightine.renovo.internal.work

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.util.UUID

/** The branches that differ by API level, on the oldest level Renovo supports. */
@Config(sdk = [24])
internal class WorkApiLevelsOldTest : WorkTestBase() {
    @Test
    fun `below API 26 free space is the free space of the partition`() {
        assertSame(UsableFreeSpace, FreeSpace.forDevice())
        assertEquals(context.filesDir.usableSpace, UsableFreeSpace.availableBytes(context, context.filesDir))
    }

    @Test
    fun `below API 28 a network counts as not roaming`() {
        assertSame(AssumeNotRoaming, RoamingCapability.forDevice())
        setNetwork(roaming = true)

        // The capability does not exist, and WorkManager treats the constraint as "connected" there too.
        assertTrue(NetworkProbe(context).satisfies(NetworkType.NOT_ROAMING))
    }

    @Test
    fun `the notification has no service type below API 29`() {
        val info = DownloadNotification(context, UUID.randomUUID()).foregroundInfo(1, 2)

        assertEquals(0, info.foregroundServiceType)
    }
}

internal class WorkApiLevelsNewTest : WorkTestBase() {
    @Test
    fun `from API 26 free space is what the app can allocate`() {
        assertSame(AllocatableFreeSpace, FreeSpace.forDevice())
    }

    @Test
    fun `from API 28 the roaming capability decides`() {
        assertSame(CapabilityRoaming, RoamingCapability.forDevice())
        setNetwork(roaming = true)
        assertFalse(NetworkProbe(context).satisfies(NetworkType.NOT_ROAMING))

        setNetwork(roaming = false)
        assertTrue(NetworkProbe(context).satisfies(NetworkType.NOT_ROAMING))
    }
}

internal class NetworkProbeTest : WorkTestBase() {
    @Test
    fun `unmetered needs the not-metered capability`() {
        setNetwork(metered = true)
        assertFalse(NetworkProbe(context).satisfies(NetworkType.UNMETERED))
        assertTrue(NetworkProbe(context).satisfies(NetworkType.CONNECTED))

        setNetwork(metered = false)
        assertTrue(NetworkProbe(context).satisfies(NetworkType.UNMETERED))
    }

    @Test
    fun `a network that is not validated is no network`() {
        setNetwork(validated = false)

        assertFalse(NetworkProbe(context).satisfies(NetworkType.CONNECTED))
    }

    @Test
    fun `no active network satisfies nothing`() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, null)

        assertFalse(NetworkProbe(context).satisfies(NetworkType.CONNECTED))
    }
}
