package io.lightine.renovo.ui.dialogs

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.core.app.ApplicationProvider
import io.lightine.renovo.ReleaseNotes
import io.lightine.renovo.ThemeMode
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.ui.RenovoTheme

/** Reads a string resource the way the screen does, so a test never repeats the English text. */
internal fun str(
    @StringRes id: Int,
    vararg args: Any,
): String = ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

internal fun sampleInfo(releaseNotes: ReleaseNotes? = ReleaseNotes("- Sync is faster")) = UpdateInfo(
    versionCode = 42,
    versionName = "1.8.0",
    apkUrl = "https://example.com/app/app-1.8.0.apk",
    releaseNotes = releaseNotes,
)

/**
 * Sets [content] under the baseline theme.
 *
 * @param inline Draws the dialog in place instead of in a dialog window.
 * @param keyboardAttached Pretends a hardware keyboard, for the keyboard hint.
 */
internal fun ComposeContentTestRule.setDialogContent(
    inline: Boolean = false,
    keyboardAttached: Boolean = false,
    content: @Composable () -> Unit,
) {
    setContent {
        val base = LocalConfiguration.current
        val configuration =
            Configuration(base).apply {
                keyboard = if (keyboardAttached) Configuration.KEYBOARD_QWERTY else Configuration.KEYBOARD_NOKEYS
            }
        RenovoTheme(themeMode = ThemeMode.Light, dynamicColor = false, colorSchemes = null, inheritHostTheme = false) {
            CompositionLocalProvider(
                LocalRenovoDialogInline provides inline,
                LocalConfiguration provides configuration,
            ) {
                content()
            }
        }
    }
}

/** This node and all of its ancestors. */
internal fun SemanticsNode.selfAndAncestors(): List<SemanticsNode> = generateSequence(this) { it.parent }.toList()

/** This node and all of its descendants. */
internal fun SemanticsNode.selfAndDescendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.selfAndDescendants() }
