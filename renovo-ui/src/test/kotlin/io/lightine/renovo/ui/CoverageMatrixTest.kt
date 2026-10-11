package io.lightine.renovo.ui

import android.content.res.Configuration
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.captureRoboImage
import io.lightine.renovo.DownloadProgress
import io.lightine.renovo.FailureReason
import io.lightine.renovo.ReleaseNotes
import io.lightine.renovo.ThemeMode
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.UpdateState
import io.lightine.renovo.UpdateStep
import io.lightine.renovo.ui.dialogs.LocalRenovoDialogInline
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The proof of coverage of UI specification part 4: every state of the coverage table, drawn by the
 * real [UpdateUi], at every window width, in both themes and both languages, plus the compact
 * landscape window. That is 12 x 3 x 2 x 2 = 144 images and 12 more for the landscape.
 *
 * File names: `matrix_<state>_<width>_<theme>_<language>.png`. The landscape window has the width
 * name `compact_landscape` and is drawn in the light theme and in English only, because the
 * theme and the language change colors and text, not layout.
 * `scripts/screenshot-grid.sh` lays the images out as one page.
 *
 * Record with `./gradlew :renovo-ui:recordRoborazziDebug`, check with `verifyRoborazziDebug`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class CoverageMatrixTest(
    private val case: MatrixCase,
) {
    // The empty rule does not start an activity. The window size and the language come from the
    // qualifiers, and a qualifier set after an activity started would not reach that activity.
    @get:Rule
    val rule = createEmptyComposeRule()

    private var rootView: View? = null

    @Test
    fun theStateLooksRightInThisWindow() {
        RuntimeEnvironment.setQualifiers(case.qualifiers)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { Stage() } }
            rule.waitForIdle()
            // A fresh frame for the pixel copy: without it the copy sometimes reads an empty window.
            rule.runOnUiThread { checkNotNull(rootView).invalidate() }
            rule.waitForIdle()
            rule.onRoot().captureRoboImage("src/test/screenshots/matrix_${case.fileName}.png")
        }
    }

    @Composable
    private fun Stage() {
        rootView = LocalView.current
        // A hardware keyboard on the expanded window, so the update available dialog shows its keyboard hint (design image 12).
        val configuration =
            Configuration(LocalConfiguration.current).apply {
                keyboard = if (case.width == MatrixWidth.Expanded) Configuration.KEYBOARD_QWERTY else Configuration.KEYBOARD_NOKEYS
            }
        // The baseline scheme, not dynamic color, so the image is the same on every machine.
        RenovoTheme(themeMode = case.theme.mode, dynamicColor = false, colorSchemes = null, inheritHostTheme = false) {
            CompositionLocalProvider(
                // A dialog window is a second window that the capture would not include.
                LocalRenovoDialogInline provides true,
                LocalConfiguration provides configuration,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    // The Material scrim over the host app, as in the design images. The forced screen covers the host app.
                    if (!case.scenario.forced) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA)))
                    }
                    UpdateUi(
                        state = case.scenario.state,
                        canInstallNow = { true },
                        actions = RecordedActions().actions,
                        isOnline = case.scenario.isOnline,
                        installedVersionName = INSTALLED_VERSION_NAME,
                        legacyPermissionStep = false,
                        android17PermissionStep = false,
                        dismissed = DismissedUpdates(),
                    )
                }
            }
        }
    }

    companion object {
        private const val SCRIM_ALPHA = 0.32f
        private const val INSTALLED_VERSION_NAME = "1.6.2"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val portrait =
                MatrixWidth.portraits.flatMap { width ->
                    MatrixTheme.entries.flatMap { theme ->
                        MatrixLanguage.entries.flatMap { language ->
                            MatrixScenario.entries.map { MatrixCase(it, width, theme, language) }
                        }
                    }
                }
            val landscape = MatrixScenario.entries.map { MatrixCase(it, MatrixWidth.CompactLandscape, MatrixTheme.Light, MatrixLanguage.English) }
            return (portrait + landscape).map { arrayOf(it) }
        }
    }
}

