package io.lightine.renovo.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A square tile with an icon, at the top of a dialog. Decorative: the headline below it says
 * everything, so screen readers skip it.
 *
 * The caller picks the color roles, for example `primaryContainer` with `onPrimaryContainer`, or
 * `errorContainer` with `error` for a failure.
 */
@Composable
internal fun IconTile(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    iconColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Box(
        modifier =
        modifier
            .size(ICON_TILE_SIZE)
            .semantics { hideFromAccessibility() }
            .background(containerColor, ICON_TILE_SHAPE),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(ICON_TILE_ICON_SIZE),
        )
    }
}

/** The number tile in front of a step of the permission screen. */
@Composable
internal fun StepTile(
    number: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
        modifier
            .size(STEP_TILE_SIZE)
            .background(MaterialTheme.colorScheme.primaryContainer, STEP_TILE_SHAPE),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/**
 * A rounded card inside a dialog or a screen: the release notes, the verified line, the reason
 * line. [color] defaults to `surfaceContainerLow`; the text color follows from it.
 */
@Composable
internal fun InnerCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = INNER_CARD_SHAPE,
        color = color,
        contentColor = contentColorFor(color),
    ) {
        Column(modifier = Modifier.padding(INNER_CARD_PADDING), content = content)
    }
}

private val ICON_TILE_SIZE = 56.dp
private val ICON_TILE_ICON_SIZE = 28.dp
private val ICON_TILE_SHAPE = RoundedCornerShape(20.dp)
private val STEP_TILE_SIZE = 32.dp
private val STEP_TILE_SHAPE = RoundedCornerShape(12.dp)
private val INNER_CARD_SHAPE = RoundedCornerShape(20.dp)
private val INNER_CARD_PADDING = 16.dp
