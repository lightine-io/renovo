package io.lightine.renovo.ui

import android.content.Context
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ApplicationProvider
import io.lightine.renovo.Renovo
import io.lightine.renovo.ThemeMode
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateState
import io.lightine.renovo.VersionSource
import kotlinx.coroutines.awaitCancellation
import org.robolectric.shadows.ShadowLooper

/** The version that the entry-point tests hand to `Renovo.submit`. */
internal val availableInfo: UpdateInfo = uiInfo(versionCode = 4242)

/**
 * Starts the real `Renovo` once per test process and brings its state to `Available`, so a test of
 * an entry point reads the same flow a host app reads.
 *
 * `Renovo.init` ignores later calls, so the settings below are the ones every entry-point test sees:
 * the baseline light theme, which gives the same colors on every machine. The version source never
 * answers, so no check result can replace the state a test set up.
 */
internal fun ComposeTestRule.startRenovoWithAvailableUpdate() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    Renovo.init(context) {
        versionSource = VersionSource { awaitCancellation() }
        autoDownload = false
        notifications = false
        ui {
            themeMode = ThemeMode.Light
            dynamicColor = false
        }
    }
    waitUntil(timeoutMillis = 10_000) {
        // The first test of a process can submit before the engine has restored its stored state, and
        // the restore then overwrites the submit. Submitting again until it sticks removes that race.
        // The engine's work is posted to the main looper, which Robolectric runs only when told to.
        ShadowLooper.idleMainLooper()
        val state = Renovo.state.value
        val available = state is UpdateState.Available && state.info == availableInfo
        if (!available) Renovo.submit(availableInfo)
        available
    }
}
