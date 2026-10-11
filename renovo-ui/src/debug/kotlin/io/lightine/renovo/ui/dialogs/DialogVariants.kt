package io.lightine.renovo.ui.dialogs

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

/**
 * The previews of one dialog in every variant: three window widths (compact 360 dp, medium 700 dp,
 * expanded 1000 dp), light and dark, English and Azerbaijani. Android Studio lists the 12 under
 * the names below, so each dialog can be checked without running the app.
 */
@Preview(name = "Compact 360dp - Light - en", widthDp = 360, heightDp = 720, showBackground = true)
@Preview(name = "Compact 360dp - Light - az", widthDp = 360, heightDp = 720, showBackground = true, locale = "az")
@Preview(name = "Compact 360dp - Dark - en", widthDp = 360, heightDp = 720, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Compact 360dp - Dark - az", widthDp = 360, heightDp = 720, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, locale = "az")
@Preview(name = "Medium 700dp - Light - en", widthDp = 700, heightDp = 960, showBackground = true)
@Preview(name = "Medium 700dp - Light - az", widthDp = 700, heightDp = 960, showBackground = true, locale = "az")
@Preview(name = "Medium 700dp - Dark - en", widthDp = 700, heightDp = 960, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Medium 700dp - Dark - az", widthDp = 700, heightDp = 960, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, locale = "az")
@Preview(name = "Expanded 1000dp - Light - en", widthDp = 1000, heightDp = 640, showBackground = true)
@Preview(name = "Expanded 1000dp - Light - az", widthDp = 1000, heightDp = 640, showBackground = true, locale = "az")
@Preview(name = "Expanded 1000dp - Dark - en", widthDp = 1000, heightDp = 640, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Expanded 1000dp - Dark - az", widthDp = 1000, heightDp = 640, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, locale = "az")
internal annotation class DialogVariants
