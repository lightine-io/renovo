package io.lightine.renovo.sample

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.lightine.renovo.Network
import io.lightine.renovo.Renovo
import io.lightine.renovo.RenovoLogger
import kotlin.time.Duration.Companion.minutes

private const val LOG_TAG = "RenovoSample"

/** Process-wide switches that the test screen flips and Renovo's callbacks read. */
object SampleState {
    /**
     * True while the sample pretends to hold unsaved data. Renovo asks [Renovo.config]'s
     * `canInstallNow` right before every install (AC-4), and the answer comes from here.
     * Compose state, so the switch on the screen and the callback always see the same value.
     */
    var unsafe: Boolean by mutableStateOf(false)
}

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Renovo.init(this) {
            versionUrl = BuildConfig.VERSION_URL
            // 15 minutes is the smallest interval Renovo accepts (WorkManager's periodic minimum).
            checkInterval = 15.minutes
            autoDownload = true
            optionalDownloadNetwork = Network.Any
            // Chosen at build time so AC-12 can be tested on an emulator set to mobile data.
            forcedDownloadNetwork = Network.valueOf(BuildConfig.FORCED_NETWORK)
            allowMobileDataOverride = true
            notifications = true
            canInstallNow = { !SampleState.unsafe }
            logger = RenovoLogger { message, error -> Log.i(LOG_TAG, message, error) }
        }
    }
}
