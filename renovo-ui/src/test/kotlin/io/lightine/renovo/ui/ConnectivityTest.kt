package io.lightine.renovo.ui

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ConnectivityTest {
    @get:Rule
    val rule = createComposeRule()

    private val manager: ConnectivityManager =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(ConnectivityManager::class.java)

    private var online = false

    private fun show() {
        rule.setContent { online = rememberIsOnline() }
        rule.waitForIdle()
    }

    private fun callbacks() = shadowOf(manager).networkCallbacks.toList()

    @Test
    fun isOnlineWhenThereIsADefaultNetwork() {
        show()
        assertTrue(online)
    }

    @Test
    fun isOfflineWhenThereIsNoDefaultNetworkAtTheStart() {
        shadowOf(manager).setActiveNetworkInfo(null)
        show()
        assertFalse(online)
    }

    @Test
    fun followsTheLossAndTheReturnOfTheDefaultNetwork() {
        show()
        val network = checkNotNull(manager.activeNetwork)
        assertEquals(1, callbacks().size)

        rule.runOnUiThread { callbacks().forEach { it.onAvailable(network) } }
        rule.runOnUiThread { callbacks().forEach { it.onLost(network) } }
        rule.waitForIdle()
        assertFalse(online)

        rule.runOnUiThread { callbacks().forEach { it.onAvailable(network) } }
        rule.waitForIdle()
        assertTrue(online)
    }

    @Test
    fun theLateLossOfAnOlderNetworkDoesNotTakeAwayTheNewDefault() {
        show()
        val old = ShadowNetwork.newInstance(OLD_NETWORK_ID)
        val new = ShadowNetwork.newInstance(NEW_NETWORK_ID)

        rule.runOnUiThread { callbacks().forEach { it.onAvailable(old) } }
        rule.runOnUiThread { callbacks().forEach { it.onAvailable(new) } }
        // The system may report the loss of the old default after the new one is available.
        rule.runOnUiThread { callbacks().forEach { it.onLost(old) } }
        rule.waitForIdle()
        assertTrue(online)
    }

    @Test
    fun stopsListeningWhenItLeavesTheComposition() {
        var showIt by mutableStateOf(true)
        rule.setContent { if (showIt) online = rememberIsOnline() }
        rule.waitForIdle()
        assertEquals(1, callbacks().size)

        rule.runOnIdle { showIt = false }
        rule.waitForIdle()
        assertEquals(0, callbacks().size)
    }

    private companion object {
        const val OLD_NETWORK_ID = 101
        const val NEW_NETWORK_ID = 102
    }
}
