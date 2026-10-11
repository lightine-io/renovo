package io.lightine.renovo.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.lightine.renovo.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class RenovoThemeTest {
    @get:Rule
    val rule = createComposeRule()

    private val hostPrimary = Color(0xFF00695C)
    private val customLight = lightColorScheme(primary = Color(0xFF112233))
    private val customDark = darkColorScheme(primary = Color(0xFF445566))

    // Each test renders once, so the scheme the content sees is captured in a plain variable.
    private fun render(
        themeMode: ThemeMode = ThemeMode.Light,
        dynamicColor: Boolean = false,
        colorSchemes: RenovoColorSchemes? = null,
        inheritHostTheme: Boolean = false,
        hostScheme: ColorScheme? = null,
    ): ColorScheme {
        var seen: ColorScheme? = null
        rule.setContent {
            val inner: @Composable () -> Unit = {
                RenovoTheme(themeMode, dynamicColor, colorSchemes, inheritHostTheme) {
                    seen = MaterialTheme.colorScheme
                }
            }
            if (hostScheme == null) inner() else MaterialTheme(colorScheme = hostScheme, content = inner)
        }
        rule.waitForIdle()
        return checkNotNull(seen)
    }

    @Test
    fun lightModeGivesTheBaselineLightScheme() {
        val scheme = render(ThemeMode.Light)
        assertEquals(lightColorScheme().primary, scheme.primary)
        assertEquals(lightColorScheme().surface, scheme.surface)
    }

    @Test
    fun darkModeGivesTheBaselineDarkScheme() {
        val scheme = render(ThemeMode.Dark)
        assertEquals(darkColorScheme().primary, scheme.primary)
        assertEquals(darkColorScheme().surface, scheme.surface)
        assertNotEquals(lightColorScheme().surface, scheme.surface)
    }

    @Test
    @Config(sdk = [37], qualifiers = "night")
    fun systemModeFollowsTheSystemWhenItIsDark() {
        assertEquals(darkColorScheme().surface, render(ThemeMode.System).surface)
    }

    @Test
    @Config(sdk = [37], qualifiers = "notnight")
    fun systemModeFollowsTheSystemWhenItIsLight() {
        assertEquals(lightColorScheme().surface, render(ThemeMode.System).surface)
    }

    @Test
    fun colorSchemesWinOverDynamicColor() {
        val schemes = RenovoColorSchemes(customLight, customDark)
        assertEquals(customLight.primary, render(ThemeMode.Light, dynamicColor = true, colorSchemes = schemes).primary)
    }

    @Test
    fun colorSchemesGiveTheirDarkSchemeInDarkMode() {
        val schemes = RenovoColorSchemes(customLight, customDark)
        assertEquals(customDark.primary, render(ThemeMode.Dark, colorSchemes = schemes).primary)
    }

    @Test
    fun dynamicColorAppliesOnAndroid12AndLater() {
        val scheme = render(ThemeMode.Light, dynamicColor = true)
        assertNotEquals(lightColorScheme().primary, scheme.primary)
    }

    @Test
    @Config(sdk = [30])
    fun dynamicColorFallsBackToTheBaselineBelowAndroid12() {
        assertEquals(lightColorScheme().primary, render(ThemeMode.Light, dynamicColor = true).primary)
    }

    @Test
    fun inheritingAHostThemeKeepsTheHostsPrimaryColor() {
        val host = lightColorScheme(primary = hostPrimary)
        val scheme = render(ThemeMode.Light, inheritHostTheme = true, hostScheme = host)
        assertEquals(hostPrimary, scheme.primary)
    }

    @Test
    fun inheritingAHostThemeIgnoresThemeModeColorSchemesAndDynamicColor() {
        val host = lightColorScheme(primary = hostPrimary)
        val scheme =
            render(
                themeMode = ThemeMode.Dark,
                dynamicColor = true,
                colorSchemes = RenovoColorSchemes(customLight, customDark),
                inheritHostTheme = true,
                hostScheme = host,
            )
        assertEquals(hostPrimary, scheme.primary)
    }

    @Test
    fun notInheritingIgnoresTheHostTheme() {
        val host = lightColorScheme(primary = hostPrimary)
        val scheme = render(ThemeMode.Light, inheritHostTheme = false, hostScheme = host)
        assertEquals(lightColorScheme().primary, scheme.primary)
    }

    @Test
    fun inheritingWithoutAHostThemeUsesColorSchemes() {
        val schemes = RenovoColorSchemes(customLight, customDark)
        assertEquals(customLight.primary, render(ThemeMode.Light, colorSchemes = schemes, inheritHostTheme = true).primary)
    }

    @Test
    fun inheritingWithoutAHostThemeUsesThemeMode() {
        assertEquals(darkColorScheme().primary, render(ThemeMode.Dark, inheritHostTheme = true).primary)
    }

    @Test
    fun aSchemeThatDiffersFromTheBaselineCountsAsAHostTheme() {
        assertFalse(lightColorScheme().isHostTheme())
        assertTrue(darkColorScheme().isHostTheme())
        assertTrue(lightColorScheme(primary = hostPrimary).isHostTheme())
        assertTrue(lightColorScheme(surfaceContainerLow = hostPrimary).isHostTheme())
    }

    @Test
    fun colorSchemesHaveValueEquality() {
        assertEquals(RenovoColorSchemes(customLight, customDark), RenovoColorSchemes(customLight, customDark))
        assertEquals(
            RenovoColorSchemes(customLight, customDark).hashCode(),
            RenovoColorSchemes(customLight, customDark).hashCode(),
        )
        assertNotEquals(RenovoColorSchemes(customLight, customDark), RenovoColorSchemes(customDark, customLight))
    }
}
