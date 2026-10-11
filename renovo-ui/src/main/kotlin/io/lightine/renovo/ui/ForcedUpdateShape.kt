package io.lightine.renovo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import kotlin.math.sqrt

/**
 * The shape above the headline of the forced update screen: two rounded squares of the same size
 * and color, one turned by 45 degrees, with the download icon on top. [size] is the width and
 * height of the whole shape. Decorative, so screen readers skip it.
 *
 * It is drawn by hand because the design needs no Expressive API for it.
 */
@Composable
internal fun ForcedUpdateShape(
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.primaryContainer
    Box(
        modifier = modifier.size(size).semantics { hideFromAccessibility() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            // The turned square reaches furthest along the diagonal, less what its rounded corners
            // cut off. Solving that for the side length makes the whole shape fill the given size.
            val side = this.size.minDimension / SIDE_DIVISOR
            val squareSize = Size(side, side)
            val topLeft = Offset((this.size.width - side) / 2f, (this.size.height - side) / 2f)
            val corner = CornerRadius(side * CORNER_RATIO)
            drawRoundRect(color = color, topLeft = topLeft, size = squareSize, cornerRadius = corner)
            rotate(degrees = 45f) {
                drawRoundRect(color = color, topLeft = topLeft, size = squareSize, cornerRadius = corner)
            }
        }
        Icon(
            painter = painterResource(R.drawable.renovo_ic_download),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(size * ICON_RATIO),
        )
    }
}

// Corner radius as a share of the side. About a third gives the soft lobes of the design mockups.
private const val CORNER_RATIO = 0.3f

// Extent of a square turned by 45 degrees, in sides: sqrt(2) minus the two corner cut-offs,
// 2 * r * (sqrt(2) - 1) with r = CORNER_RATIO. The side is the shape size divided by this.
private val SIDE_DIVISOR = (sqrt(2.0) - 2.0 * CORNER_RATIO * (sqrt(2.0) - 1.0)).toFloat()

// Size of the icon box as a share of the shape. The download glyph fills only two thirds of the
// height of its box, so at this ratio the glyph is about 28 percent of the shape tall, as in the
// mockups. A quarter for the box drew the glyph at half the size of the mockups.
private const val ICON_RATIO = 0.42f
