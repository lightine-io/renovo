package io.lightine.renovo.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.lightine.renovo.ThemeMode

// The previews sit in src/debug so they never ship in the release library. Each one shows in a
// light and in a dark variant, which the IDE lists side by side under the names below.

/** Renders a preview in the light theme and in the dark theme. */
@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
internal annotation class LightAndDark

/**
 * The theme of every preview: the Material 3 baseline scheme, as in the design mockups, following
 * the preview's light or dark setting, on the `surface` color.
 */
@Composable
internal fun PreviewFrame(content: @Composable () -> Unit) {
    RenovoTheme(
        themeMode = ThemeMode.System,
        dynamicColor = false,
        colorSchemes = null,
        inheritHostTheme = false,
    ) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                content()
            }
        }
    }
}

@LightAndDark
@Composable
internal fun IconTilePreview() {
    PreviewFrame {
        IconTile(icon = R.drawable.renovo_ic_download)
        IconTile(
            icon = R.drawable.renovo_ic_error,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            iconColor = MaterialTheme.colorScheme.error,
        )
        IconTile(icon = R.drawable.renovo_ic_verified)
        IconTile(icon = R.drawable.renovo_ic_check)
    }
}

@LightAndDark
@Composable
internal fun StepTilePreview() {
    PreviewFrame {
        StepTile(number = 1)
        StepTile(number = 2)
        StepTile(number = 3)
    }
}

@LightAndDark
@Composable
internal fun InnerCardPreview() {
    PreviewFrame {
        InnerCard {
            Text(text = "A card on surfaceContainerLow", style = MaterialTheme.typography.bodyMedium)
        }
        InnerCard(color = MaterialTheme.colorScheme.errorContainer) {
            Text(text = "A card on errorContainer", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@LightAndDark
@Composable
internal fun ForcedUpdateShapeCompactPreview() {
    PreviewFrame { ForcedUpdateShape(size = 120.dp) }
}

@LightAndDark
@Composable
internal fun ForcedUpdateShapeMediumPreview() {
    PreviewFrame { ForcedUpdateShape(size = 168.dp) }
}

@LightAndDark
@Composable
internal fun ForcedUpdateShapeExpandedPreview() {
    PreviewFrame { ForcedUpdateShape(size = 240.dp) }
}

@LightAndDark
@Composable
internal fun ProgressPreview() {
    PreviewFrame {
        Text(text = "0 percent", style = MaterialTheme.typography.labelMedium)
        RenovoProgress(progress = 0f)
        Text(text = "42 percent", style = MaterialTheme.typography.labelMedium)
        RenovoProgress(progress = 0.42f)
        Text(text = "100 percent", style = MaterialTheme.typography.labelMedium)
        RenovoProgress(progress = 1f)
        Text(text = "Size not known", style = MaterialTheme.typography.labelMedium)
        RenovoProgress(progress = null)
    }
}

@LightAndDark
@Composable
internal fun WindowWidthPreview() {
    PreviewFrame {
        Text(text = "Window width: ${rememberWindowWidth()}", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "Hardware keyboard attached: ${isHardwareKeyboardAttached()}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
