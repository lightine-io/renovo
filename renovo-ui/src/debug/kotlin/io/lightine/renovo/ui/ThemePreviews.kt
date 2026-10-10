package io.lightine.renovo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.lightine.renovo.ThemeMode

// A row of the color roles the update UI uses, so a preview shows which scheme won.
@Composable
private fun Swatches(label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val scheme = MaterialTheme.colorScheme
            listOf(
                scheme.primary,
                scheme.primaryContainer,
                scheme.surfaceContainerHigh,
                scheme.surfaceContainerLow,
                scheme.errorContainer,
            ).forEach { color ->
                Row(Modifier.size(40.dp).background(color, RoundedCornerShape(12.dp))) {}
            }
        }
    }
}

@Preview(name = "Theme, baseline, system light", showBackground = true)
@Composable
internal fun RenovoThemeBaselineLightPreview() {
    RenovoTheme(ThemeMode.Light, dynamicColor = false, colorSchemes = null, inheritHostTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface) { Swatches("Baseline, ThemeMode.Light") }
    }
}

@Preview(name = "Theme, baseline, dark", showBackground = true)
@Composable
internal fun RenovoThemeBaselineDarkPreview() {
    RenovoTheme(ThemeMode.Dark, dynamicColor = false, colorSchemes = null, inheritHostTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface) { Swatches("Baseline, ThemeMode.Dark") }
    }
}

@Preview(name = "Theme, host color schemes", showBackground = true)
@Composable
internal fun RenovoThemeColorSchemesPreview() {
    val schemes =
        RenovoColorSchemes(
            light = lightColorScheme(primary = Color(0xFF006C4C), primaryContainer = Color(0xFF89F8C7)),
            dark = darkColorScheme(primary = Color(0xFF6CDBAC), primaryContainer = Color(0xFF005138)),
        )
    RenovoTheme(ThemeMode.Light, dynamicColor = true, colorSchemes = schemes, inheritHostTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface) { Swatches("colorSchemes win over dynamic color") }
    }
}

@Preview(name = "Theme, inherits the host theme", showBackground = true)
@Composable
internal fun RenovoThemeInheritedPreview() {
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFFB3261E), primaryContainer = Color(0xFFF9DEDC))) {
        RenovoTheme(ThemeMode.Dark, dynamicColor = true, colorSchemes = null, inheritHostTheme = true) {
            Surface(color = MaterialTheme.colorScheme.surface) { Swatches("Host theme kept, ThemeMode ignored") }
        }
    }
}
