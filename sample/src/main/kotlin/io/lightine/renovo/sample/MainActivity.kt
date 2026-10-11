package io.lightine.renovo.sample

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.lightine.renovo.Renovo
import io.lightine.renovo.ui.RenovoUpdateHost

/** The Compose entry point: the update UI is added with [RenovoUpdateHost]. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                RenovoUpdateHost {
                    MainScreen(
                        onRequestNotifications = { Renovo.requestNotificationPermission(this) },
                        onOpenLegacy = { startActivity(Intent(this, LegacyActivity::class.java)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MainScreen(
    onRequestNotifications: () -> Unit,
    onOpenLegacy: () -> Unit,
) {
    val state by Renovo.state.collectAsStateWithLifecycle()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Renovo sample", style = MaterialTheme.typography.headlineSmall)
            Text("Version ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
            Text("Version file: ${BuildConfig.VERSION_URL}")
            Text("State", style = MaterialTheme.typography.titleMedium)
            Text(state.describe())

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = SampleState.unsafe, onCheckedChange = { SampleState.unsafe = it })
                Text("Unsaved data (not safe to install)")
            }

            Button(onClick = { Renovo.checkNow() }) { Text("Check now") }
            Button(onClick = { Renovo.startDownload() }) { Text("Start download") }
            Button(onClick = { Renovo.installIfReady() }) { Text("Install if ready") }
            Button(onClick = onRequestNotifications) { Text("Ask for notifications") }
            Button(onClick = onOpenLegacy) { Text("Open the Views activity") }
        }
    }
}
