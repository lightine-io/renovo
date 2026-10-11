package io.lightine.renovo.sample

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.lightine.renovo.Renovo
import io.lightine.renovo.UpdateListener
import io.lightine.renovo.ui.attachUi

/** The Views entry point: no Compose in this activity's own code, only [attachUi] for the update UI. */
class LegacyActivity : AppCompatActivity() {
    // Kept as a field so onDestroy removes the same instance. The listener runs on the main thread.
    private val listener =
        UpdateListener { state ->
            findViewById<TextView>(R.id.stateText).text = state.describe()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_legacy)
        findViewById<TextView>(R.id.versionText).text =
            getString(R.string.version_line, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        findViewById<Button>(R.id.closeButton).setOnClickListener { finish() }

        // After setContentView: attachUi adds its view on top of the content that is already there.
        Renovo.attachUi(this)
        Renovo.addListener(listener)
    }

    override fun onDestroy() {
        Renovo.removeListener(listener)
        super.onDestroy()
    }
}
