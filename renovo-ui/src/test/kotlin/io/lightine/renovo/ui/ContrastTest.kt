package io.lightine.renovo.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

// UI specification part 5: text contrast is at least 4.5:1 in both themes with the baseline scheme.
// Each pair is a text color on the container it is drawn on, as the screens use them.
class ContrastTest {
    private fun ColorScheme.textPairs(): List<Triple<String, Color, Color>> = listOf(
        Triple("dialog headline", onSurface, surfaceContainerHigh),
        Triple("dialog body", onSurfaceVariant, surfaceContainerHigh),
        Triple("text button", primary, surfaceContainerHigh),
        Triple("primary button", onPrimary, primary),
        Triple("card label and version line", onSurfaceVariant, surfaceContainerLow),
        Triple("release notes and verified line", onSurface, surfaceContainerLow),
        Triple("forced headline", onSurface, surface),
        Triple("forced body and offline note", onSurfaceVariant, surface),
        Triple("versions pill", onSurfaceVariant, surfaceContainerLow),
        Triple("reason card", onErrorContainer, errorContainer),
        Triple("outlined button", primary, surface),
        Triple("step number", onPrimaryContainer, primaryContainer),
    )

    private fun contrast(
        a: Color,
        b: Color,
    ): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    private fun assertReadable(
        theme: String,
        scheme: ColorScheme,
    ) {
        for ((name, text, background) in scheme.textPairs()) {
            val ratio = contrast(text, background)
            assertTrue("$theme $name: $ratio:1", ratio >= MIN_RATIO)
        }
    }

    @Test
    fun everyTextIsReadableInTheLightBaselineScheme() = assertReadable("light", lightColorScheme())

    @Test
    fun everyTextIsReadableInTheDarkBaselineScheme() = assertReadable("dark", darkColorScheme())

    private companion object {
        const val MIN_RATIO = 4.5f
    }
}
