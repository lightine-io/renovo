package io.lightine.renovo.ui.forced

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

/**
 * The previews of one forced update screen in every variant: three window widths (compact 390 dp,
 * medium 700 dp, expanded 1000 dp), light and dark, English and Azerbaijani. The compact height
 * matches the phone mockups, so the permission state can be checked for "fits without scrolling".
 */
@Preview(name = "Compact 390dp - Light - en", widthDp = 390, heightDp = 844, showBackground = true)
@Preview(name = "Compact 390dp - Light - az", widthDp = 390, heightDp = 844, showBackground = true, locale = "az")
@Preview(name = "Compact 390dp - Dark - en", widthDp = 390, heightDp = 844, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Compact 390dp - Dark - az", widthDp = 390, heightDp = 844, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, locale = "az")
@Preview(name = "Medium 700dp - Light - en", widthDp = 700, heightDp = 960, showBackground = true)
@Preview(name = "Medium 700dp - Light - az", widthDp = 700, heightDp = 960, showBackground = true, locale = "az")
@Preview(name = "Medium 700dp - Dark - en", widthDp = 700, heightDp = 960, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Medium 700dp - Dark - az", widthDp = 700, heightDp = 960, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, locale = "az")
@Preview(name = "Expanded 1000dp - Light - en", widthDp = 1000, heightDp = 640, showBackground = true)
@Preview(name = "Expanded 1000dp - Light - az", widthDp = 1000, heightDp = 640, showBackground = true, locale = "az")
@Preview(name = "Expanded 1000dp - Dark - en", widthDp = 1000, heightDp = 640, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Expanded 1000dp - Dark - az", widthDp = 1000, heightDp = 640, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, locale = "az")
internal annotation class ForcedVariants
