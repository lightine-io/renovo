package io.lightine.renovo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** States compare by value, which StateFlow relies on to skip repeated states. */
class UpdateStateTest {
    private fun info(versionCode: Long = 2) = UpdateInfo(versionCode, "2.0", "https://example.com/app.apk")

    private fun progress(bytes: Long = 10) = DownloadProgress(bytes, 100)

    @Test
    fun `states with equal values are equal`() {
        val pairs =
            listOf(
                UpdateState.Available(info()) to UpdateState.Available(info()),
                UpdateState.Downloading(info(), progress(), true) to UpdateState.Downloading(info(), progress(), true),
                UpdateState.WaitingForNetwork(info()) to UpdateState.WaitingForNetwork(info()),
                UpdateState.ReadyToInstall(info()) to UpdateState.ReadyToInstall(info()),
                UpdateState.PermissionNeeded(info()) to UpdateState.PermissionNeeded(info()),
                UpdateState.Installing(info()) to UpdateState.Installing(info()),
                UpdateState.Failed(FailureReason.Network, info(), true) to UpdateState.Failed(FailureReason.Network, info(), true),
                UpdateState.Required(info(), UpdateStep.Downloading(progress())) to
                    UpdateState.Required(info(), UpdateStep.Downloading(progress())),
                UpdateState.Required(info(), UpdateStep.WaitingForNetwork(true)) to
                    UpdateState.Required(info(), UpdateStep.WaitingForNetwork(true)),
                UpdateState.Required(info(), UpdateStep.Failed(FailureReason.Cancelled)) to
                    UpdateState.Required(info(), UpdateStep.Failed(FailureReason.Cancelled)),
            )
        pairs.forEach { (a, b) ->
            assertEquals(a, b)
            assertEquals(a.hashCode(), b.hashCode())
        }
    }

    @Test
    fun `states with different values differ`() {
        assertNotEquals(UpdateState.Available(info(2)), UpdateState.Available(info(3)))
        assertNotEquals(UpdateState.Downloading(info(), progress(10), false), UpdateState.Downloading(info(), progress(20), false))
        assertNotEquals(UpdateState.Downloading(info(), progress(10), false), UpdateState.Downloading(info(), progress(10), true))
        assertNotEquals(UpdateState.Failed(FailureReason.Network, null, true), UpdateState.Failed(FailureReason.Network, null, false))
        assertNotEquals(UpdateState.Required(info(), UpdateStep.WaitingForUser), UpdateState.Required(info(), UpdateStep.Installing))
        val ready: UpdateState = UpdateState.ReadyToInstall(info())
        assertNotEquals(ready, UpdateState.Installing(info()))
    }

    @Test
    fun `download progress rejects negative values`() {
        assertThrows(IllegalArgumentException::class.java) { DownloadProgress(-1, 100) }
        assertThrows(IllegalArgumentException::class.java) { DownloadProgress(0, 0) }
        DownloadProgress(0, null)
    }
}
