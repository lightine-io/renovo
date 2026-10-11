package io.lightine.renovo.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.lightine.renovo.Renovo

/**
 * Adds the update UI to [activity], for a host app that does not use Compose: the dialogs and the
 * forced update screen are drawn over the activity's own views.
 *
 * Call it once in `onCreate`, after `setContentView`, and again each time the activity is created,
 * so the screens come back after a rotation. A second call for the same activity does nothing. The
 * update UI appears only in the most recently resumed activity that called this.
 *
 * Every `AppCompatActivity` is a `ComponentActivity`. A plain `Activity` is not supported, because
 * Compose needs the lifecycle owners that `ComponentActivity` provides.
 *
 * There is no Compose theme to inherit, so the colors come from `ui { }` in `Renovo.init`, then
 * [colorSchemes], then dynamic color on Android 12 and later, then the Material 3 baseline.
 *
 * ```
 * class MainActivity : AppCompatActivity() {
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         setContentView(R.layout.main)
 *         Renovo.attachUi(this)
 *     }
 * }
 * ```
 *
 * @param activity The activity to draw in.
 * @param colorSchemes A light and a dark scheme for the update UI, or null for dynamic color or the baseline.
 */
public fun Renovo.attachUi(
    activity: ComponentActivity,
    colorSchemes: RenovoColorSchemes? = null,
) {
    // The view is found by its id, so the check also holds when the caller keeps no reference.
    if (activity.findViewById<View>(R.id.renovo_ui_host) != null) return

    val view =
        ComposeView(activity).apply {
            // A fixed id: the view saves its screen-local state under it, so a dismissed dialog stays
            // dismissed after a rotation only when the new view has the id of the old one.
            id = R.id.renovo_ui_host
            // The default strategy disposes the composition when the view detaches from the window.
            // That would drop the screen-local state on every detach. The activity's lifecycle is
            // the right owner for a view that lives as long as the activity.
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RenovoUpdateUi(colorSchemes = colorSchemes, inheritHostTheme = false) }
        }
    activity.addContentView(
        view,
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
    )
}