/** The window sizes: the three widths of the design images, and the compact window turned on its side. */
enum class MatrixWidth(
    val fileName: String,
    val sizeQualifiers: String,
) {
    Compact("compact", "w390dp-h844dp"),
    Medium("medium", "w700dp-h960dp"),
    Expanded("expanded", "w1000dp-h640dp"),
    CompactLandscape("compact_landscape", "w844dp-h390dp"),
    ;

    companion object {
        val portraits = listOf(Compact, Medium, Expanded)
    }
}

enum class MatrixTheme(
    val fileName: String,
    val mode: ThemeMode,
) {
    Light("light", ThemeMode.Light),
    Dark("dark", ThemeMode.Dark),
}

enum class MatrixLanguage(
    val fileName: String,
) {
    English("en"),
    Azerbaijani("az"),
}

/**
 * The 12 states of the coverage table. The forced update states are separate rows, as in the table.
 * The release notes carry both languages, as a version file does, so the dialog picks the one that
 * matches the language of the window.
 */
enum class MatrixScenario(
    val fileName: String,
    val state: UpdateState,
    val forced: Boolean = false,
    // Offline for "waiting for the user", as in design images 05, 11, and 13, so the offline note is part of the picture.
    val isOnline: Boolean = true,
) {
    UpdateAvailable("update_available", UpdateState.Available(optionalInfo)),
    Downloading("downloading", UpdateState.Downloading(optionalInfo, progress, userInitiated = true)),
    ReadyToInstall("ready_to_install", UpdateState.ReadyToInstall(optionalInfo)),
    Permission("permission", UpdateState.PermissionNeeded(optionalInfo)),
    UpdateFailed("update_failed", UpdateState.Failed(FailureReason.HashMismatch, optionalInfo, userInitiated = true)),
    ForcedWaitingForUser("forced_waiting_for_user", requiredIn(UpdateStep.WaitingForUser), forced = true, isOnline = false),
    ForcedDownloading("forced_downloading", requiredIn(UpdateStep.Downloading(progress)), forced = true),
    ForcedWaitingForNetwork("forced_waiting_for_network", requiredIn(UpdateStep.WaitingForNetwork(canUseMobileData = true)), forced = true),
    ForcedInstalling("forced_installing", requiredIn(UpdateStep.Installing), forced = true),
    ForcedFailed("forced_failed", requiredIn(UpdateStep.Failed(FailureReason.HashMismatch)), forced = true),
    ForcedPermissionNeeded("forced_permission_needed", requiredIn(UpdateStep.PermissionNeeded), forced = true),
    ForcedUnsupported("forced_unsupported", requiredIn(UpdateStep.Unsupported), forced = true),
}

/** One image of the matrix. Its text is the file name without `matrix_` and `.png`, which is also the test name. */
class MatrixCase(
    val scenario: MatrixScenario,
    val width: MatrixWidth,
    val theme: MatrixTheme,
    val language: MatrixLanguage,
) {
    val fileName: String get() = "${scenario.fileName}_${width.fileName}_${theme.fileName}_${language.fileName}"

    // The language comes first: it is the first qualifier in Android's order.
    val qualifiers: String get() = "${language.fileName}-${width.sizeQualifiers}-xhdpi"

    override fun toString(): String = fileName
}

private val progress = DownloadProgress(bytesDownloaded = 15_000_000, totalBytes = 24_000_000)

// The example of the technical specification, section 3.
private val notes =
    ReleaseNotes.of(
        "az" to "- Sinxronizasiya **sürətləndirildi**\n- Xətalar aradan qaldırıldı",
        "en" to "- Sync is **faster**\n- Bug fixes",
    )

private fun info(mandatory: Boolean) = UpdateInfo(
    versionCode = 42,
    versionName = "1.8.0",
    apkUrl = "https://example.com/app/app-1.8.0.apk",
    size = 24_000_000,
    mandatory = mandatory,
    releaseNotes = notes,
)

private val optionalInfo = info(mandatory = false)

private fun requiredIn(step: UpdateStep): UpdateState = UpdateState.Required(info(mandatory = true), step)
