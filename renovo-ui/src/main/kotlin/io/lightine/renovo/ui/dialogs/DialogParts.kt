package io.lightine.renovo.ui.dialogs

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// The pieces the five dialogs share, so the type and the buttons of the specification exist once.

/** The headline of a dialog: `headlineSmall`, medium weight. Screen readers announce it as a heading. */
@Composable
internal fun DialogHeadline(
    text: String,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        modifier = modifier.fillMaxWidth().semantics { heading() },
    )
}

/** The body text of a dialog: `bodyMedium` in `onSurfaceVariant`. */
@Composable
internal fun DialogBody(
    text: String,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        modifier = modifier.fillMaxWidth(),
    )
}

/** A dialog's text button: at least 48 dp tall, fully rounded. */
@Composable
internal fun DialogTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A minimum, not a fixed height, so a large font scale grows the button instead of clipping the label.
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = BUTTON_MIN_HEIGHT), shape = CircleShape) {
        Text(text)
    }
}

/** A dialog's filled primary button: at least 48 dp tall, fully rounded, `primary` with `onPrimary`. */
@Composable
internal fun DialogPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = BUTTON_MIN_HEIGHT), shape = CircleShape) {
        Text(text)
    }
}

private val BUTTON_MIN_HEIGHT = 48.dp
