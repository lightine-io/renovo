# Renovo

[![Maven Central](https://img.shields.io/maven-central/v/io.lightine.renovo/renovo-core?label=Maven%20Central)](https://central.sonatype.com/artifact/io.lightine.renovo/renovo-core)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![check](https://github.com/lightine-io/renovo/actions/workflows/check.yml/badge.svg)](https://github.com/lightine-io/renovo/actions/workflows/check.yml)

Renovo lets an Android app that is installed from a direct APK, outside the Play Store, check for a new version, download it, verify it, and install it by itself.

Renovo is for apps that are **not** distributed on the Play Store. It installs one complete APK, not split APKs or app bundles.

## Quick start

Add the two dependencies. `renovo-core` has no screens. `renovo-ui` adds ready-made Jetpack Compose screens and brings `renovo-core` with it.

```kotlin
implementation("io.lightine.renovo:renovo-core:0.1.0")
implementation("io.lightine.renovo:renovo-ui:0.1.0")
```

Start Renovo in `Application.onCreate`. The host app (the app that uses Renovo) gives it the address of the version file (a small JSON document that describes the latest version, see [The version file](#the-version-file)).

```kotlin
override fun onCreate() {
    super.onCreate()
    Renovo.init(this) { versionUrl = "https://example.com/app/version.json" }
}
```

Wrap your Compose content in `RenovoUpdateHost`:

```kotlin
setContent { AppTheme { RenovoUpdateHost { AppContent() } } }
```

If the host app does not use Compose, call this in `onCreate` after `setContentView` instead. The activity must be a `ComponentActivity`; every `AppCompatActivity` is one.

```kotlin
Renovo.attachUi(this)
```

That is all. What happens next:

- Renovo checks for a new version when the host app starts, and again every six hours while a network is available.
- When a newer version exists, the update UI (the ready-made screens in `renovo-ui`) asks the user to update. Renovo downloads the APK, and the download resumes after any interruption.
- Renovo checks the file, then asks the user to install it. The user allows installs from the host app once, and the update UI guides them through it.
- When the version file marks an update as forced (a forced update is one the user cannot postpone), the update UI blocks the host app until it is installed.

## Contents

- [How it works](#how-it-works)
- [The version file](#the-version-file)
- [Settings](#settings)
- [The update state](#the-update-state)
- [The update UI](#the-update-ui)
- [Notifications](#notifications)
- [What Renovo adds to your manifest](#what-renovo-adds-to-your-manifest)
- [Behavior per Android version](#behavior-per-android-version)
- [Known limits](#known-limits)
- [Google developer verification](#google-developer-verification)
- [Requirements](#requirements)
- [Testing the integration](#testing-the-integration)
- [Versioning, changelog, license](#versioning-changelog-license)

## How it works

1. **Check.** Renovo asks the version file for the latest version. It does this when the host app starts, every `checkInterval` in the background (WorkManager, the Android library for deferrable background work, runs the job), and when you call `Renovo.checkNow()`. It sends the ETag from the last answer (a tag the server attaches to a file version), so an unchanged file costs almost no data.
2. **Download.** If the version is newer than the installed one, Renovo downloads the APK into the host app's private storage. An interrupted download resumes where it stopped. The same version is never downloaded twice. Files of older versions are deleted.
3. **Verify.** Before anything is installed, Renovo checks, in this order: the SHA-256 hash (when the version file gives one), the package name, the version code, and the signing certificate.
4. **Install.** Renovo hands the APK to the Android package installer. Android shows its own confirmation unless it can skip it (see [Behavior per Android version](#behavior-per-android-version)). An install always starts from the foreground, never from a background job. After the update, the new version deletes the downloaded file and schedules the next check.

What Renovo guarantees:

- **Same publisher.** Only an APK with the same package name and the same signing certificate as the installed app is installed. A compromised server cannot make Renovo install an APK from another publisher.
- **Intact file.** When the version file has a `sha256`, a file with a different hash is never installed. A file that fails any check is deleted and reported with a specific reason.
- **Never a downgrade.** Only a higher version code is accepted.
- **Never at a bad moment.** Renovo asks `canInstallNow` right before every install. While it returns `false`, nothing is installed.
- **Data is safe.** A failed or cancelled install leaves the current version running with its data. Migrating stored data between versions is the host app's job.
- **Forced updates hold.** A device that skipped several releases and is below `minSupportedVersionCode` is still blocked.
- **HTTPS only.** Renovo rejects a version file URL or APK URL that is not HTTPS, and a redirect away from HTTPS. The server needs a certificate that the device trusts.
- **No data sent.** Renovo makes no request except the one for the version file and the one for the APK. It has no analytics.

## The version file

The version file is a JSON document on any HTTPS host. Renovo needs no server logic: a static file is enough.

```json
{
  "versionCode": 42,
  "versionName": "1.8.0",
  "apkUrl": "https://example.com/app/app-1.8.0.apk",
  "sha256": "9f2c...e1",
  "size": 25165824,
  "mandatory": false,
  "minSupportedVersionCode": 38,
  "releaseNotes": {
    "az": "- Sinxronizasiya **sürətləndirildi**\n- Xətalar aradan qaldırıldı",
    "en": "- Sync is **faster**\n- Bug fixes"
  }
}
```

| Field | Required | Meaning |
|---|---|---|
| `versionCode` | yes | Version code of the latest APK. A whole number of 1 or more. |
| `versionName` | yes | Version name shown to the user. A string. |
| `apkUrl` | yes | HTTPS URL of the APK. |
| `sha256` | no | SHA-256 hash of the APK, 64 hexadecimal characters. Checked when present. |
| `size` | no | APK size in bytes. Used for the progress display. A positive number. |
| `mandatory` | no, default `false` | `true` makes this update a forced update, one the user cannot postpone. |
| `minSupportedVersionCode` | no | Installed versions below this version code are forced to update. |
| `releaseNotes` | no | One string, or an object with one string per language code. |

The rules:

- **Forced updates.** An update is forced when `mandatory` is `true`, or when the installed version code is below `minSupportedVersionCode`. The second rule works even when the latest version is not marked `mandatory`.
- **Release notes per language.** Renovo picks the device language, then `en`, then the first entry. A single string is used for every language.
- **Markdown subset.** Release notes may use `**bold**`, `*italic*`, lines that start with `- ` as bullets, `[text](https://...)` links, and line breaks. Everything else is shown as typed. Links open only if they are HTTPS.
- **Unknown fields are ignored**, so the format can grow.
- **A file that cannot be read** (broken JSON, a missing required field, a plain HTTP `apkUrl`) is a failed check, not a crash. Renovo retries it later.
- **HTTPS only**, for the version file and for the APK. The server may redirect, as long as every step is HTTPS.
- **Add the hash.** Without `sha256`, Renovo still checks the package name, the version code, and the signing certificate. The hash adds a check that the file is exactly the one you published. Make it with `shasum -a 256 app-1.8.0.apk`.

## Settings

All settings go in the block of `Renovo.init`. Only the version location is required: set exactly one of `versionUrl` and `versionSource`. Invalid settings throw `IllegalArgumentException`. A second call to `init` is ignored.

| Setting | Default | What it does |
|---|---|---|
| `versionUrl` | none | HTTPS URL of the version file. `versionUrl = "https://example.com/app/version.json"` |
| `versionSource` | none | Your own source of version information, instead of a version file. See [Your own version source](#your-own-version-source). |
| `versionHeaders` | no headers | Extra headers for the version file request. `versionHeaders = { mapOf("Authorization" to "Bearer ${tokens.current()}") }` |
| `checkInterval` | `6.hours` | Time between background checks. The minimum is 15 minutes. `checkInterval = 12.hours` |
| `autoDownload` | `true` | `true` downloads a newer version without waiting for the user. `false` waits for `Renovo.startDownload()`. `autoDownload = false` |
| `optionalDownloadNetwork` | `Network.Any` | Networks an optional update may download on: `Any`, `UnmeteredOnly`, or `NotRoaming`. `optionalDownloadNetwork = Network.UnmeteredOnly` |
| `forcedDownloadNetwork` | `Network.Any` | The same choice for forced updates. `forcedDownloadNetwork = Network.NotRoaming` |
| `allowMobileDataOverride` | `true` | A forced update that waits for an allowed network lets the user download with mobile data instead. `allowMobileDataOverride = false` |
| `notifications` | `true` | Shows the "update ready" notification, only when notifications are already allowed. `notifications = false` |
| `canInstallNow` | `{ true }` | Asked right before every install. Return `false` while an install would lose work. `canInstallNow = { !repository.hasUnsentData() }` |
| `logger` | `null` (silent) | Receives Renovo's diagnostic messages. `logger = RenovoLogger { message, error -> Log.d("Renovo", message, error) }` |
| `ui { }` | see [The update UI](#the-update-ui) | Theme settings for the update UI. `ui { themeMode = ThemeMode.Dark }` |

`Network.Any` is any connected network, including mobile data. `Network.UnmeteredOnly` is a network that is not billed by usage, such as most Wi-Fi. `Network.NotRoaming` is any network except mobile data while roaming.

A complete example with every setting:

```kotlin
import io.lightine.renovo.Network
import io.lightine.renovo.Renovo
import io.lightine.renovo.RenovoLogger
import io.lightine.renovo.ThemeMode
import kotlin.time.Duration.Companion.hours

Renovo.init(this) {
    versionUrl = "https://example.com/app/version.json"
    versionHeaders = { mapOf("Authorization" to "Bearer ${tokens.current()}") }
    checkInterval = 12.hours
    autoDownload = true
    optionalDownloadNetwork = Network.UnmeteredOnly
    forcedDownloadNetwork = Network.Any
    allowMobileDataOverride = true
    notifications = true
    canInstallNow = { !repository.hasUnsentData() }
    logger = RenovoLogger { message, error -> Log.d("Renovo", message, error) }
    ui {
        themeMode = ThemeMode.System
        dynamicColor = true
    }
}
```

### Your own version source

If your team already has an API that returns the latest version, use it instead of a version file. Set `versionSource` instead of `versionUrl`. Renovo calls it on a background thread for every check. Throw to report a failed check: an `IOException` counts as a network failure, anything else as version information that could not be read. Renovo then retries with increasing delays.

```kotlin
Renovo.init(this) {
    versionSource = VersionSource {
        val r = api.latestVersion()
        UpdateInfo(
            versionCode = r.build,
            versionName = r.name,
            apkUrl = r.downloadUrl,
            mandatory = r.force,
            minSupportedVersionCode = r.minBuild,
            releaseNotes = ReleaseNotes.of("az" to r.notesAz, "en" to r.notesEn),
        )
    }
}
```

`UpdateInfo` holds the same fields as the version file (`sha256` and `size` are optional). Its constructor throws `IllegalArgumentException` for a value that is not allowed, such as an `apkUrl` that is not HTTPS or a `sha256` that is not 64 hexadecimal characters.

`ReleaseNotes("- Bug fixes")` creates notes in one language. `ReleaseNotes.of("az" to "...", "en" to "...")` creates notes per language; the first entry is the last fallback. `notes.forLanguage("az")` returns the text Renovo would pick.

### Hand over version information

If the host app already receives version information, for example in its own sync response, pass it to Renovo. `submit` has the same effect as a successful check, and no separate request is made.

```kotlin
Renovo.submit(UpdateInfo(42, "1.8.0", "https://example.com/app/app-1.8.0.apk"))
```

### A version file that needs authentication

Use `versionHeaders`. Renovo calls it before every request to the version file, so a token that changes is always current. The headers go to the version file only, and Renovo drops them if the server redirects to another host. The APK request carries no custom headers, so `apkUrl` must work without them, for example as a signed URL.

```kotlin
versionHeaders = { mapOf("Authorization" to "Bearer ${tokens.current()}") }
```

### Choose when an install may start

`canInstallNow` runs on the main thread right before every install. If it returns `false`, the update stays in the `ReadyToInstall` state. Renovo asks again at the next app start and at the next `Renovo.installIfReady()`. The update UI shows its "Ready to install" dialog only while `canInstallNow` returns `true`.

```kotlin
canInstallNow = { !repository.hasUnsentData() }
```

### Logging

Logging is off until you set a `logger`. Messages never contain personal data. A logger that throws cannot break Renovo.

```kotlin
logger = RenovoLogger { message, error -> Log.d("Renovo", message, error) }
```

### Actions

Each call has the effect its name says.

| Call | What it does | Example |
|---|---|---|
| `Renovo.checkNow()` | Checks for a new version now. | `checkButton.setOnClickListener { Renovo.checkNow() }` |
| `Renovo.startDownload()` | Starts the download of the available version, or tries again after a failure. If the file is already downloaded and verified, it starts the install. | `updateButton.setOnClickListener { Renovo.startDownload() }` |
| `Renovo.installIfReady()` | Installs the downloaded and verified version after asking `canInstallNow`. When the user must first allow installs, it opens that setting. | `installButton.setOnClickListener { Renovo.installIfReady() }` |
| `Renovo.submit(info)` | Hands over version information you already have. | `Renovo.submit(info)` |
| `Renovo.useMobileDataOnce()` | Lets a waiting forced update download with mobile data, for this version only. | `mobileDataButton.setOnClickListener { Renovo.useMobileDataOnce() }` |
| `Renovo.requestNotificationPermission(activity)` | Asks the user to allow notifications. See [Notifications](#notifications). | `Renovo.requestNotificationPermission(this)` |

## The update state

`Renovo.state` is a `StateFlow<UpdateState>`. It is process-wide, so it survives every screen. The update UI reads it, and a headless host app (one that builds its own screens) reads it too.

```kotlin
lifecycleScope.launch {
    Renovo.state.collect { state ->
        when (state) {
            is UpdateState.Available -> offerUpdate(state.info)
            is UpdateState.ReadyToInstall -> offerInstall(state.info)
            is UpdateState.Required -> blockApp(state.info, state.step)
            else -> Unit
        }
    }
}
```

Without coroutines, use the listener. It is called on the main thread, once with the current state when you add it, and after every change.

```kotlin
val listener = UpdateListener { state -> render(state) }
Renovo.addListener(listener)
// later, for example in onDestroy:
Renovo.removeListener(listener)
```

| State | Meaning |
|---|---|
| `Idle` | Nothing is known yet. No check has finished since the app started. |
| `Checking` | A check is running and no update is known yet. |
| `UpToDate` | The installed version is the latest. |
| `Available(info)` | A newer optional version exists and waits for `Renovo.startDownload()`. |
| `Downloading(info, progress, userInitiated)` | The file is downloading. `userInitiated` is `true` when the user started it. The update UI shows the download only then. |
| `WaitingForNetwork(info)` | The download waits for a network that your settings allow. |
| `ReadyToInstall(info)` | The file is downloaded and verified. `Renovo.installIfReady()` installs it. |
| `PermissionNeeded(info)` | The user must allow this app to install updates. `Renovo.installIfReady()` opens the setting. |
| `Installing(info)` | The update is with the system installer. |
| `Failed(reason, info, userInitiated)` | A check, download, or install failed. `info` is `null` when a check failed before any version was known. The update UI shows the failure only when `userInitiated` is `true`. |
| `Unsupported` | This device does not allow the app to update itself. Nothing is tried until the installed version changes. |
| `Required(info, step)` | A forced update exists. It stays set through download and install, and `step` says what is happening. |

`FailureReason` says why something failed:

| Reason | Meaning |
|---|---|
| `Network` | No connection, or the server could not be reached. |
| `Parse` | The version information could not be read. |
| `HashMismatch` | The downloaded file is not the one the version information describes: the SHA-256 hash differs, or the APK has another version code than announced. |
| `SignatureMismatch` | The file is from a different publisher or a different app. |
| `NotNewer` | The downloaded file is not newer than the installed version. |
| `Storage` | Not enough free space, or the file could not be written or read. |
| `InstallRejected` | The system rejected the install. |
| `Cancelled` | The user cancelled the install. |

### Forced updates and `UpdateStep`

A forced update is always `Required`, whatever it is doing. Its `step` is an `UpdateStep`:

| Step | Meaning |
|---|---|
| `WaitingForUser` | Waiting for the user to start, by calling `Renovo.startDownload()` or `Renovo.installIfReady()`. The file may already be downloaded. |
| `Downloading(progress)` | The file is downloading. |
| `WaitingForNetwork(canUseMobileData)` | The download waits for an allowed network. When `canUseMobileData` is `true`, `Renovo.useMobileDataOnce()` downloads over mobile data now. |
| `PermissionNeeded` | The user must allow installs. `Renovo.installIfReady()` opens the setting. |
| `Installing` | The update is with the system installer. |
| `Failed(reason)` | The last attempt failed. `Renovo.startDownload()` tries again. |
| `Unsupported` | This device does not allow the app to update itself. |

`DownloadProgress` has `bytesDownloaded` and `totalBytes`. `totalBytes` is `null` while the size is not known.

A headless host app that blocks its own screen during a forced update:

```kotlin
Renovo.state.collect { state ->
    val required = state as? UpdateState.Required
    if (required == null) {
        hideBlockingScreen()
        return@collect
    }
    when (val step = required.step) {
        UpdateStep.WaitingForUser -> showUpdateButton { Renovo.startDownload() }
        is UpdateStep.Downloading -> showProgress(step.progress.bytesDownloaded, step.progress.totalBytes)
        is UpdateStep.WaitingForNetwork -> showWaiting(step.canUseMobileData) { Renovo.useMobileDataOnce() }
        UpdateStep.PermissionNeeded -> showAllowInstallsButton { Renovo.installIfReady() }
        UpdateStep.Installing -> showInstalling()
        is UpdateStep.Failed -> showRetryButton(step.reason) { Renovo.startDownload() }
        UpdateStep.Unsupported -> showAskTheInstallerText()
    }
}
```

## The update UI

The update UI is the optional `renovo-ui` module. It draws the screens that belong to the current state. A host app can skip it and build its own screens on the state above.

### Two entry points

For Compose, put `RenovoUpdateHost` around your content. It draws the content, and the update UI over it.

```kotlin
setContent {
    AppTheme {
        RenovoUpdateHost { AppContent() }
    }
}
```

For apps without Compose, call `Renovo.attachUi(activity)` once in `onCreate`, after `setContentView`, and call it again every time the activity is created, so the screens come back after a rotation. A second call for the same activity does nothing.

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        Renovo.attachUi(this)
    }
}
```

`attachUi` needs a `ComponentActivity`, because Compose needs the lifecycle owners it provides. A plain `android.app.Activity` is not supported.

If the host app has several windows or activities, the update UI appears in the most recently resumed activity that has a host, and only there.

### Theme

- **`RenovoUpdateHost` inherits your theme.** Inside your `MaterialTheme`, the update UI uses your colors, typography, and shapes, including your choice of light or dark. `ui { themeMode }` and `ui { dynamicColor }` are then ignored, because your theme already decided.
- **`colorSchemes` replaces the inherited theme.** Pass a light and a dark Material 3 color scheme. The `ui { }` settings then choose between them.
- **`attachUi` has no theme to inherit.** It uses `ui { themeMode }` to choose light or dark, then your `colorSchemes` if you passed them, then dynamic color on Android 12 and later, then the Material 3 baseline colors.
- One blind spot: a host theme that uses exactly the Material 3 baseline light scheme looks like no theme at all. The update UI then follows the `ui { }` settings, and the result is the baseline colors unless dynamic color applies.

```kotlin
val schemes = RenovoColorSchemes(
    light = lightColorScheme(primary = Color(0xFF6750A4)),
    dark = darkColorScheme(primary = Color(0xFFD0BCFF)),
)

RenovoUpdateHost(colorSchemes = schemes) { AppContent() }
// or, without Compose:
Renovo.attachUi(this, colorSchemes = schemes)
```

The `ui { }` block in `Renovo.init`:

| Setting | Default | What it does |
|---|---|---|
| `themeMode` | `ThemeMode.System` | `System` follows the device, `Light` and `Dark` force one. `ui { themeMode = ThemeMode.Light }` |
| `dynamicColor` | `true` | Uses the wallpaper colors on Android 12 and later. `ui { dynamicColor = false }` |

### The screens

| Screen | When it shows | Dismiss |
|---|---|---|
| Update available | State is `Available`. Shows the release notes, version, and size. | "Later" hides it until the next app start. "Update" starts the download. |
| Downloading | State is `Downloading` with `userInitiated` true. A background download shows nothing. | "Hide" hides it. The download continues. |
| Ready to install | State is `ReadyToInstall` and `canInstallNow` returns `true`. | "Not now" hides it until the next app start. "Install" installs. |
| First-time permission | State is `PermissionNeeded`. Explains the Android setting in three steps. | "Cancel" hides it until the next app start. "Open settings" opens the setting, and the update continues when the user comes back. |
| Update not installed | State is `Failed` with `userInitiated` true. Shows the reason. A failed background check shows nothing. | "Close" hides it. "Try again" retries. |
| Forced update | State is `Required`. A full screen over the host app: download progress, waiting for a network, the permission steps, a failure with "Try again", or the unsupported-device text. | **Cannot be dismissed.** Back and Esc do nothing. |
| Unsupported device | State is `Unsupported` during a forced update. It replaces the body of the forced screen. In the optional case nothing shows. | Part of the forced screen. |

A choice such as "Later" is kept for the version it was made for. A newer version asks again. Rotating, resizing, folding, or unfolding never brings back a screen the user put away, and never restarts a download.

### Languages, window sizes, and keyboard

- **Languages.** Every text is in English and Azerbaijani, and follows the device language. Other languages show English.
- **Window sizes.** The layout follows the window width, not the device type. On a compact window (phone) dialogs fill the width minus margins, and the forced screen has one column that scrolls when the window is short. On a medium window (tablet in portrait) dialogs are 440dp wide and the forced screen is one centered column. On an expanded window (tablet in landscape, desktop window) dialogs are at most 560dp wide and the forced screen has two columns. Nothing is locked to one orientation.
- **Keyboard and mouse.** Every screen works with a keyboard only. Focus order is logical. Enter activates the primary action and Esc activates the dismiss action. On the forced update screen, Esc and Back do nothing.
- **Accessibility.** Touch targets are at least 48dp, and the progress indicator announces its value.

The repository has a Compose preview for every screen in `renovo-ui/src/debug`, in light and dark, in both languages, and at the three window sizes. They are for contributors and are not part of the published library.

## Notifications

- **When.** When a download becomes ready to install and no activity of the host app is in the foreground, Renovo shows one "Update ready" notification. Tapping it opens the host app's launcher activity, and the update UI shows its "Ready to install" screen. The notification never installs directly, because an install must start from the foreground. Renovo removes it when the update is installed or replaced by a newer version.
- **Only when allowed.** Renovo shows the notification only if the setting `notifications` is `true` and Android already allows notifications for the host app. If not, nothing is shown, and the install is offered at the next app start.
- **Renovo never asks for the permission on its own.** On Android 13 and later, notifications need a runtime permission. Your app picks the moment to ask, with `Renovo.requestNotificationPermission(activity)`. On Android 12L and earlier, it does nothing.

```kotlin
Renovo.requestNotificationPermission(this)
```

- **A download progress notification.** A download the user starts runs as a foreground job, and Android requires a visible notification for it. That one is shown by the system's rules and is not controlled by the `notifications` setting.
- **Replace the icons.** The small icons are two drawables in `renovo-core`: `renovo_ic_update_ready` (update ready) and `renovo_ic_download` (download progress). To use your own, define a drawable with the same name in the host app, for example `res/drawable/renovo_ic_update_ready.xml`. Notification small icons are drawn as a mask, so only the shape matters. `renovo-ui` also uses `renovo_ic_download` as the download icon in its dialogs and on the forced update screen, so replacing it changes that icon too.
- **Languages.** The channel names and texts are in English and Azerbaijani, and follow the device language.

## What Renovo adds to your manifest

Renovo merges these permissions and components into the host app's manifest. You declare nothing yourself. Check the merged manifest in Android Studio (Merged Manifest tab) if you need to.

| Permission | Added by | Why |
|---|---|---|
| `INTERNET` | `renovo-core` | To fetch the version file and the APK. |
| `ACCESS_NETWORK_STATE` | `renovo-core`, `renovo-ui` | To honor the network setting for downloads. The update UI also uses it to show the offline note on the forced update screen. |
| `REQUEST_INSTALL_PACKAGES` | `renovo-core` | Android 8 and later require it to install an APK from outside a store. The user still has to allow the host app. |
| `UPDATE_PACKAGES_WITHOUT_USER_ACTION` | `renovo-core` | Lets a self-update skip the confirmation prompt on Android 12 and later when Android's conditions are met. A normal permission. |
| `FOREGROUND_SERVICE_DATA_SYNC` | `renovo-core` | Android 14 and later require it for the foreground job that runs a download the user started. |
| `POST_NOTIFICATIONS` | `renovo-core` | So `Renovo.requestNotificationPermission` can be granted on Android 13 and later. Without the declaration the request could never succeed. Renovo never asks on its own. |

WorkManager, which Renovo uses to run its background jobs, adds three more permissions of its own: `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` (to reschedule the periodic check after a restart), and `FOREGROUND_SERVICE`.

| Component | Why |
|---|---|
| `SystemForegroundService` (WorkManager's), with type `dataSync` | Runs the download the user started. |
| `InstallStatusReceiver` (not exported) | Receives the result from the Android installer, through an explicit `PendingIntent`. |
| `PackageReplacedReceiver` (not exported) | Android sends `MY_PACKAGE_REPLACED` to the replaced app only. Renovo uses it to clean up after an update. |

Both libraries ship R8 and ProGuard consumer rules, so a host app with code shrinking needs no extra rules.

## Behavior per Android version

Renovo supports Android 7.0 (API 24) and later. It handles the differences for you. This table says what you will see.

| Android version | What changes |
|---|---|
| 7.0 and 7.1 (API 24-25) | "Install from unknown sources" is one device-wide setting. Renovo opens the security settings screen and the update UI shows a matching step. The user always confirms the install. |
| 8.0 and later (API 26+) | Each app has its own "install unknown apps" permission. Renovo checks it before every install, because the user can take it back, and opens the settings screen for this app. |
| 12 and later (API 31+) | A self-update can skip the confirmation prompt. See the conditions below. Renovo always handles the fallback to a prompt. |
| 13 and later (API 33+) | Notifications need a runtime permission. Renovo never blocks an update on it. |
| 14 and later (API 34+) | The foreground job for a user-started download needs the `dataSync` type and its permission. Renovo declares both. Renovo does not use install constraints ("gentle update") or claim update ownership: every install starts from a tap in the foreground. |
| 15 and later (API 35+) | Edge-to-edge is enforced: apps draw behind the system bars. The update UI handles the window insets. |
| 16 and later (API 36+) | Google's developer verification can stop an install. The result arrives from the installer with a reason, and Renovo reports it as `InstallRejected`. See [Google developer verification](#google-developer-verification). |
| 17 | Advanced Protection Mode, an optional Android setting for high-risk users, blocks installs from unknown sources. Renovo reports the device as unsupported. |

**When a self-update is silent (Android 12 and later).** Android skips the prompt only if all of these hold. Renovo requests it, and Android decides:

- the host app holds `UPDATE_PACKAGES_WITHOUT_USER_ACTION` (Renovo declares it),
- the host app is updating itself, and the user has allowed installs from it,
- the new APK's target SDK is at or above a floor that rises with each Android release,
- nothing else needs the user, such as a different update owner or a recent earlier silent update.

The target SDK floor of the new APK:

| Device runs | The new APK must target |
|---|---|
| Android 12 | API 29 or higher |
| Android 13 | API 30 or higher |
| Android 14 | API 31 or higher |
| Android 15 | API 33 or higher |
| Android 16 | API 34 or higher |
| Android 17 | API 35 or higher |

If any condition fails, Android shows the confirmation screen and Renovo carries on from there. Plan for the prompt on every device.

**Devices that cannot install.** If the device has no way to grant the permission, a policy forbids installs from unknown sources, or the installer rejects the install as not allowed, Renovo sets `Unsupported` and stops trying until the next version of the host app is installed.

## Known limits

These are Android limits on devices that are not centrally managed. Renovo cannot remove them.

- **A fully silent install is not always possible.** The user must allow installs from the host app once. After that, Android 12 and later can install without a prompt under the conditions above, but older versions and some manufacturers always ask for confirmation.
- **The system decides when background work runs.** An app that the user force-stopped runs nothing. The check on app start covers this.
- **No downgrades.** Android does not allow them. A bad release is fixed by shipping a higher version code.
- **Developer verification is the owner's job.** Google's developer verification needs the host app's owner to register its package name and signing key. Renovo cannot do this for you. See [Google developer verification](#google-developer-verification).
- **Data migration is yours.** The host app migrates its own stored data between versions.
- **Play Protect may warn.** Google Play Protect may show a warning when an app installs an APK from outside a store. Renovo cannot suppress it.
- **Updates keep your release process.** Every APK must be signed with the same key as the installed app, or Android refuses it. Renovo checks this first so it can give a clear reason.

### Local update servers on Android 17

An app that targets Android 17 (API 37) or higher cannot reach a private network address, such as `10.0.2.2` or a LAN address like `192.168.1.20`, without the `ACCESS_LOCAL_NETWORK` permission. The request times out. If the host app fetches its version file or APK from such an address, for example in a test setup with a server on a developer machine, it must declare the permission and, where the platform asks, request it at run time:

```xml
<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />
```

Renovo does not add this permission, because public HTTPS hosts do not need it. See Android's [local network permission](https://developer.android.com/privacy-and-security/local-network-permission) page.

### Out of scope

Renovo does not do these, and will not:

- **Apps that are also on the Play Store.** Renovo adds the permission to install packages, which Play policy restricts.
- **Split APKs and app bundles.** Renovo installs one complete APK. Publish a universal APK.
- **Updating other apps.** Renovo updates the host app only.
- **Rollback** to an earlier version.
- **Delta or patch updates.** Renovo downloads the full APK.
- **Device management** features.
- **Hosting** the version file or the APK. You provide a server.

## Google developer verification

Google is rolling out developer verification for apps on certified Android devices. A developer registers with Google, and registers each app's package name and signing key. On a device where verification is enforced, an app from an unregistered developer fails to install, and an update to an unregistered app fails too.

Google's schedule at the time of writing: protections began on 30 September 2026 for apps installed from participating stores in Brazil, Indonesia, Singapore, and Thailand on certified devices running Android 7 or later. Verification expands to all apps on certified devices in 2027 and later. Google's page has the current dates and the exceptions, such as limited distribution accounts for students and hobbyists.

What it means for you:

- **The owner of the host app must register** the package name and the signing key, as a developer who distributes outside the Play Store. Renovo cannot do it for them. Do it before you ship the first Renovo update.
- Renovo reports a verification failure as `FailureReason.InstallRejected`, and writes the reason to your `logger`.
- Google's page: [developer.android.com/developer-verification](https://developer.android.com/developer-verification).

## Requirements

| What | Value |
|---|---|
| Android | `minSdk` 24 (Android 7.0) or higher |
| `compileSdk` of the host app | 37 or higher. The libraries' dependencies require it, and Android Gradle Plugin stops the build otherwise. |
| Kotlin | 2.4.0 or higher. See below. |
| Java | The class files are Java 17 bytecode. Set the host app's `sourceCompatibility` and `targetCompatibility` to Java 17 or higher, so Java code in the host app can call Renovo. |
| Not on Play | Renovo is for apps that are not distributed on the Play Store. |

**Kotlin version.** Renovo is compiled with Kotlin 2.4.21, so its class files carry Kotlin metadata version 2.4. Kotlin documents compatibility in its [evolution principles](https://kotlinlang.org/docs/kotlin-evolution-principles.html) like this: a newer compiler reads the binaries of older ones, and a compiler can usually, but with no guarantee, read the binaries of the next language release, not later ones. So the host app must build with **Kotlin 2.4.0 or higher**. Kotlin 2.3 can usually read Renovo, but Kotlin does not guarantee it. Kotlin 2.2 and older are not supported. With Compose, keep the Kotlin Compose compiler plugin at the same version as Kotlin, as the Compose documentation asks.

`renovo-core` depends on WorkManager, `androidx.core`, and kotlinx.coroutines, which most apps already have. `renovo-ui` adds Jetpack Compose, Material 3, `androidx.activity`, and the Compose window size libraries.

**Call `Renovo.init` in the main process only.** `Application.onCreate` runs in every process of the host app, such as a `:remote` service or a crash reporter. Each process would build its own Renovo and write to the same stored state, and the processes would overwrite each other's records. If the host app has more than one process, check the name:

```kotlin
val me = getSystemService(ActivityManager::class.java).runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }
if (me?.processName == packageName) Renovo.init(this) { versionUrl = "https://example.com/app/version.json" }
```

On Android 9 and later, `Application.getProcessName() == packageName` does the same.

## Testing the integration

The `sample` app updates itself from version 1 to version 2, and the `test-server` module serves a version file and an APK from your machine over HTTPS, with `Range` and `If-None-Match` support and a switch that cuts a download in the middle to test resume. Use the two together on an emulator to see the whole flow. Read [`sample/README.md`](sample/README.md) for the sample and [`test-server/README.md`](test-server/README.md) for the server. The sample's debug build trusts the test server's self-signed certificate. Your release build must not.

## Versioning, changelog, license

- **Versioning.** Renovo follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html). `renovo-core` and `renovo-ui` always have the same version, so use the same version for both. The public API is checked for accidental breaking changes between releases.
- **Changelog.** [`CHANGELOG.md`](CHANGELOG.md) lists every change.
- **API reference.** Every public class, function, and setting has a description and an example in its KDoc. Build the reference with Dokka: `./gradlew dokkaGenerate`. The HTML pages are in `renovo-core/build/dokka/html` and `renovo-ui/build/dokka/html`.
- **Security.** Report a vulnerability as described in [`SECURITY.md`](SECURITY.md).
- **License.** Apache License 2.0. See [`LICENSE`](LICENSE). Copyright Asker Asadov (Lightine).
