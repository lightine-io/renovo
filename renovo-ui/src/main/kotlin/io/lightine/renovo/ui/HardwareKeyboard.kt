package io.lightine.renovo.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * True when a hardware keyboard is attached, so the expanded layout can show the keyboard hint line.
 *
 * It reads the configuration, which the system updates when a keyboard is attached or removed.
 */
@Composable
internal fun isHardwareKeyboardAttached(): Boolean {
    val configuration = LocalConfiguration.current
    return hasHardwareKeyboard(configuration.keyboard, configuration.hardKeyboardHidden)
}

/**
 * Applies the two configuration fields. `keyboard` says whether the device has a key input at all;
 * `hardKeyboardHidden` says whether a slide-out keyboard is closed, which counts as not attached.
 */
internal fun hasHardwareKeyboard(
    keyboard: Int,
    hardKeyboardHidden: Int,
): Boolean = keyboard != Configuration.KEYBOARD_NOKEYS && hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
