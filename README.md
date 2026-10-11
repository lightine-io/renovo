# Renovo

[![Maven Central](https://img.shields.io/maven-central/v/io.lightine.renovo/renovo-core?label=Maven%20Central)](https://central.sonatype.com/artifact/io.lightine.renovo/renovo-core)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![check](https://github.com/lightine-io/renovo/actions/workflows/check.yml/badge.svg)](https://github.com/lightine-io/renovo/actions/workflows/check.yml)

Renovo is a Kotlin library for Android apps that are installed from an APK file, outside the Play Store. With Renovo, such an app checks for a new version, downloads it, verifies it, and installs it by itself. You need no server logic: a small JSON file on any HTTPS host, the version file, is enough. Renovo is **not** for apps on the Play Store, and it installs one complete APK, not split APKs or app bundles.

## Quick start

**1. Add the dependency** to the app module's `build.gradle.kts`. Renovo is on Maven Central. `renovo-ui` brings `renovo-core` with it.

```kotlin
dependencies {
    implementation("io.lightine.renovo:renovo-ui:0.1.0")
}
```

**2. Start Renovo and show the update UI**, the ready-made update screens. Two pieces of code: one in your `Application` class, one in your main activity.

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Renovo.init(this) { versionUrl = "https://example.com/app/version.json" }
    }
}

// In MainActivity.onCreate, around your existing Compose content:
setContent { AppTheme { RenovoUpdateHost { AppContent() } } }
```

The imports are `io.lightine.renovo.Renovo` and `io.lightine.renovo.ui.RenovoUpdateHost`. Register `App` in `AndroidManifest.xml` with `<application android:name=".App" ...>` if it is not there yet. No Compose in your app? See [Update UI in a Views app](#b-update-ui-in-a-views-or-appcompat-app).

**3. Publish the version file** at that URL. It is a JSON document that describes the latest version. See [The version file](#the-version-file).

What happens next: Renovo checks the version file when the app starts, and every six hours in the background. When a newer version exists, Renovo downloads the APK in the background, and an interrupted download resumes where it stopped. Renovo then verifies the file and shows a "Ready to install" dialog. The first time, Android asks the user to allow installs from your app, and the dialog explains how. If the version file marks the update as a forced update, one the user cannot postpone, a full screen blocks the app until the user installs it.

## Contents

- [Words used in this guide](#words-used-in-this-guide)
- [How it works](#how-it-works)
- [The version file](#the-version-file)
- [Use cases](#use-cases)
  - [a. Update UI in a Compose app](#a-update-ui-in-a-compose-app)
  - [b. Update UI in a Views or AppCompat app](#b-update-ui-in-a-views-or-appcompat-app)
  - [c. Your own screens, core only](#c-your-own-screens-core-only)
  - [d. You already have a backend API](#d-you-already-have-a-backend-api)
  - [e. You have no backend](#e-you-have-no-backend)
  - [f. Authentication](#f-authentication)
  - [g. Forced updates](#g-forced-updates)
  - [h. Install timing, notifications, networks, logging](#h-install-timing-notifications-networks-logging)
  - [i. Theming the update UI](#i-theming-the-update-ui)
- [Settings reference](#settings-reference)
- [Update state reference](#update-state-reference)
- [What Renovo adds to your manifest](#what-renovo-adds-to-your-manifest)
- [Behavior per Android version](#behavior-per-android-version)
- [Known limits](#known-limits)
- [Google developer verification](#google-developer-verification)
- [Requirements](#requirements)
- [Versioning, changelog, license](#versioning-changelog-license)

## Words used in this guide

- **Host app** - your app, the one that uses Renovo.
- **Version file** - a small JSON document on your server that describes the latest version: its version code, its name, and where to download the APK.
- **Forced update** - an update the user cannot postpone. The host app stays blocked until it is installed.
- **Update UI** - the ready-made screens in the `renovo-ui` module: dialogs for optional updates and a full screen for forced updates.

Renovo has two modules:

| Module | What it has | Use it when |
|---|---|---|
| `renovo-core` | Checking, downloading, verifying, installing, and the update state. No screens. | You build your own screens. |
| `renovo-ui` | The update UI, built with Jetpack Compose. Depends on `renovo-core`. | You want ready-made screens. Works in Compose apps and in apps without Compose. |

## How it works

```text
  version file          APK file
  (your HTTPS host)     (your HTTPS host)
        |                     |
        v                     v
   1. CHECK  ------->  2. DOWNLOAD  ------->  3. VERIFY  ------->  4. INSTALL
   newer version?      in the background,     hash, package,       Android's package
                       resumes after a        version code,        installer, started
                       broken connection      signing certificate  from the foreground
```

1. **Check.** Renovo reads the version file. It does this when the host app starts, every `checkInterval` in the background, and when you call `Renovo.checkNow()`. WorkManager, the Android library for background work, runs the background check. Renovo sends the ETag of the last answer (a tag the server attaches to one version of a file), so an unchanged file costs almost no data.
2. **Download.** If the version code is higher than the installed one, Renovo downloads the APK into the host app's private storage. An interrupted download resumes where it stopped. The same version is never downloaded twice. Files of older versions are deleted.
3. **Verify.** Before anything is installed, Renovo checks, in this order: the SHA-256 hash (when the version file gives one), the package name, the version code, and the signing certificate.
4. **Install.** Renovo hands the APK to the Android package installer. Android shows its own confirmation unless it can skip it (see [Behavior per Android version](#behavior-per-android-version)). An install always starts while the host app is in the foreground, never from a background job. After the update, the new version deletes the downloaded file and schedules the next check.

What Renovo guarantees:

- **Same publisher.** Only an APK with the same package name and the same signing certificate as the installed app is installed. A compromised server cannot make Renovo install an APK from another publisher.
- **Intact file.** When the version file has a `sha256`, a file with a different hash is never installed. A file that fails any check is deleted and reported with a specific reason.
- **Never a downgrade.** Only a higher version code is accepted.
- **Never at a bad moment.** Renovo asks your `canInstallNow` function right before every install. While it returns `false`, nothing is installed.
- **Data is safe.** A failed or cancelled install leaves the current version running with its data. Migrating stored data between versions is the host app's job.
- **Forced updates hold.** A device that skipped several releases and is below `minSupportedVersionCode` is still blocked.
- **HTTPS only.** Renovo rejects a version file URL or APK URL that is not HTTPS, and a redirect away from HTTPS. The server needs a certificate that the device trusts.
- **No data sent.** Renovo makes no request except the one for the version file and the one for the APK. It has no analytics.

## The version file

The version file is a JSON document on any HTTPS host. A static file is enough. Here is one with every field:

```json
{
  "versionCode": 42,
  "versionName": "1.8.0",
  "apkUrl": "https://example.com/app/app-1.8.0.apk",
  "sha256": "3b4c8f0e2a9d7c61e5f04b2a8d9c3e7f1a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d",
  "size": 25165824,
  "mandatory": false,
  "minSupportedVersionCode": 38,
  "releaseNotes": {
    "az": "- Sinxronizasiya **sürətləndirildi**\n- Xətalar aradan qaldırıldı",
    "en": "- Sync is **faster**\n- Bug fixes"
  }
}
```

Only the first three fields are required.

| Field | Required | Meaning |
|---|---|---|
| `versionCode` | yes | Version code of the new APK, the same number as `versionCode` in its `build.gradle.kts`. A whole number of 1 or more. |
| `versionName` | yes | Version name shown to the user. A string. |
| `apkUrl` | yes | HTTPS URL of the APK. |
| `sha256` | no | SHA-256 hash of the APK, 64 hexadecimal characters, upper or lower case. Checked when present. |
| `size` | no | APK size in bytes. Used only for display, such as the download size and the progress. A positive number. |
| `mandatory` | no, default `false` | `true` makes this update a forced update for every older installed version. |
| `minSupportedVersionCode` | no | Installed versions below this version code get a forced update. Versions at or above it get an optional one. |
| `releaseNotes` | no | One string for every language, or an object with one string per language code, such as `en` or `pt-BR`. |

The rules:

- **Release notes per language.** A key is a language code, optionally with a region, such as `pt` or `pt-BR`. Case does not matter, and `pt_BR` means the same as `pt-BR`. For a phone set to Brazilian Portuguese, Renovo picks `pt-BR`, then `pt`, then any other `pt-` key in file order, then `en` (or the first `en-` key when there is no plain `en`), then the first entry. A phone set to plain `pt` gets `pt`, or the first `pt-` key when there is no plain `pt`. A single string is used for every language.
- **Which language counts.** The update UI uses the host app's language as its activity sees it, so a per-app language set on Android 13 and later is respected. The "Update ready" notification has no activity, so its texts come from the application context. On Android 12 and older, a language set only through AppCompat's per-app language support reaches activities, not the application context, so the notification may use the device language.
- **Markdown subset.** Release notes may use `**bold**`, `*italic*`, lines that start with `- ` as bullets, `[text](https://...)` links, and line breaks. Everything else is shown as typed. Links open only if they are HTTPS.
- **Unknown fields are ignored**, so the format can grow.
- **A file that cannot be read** (broken JSON, a missing required field, a plain HTTP `apkUrl`, a file larger than 1 MiB or not in UTF-8) is a failed check, not a crash. Renovo tries again at the next check.
- **HTTPS only**, for the version file and for the APK. The server may redirect, up to five times, as long as every step is HTTPS.
- **Always raise `versionCode`.** Renovo only installs a higher version code than the installed one, and Android refuses anything else anyway.

### Compute the hash and the size

Add `sha256`. Without it, Renovo still checks the package name, the version code, and the signing certificate. The hash adds a check that the file is exactly the one you published.

```sh
# macOS
shasum -a 256 app-release.apk     # sha256: the first word of the output
wc -c < app-release.apk           # size in bytes

# Linux
sha256sum app-release.apk
wc -c < app-release.apk
```

On Windows PowerShell, `Get-FileHash app-release.apk -Algorithm SHA256` prints the hash in capitals, which Renovo accepts, and `(Get-Item app-release.apk).Length` prints the size.

### Host it

Any server that serves files over HTTPS with a trusted certificate works. The APK and the version file can be on different hosts.

- **A plain web server** or any static file hosting. Put `version.json` and the APK in one folder. Static file servers already send an ETag or a Last-Modified date and answer range requests, which Renovo uses to resume a download.
- **An S3-style bucket**, meaning any object storage with an S3-compatible API. Make the two objects publicly readable, or put a CDN in front of the bucket, and use their HTTPS URLs. For a private bucket, see [Authentication](#f-authentication).
- **GitHub Releases of a public repository.** Attach the APK and `version.json` to each release. Set `versionUrl` to `https://github.com/OWNER/REPO/releases/latest/download/version.json`, which always points to the newest release that is not a draft or a pre-release. In the file, set `apkUrl` to that release's APK, such as `https://github.com/OWNER/REPO/releases/download/v1.8.0/app-1.8.0.apk`. GitHub redirects both to its download host over HTTPS, sends an ETag, and answers range requests, so checks and resumed downloads work. Assets of a private repository need a token, and Renovo sends no headers with the APK request, so private repositories do not work.

The `Content-Type` of the version file does not matter. Renovo reads the body as JSON.

### Caching and ETag

Renovo keeps the ETag of the last version file it read and sends it as `If-None-Match` on the next check. When the file has not changed, the server answers `304 Not Modified` with no body, and the check ends there. A server without ETags still works: Renovo then downloads the small file at every check.

Renovo turns off the HTTP cache on the device, so what it sees is what your server or CDN sends. If a CDN caches `version.json`, give it a short cache time, for example `Cache-Control: max-age=300`, or clear the CDN cache after each release. Otherwise devices see the new version only when the cached copy expires.

### Forced updates in the file

There are two ways to make an update forced. Use whichever matches your rule.

Force everyone onto version 42, for example after a security fix:

```json
{ "versionCode": 42, "versionName": "1.8.0", "apkUrl": "https://example.com/app/app-1.8.0.apk", "mandatory": true }
```

Force only very old versions. Here devices on 37 or lower must update, and devices on 38 to 41 get an optional update:

```json
{ "versionCode": 42, "versionName": "1.8.0", "apkUrl": "https://example.com/app/app-1.8.0.apk", "minSupportedVersionCode": 38 }
```

`minSupportedVersionCode` works even when `mandatory` is `false`. A device that skipped several releases is still blocked. What the user then sees is in [Forced updates](#g-forced-updates).

## Use cases

Each section below has complete code you can paste. Pick the ones that match your app.

### a. Update UI in a Compose app

Add the `renovo-ui` dependency from the [Quick start](#quick-start), start Renovo in your `Application`, and wrap your content in `RenovoUpdateHost`. It draws your content, and the update UI on top of it.

```kotlin
// App.kt
import android.app.Application
import io.lightine.renovo.Renovo

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Renovo.init(this) {
            versionUrl = "https://example.com/app/version.json"
        }
    }
}
```

Register the class in `AndroidManifest.xml` with `<application android:name=".App" ...>`.

```kotlin
// MainActivity.kt
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.lightine.renovo.ui.RenovoUpdateHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                RenovoUpdateHost {
                    AppContent()
                }
            }
        }
    }
}
```

Put `RenovoUpdateHost` inside your `MaterialTheme` (here `AppTheme`), so the update UI uses your colors. Nothing else is needed: the update UI follows the update state by itself.

#### What the user sees

| Screen | When it shows | How it goes away |
|---|---|---|
| Update available | A newer optional version exists and is not downloaded yet. This happens when `autoDownload = false`, or after the user cancelled a download. Shows the release notes with the version and the size, when the version file has release notes. | "Later" hides it until the next app start. "Update" starts the download. |
| Downloading | The user started the download. A background download shows nothing. | "Hide" hides it. The download continues. |
| Ready to install | The file is downloaded and verified, and `canInstallNow` returns `true`. | "Not now" hides it until the next app start. "Install" installs. |
| First-time permission | The user must allow installs from the host app. Explains the Android setting in three steps. | "Cancel" hides it until the next app start. "Open settings" opens the setting, and the update continues when the user comes back. |
| Update not installed | Something the user started failed. Shows the reason. A failed background check shows nothing. | "Close" hides it. "Try again" retries. |
| Forced update | A forced update exists. A full screen over the host app with the download progress, a network wait, the permission steps, a failure with "Try again", or the text for a device that cannot update. | **Cannot be dismissed.** Back and Esc do nothing. |

A choice such as "Later" is kept for the version it was made for, until the host app's process ends; Android may keep the process alive for a while after the user leaves the app. A newer version asks again. Rotating, resizing, folding, or unfolding never brings back a screen the user put away, and never restarts a download.

More about the update UI:

- **Languages.** Every text is in English and Azerbaijani and follows the device language. Other languages show English. You can add a language, see [Theming](#i-theming-the-update-ui).
- **Window sizes.** The layout follows the window width, not the device type. Dialogs fill a phone's width minus margins, are 440dp wide on a medium window, and at most 560dp on a wide one. The forced update screen uses two columns on a wide window. Nothing is locked to one orientation.
- **Keyboard and mouse.** Every screen works with a keyboard only. Enter runs the main action and Esc the dismiss action.
- **Accessibility.** Touch targets are at least 48dp, and the progress indicator announces its value.
- **Several activities.** The update state belongs to the whole process. The update UI appears only in the most recently resumed activity that has a `RenovoUpdateHost` or called `attachUi`. Add it to every activity a user can stay in for long, at least the launcher activity.

### b. Update UI in a Views or AppCompat app

Use the same dependency and the same `App` class as in [use case a](#a-update-ui-in-a-compose-app). In each activity, call `Renovo.attachUi(this)` in `onCreate`, after `setContentView`. It adds the update UI on top of your views. Your app needs no Compose code of its own.

```kotlin
// MainActivity.kt
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.lightine.renovo.Renovo
import io.lightine.renovo.ui.attachUi

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Renovo.attachUi(this)
    }
}
```

- Call it every time the activity is created, so the update UI comes back after a rotation. A second call for the same activity does nothing.
- The activity must be a `ComponentActivity`. Every `AppCompatActivity` and `FragmentActivity` is one. A plain `android.app.Activity` is not supported, because Compose needs what `ComponentActivity` provides.
- `attachUi` has no Compose theme to take colors from. See [Theming](#i-theming-the-update-ui) to match your colors.

### c. Your own screens, core only

Use only `renovo-core` when you want to draw every screen yourself. Your app reads the update state and calls Renovo's actions.

```kotlin
dependencies {
    implementation("io.lightine.renovo:renovo-core:0.1.0")
}
```

Start Renovo in your `Application` exactly as in [use case a](#a-update-ui-in-a-compose-app). The `ui { }` settings then have no effect.

`Renovo.state` is a `StateFlow<UpdateState>`, a value that changes over time. It always holds one `UpdateState`, and it lives as long as the process, so it survives rotation and every screen. This activity shows the state as text and offers the right button for each state:

```kotlin
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.lightine.renovo.Renovo
import io.lightine.renovo.UpdateState
import kotlinx.coroutines.launch

class UpdateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)
        val status = findViewById<TextView>(R.id.status)
        val action = findViewById<Button>(R.id.action)
        findViewById<Button>(R.id.check).setOnClickListener { Renovo.checkNow() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Renovo.state.collect { state ->
                    status.text = describe(state)
                    val button = buttonFor(state)
                    action.isEnabled = button != null
                    action.text = button?.first ?: ""
                    action.setOnClickListener { button?.second?.invoke() }
                }
            }
        }
    }

    // The label and the action of the one button that fits the state, or null for none.
    private fun buttonFor(state: UpdateState): Pair<String, () -> Unit>? = when (state) {
        is UpdateState.Available -> "Download" to { Renovo.startDownload() }
        is UpdateState.ReadyToInstall -> "Install" to { Renovo.installIfReady() }
        is UpdateState.PermissionNeeded -> "Open settings" to { Renovo.installIfReady() }
        is UpdateState.Failed -> "Try again" to { if (state.info == null) Renovo.checkNow() else Renovo.startDownload() }
        // A forced update gets its own blocking screen, see "Forced updates".
        else -> null
    }
}
```

The message for every state. The `when` has no `else`, so the compiler tells you if a future version adds a state:

```kotlin
import io.lightine.renovo.DownloadProgress
import io.lightine.renovo.UpdateState

fun describe(state: UpdateState): String = when (state) {
    UpdateState.Idle -> "Not checked yet."
    UpdateState.Checking -> "Checking for updates..."
    UpdateState.UpToDate -> "You have the latest version."
    is UpdateState.Available -> "Version ${state.info.versionName} is available."
    is UpdateState.Downloading -> "Downloading version ${state.info.versionName}${percent(state.progress)}."
    is UpdateState.WaitingForNetwork -> "The download waits for an allowed network."
    is UpdateState.ReadyToInstall -> "Version ${state.info.versionName} is ready to install."
    is UpdateState.PermissionNeeded -> "Allow this app to install updates in the settings."
    is UpdateState.Installing -> "Installing..."
    is UpdateState.Failed -> "The update failed: ${state.reason}."
    UpdateState.Unsupported -> "This device does not allow the app to update itself."
    is UpdateState.Required -> "Version ${state.info.versionName} is required." // details in state.step
}

private fun percent(progress: DownloadProgress): String =
    progress.totalBytes?.let { total -> ", ${progress.bytesDownloaded * 100 / total}%" } ?: ""
```

In Compose, read the state with `val state by Renovo.state.collectAsStateWithLifecycle()` from `androidx.lifecycle:lifecycle-runtime-compose`.

Without coroutines, use a listener. It is called on the main thread, once with the current state when you add it, and after every change. Remove it when the screen goes away.

```kotlin
// Inside your activity, where status is a TextView.
private val listener = UpdateListener { state -> status.text = describe(state) }

override fun onStart() {
    super.onStart()
    Renovo.addListener(listener)
}

override fun onStop() {
    Renovo.removeListener(listener)
    super.onStop()
}
```

Things your own screens must do, because the update UI is not there to do them:

- **Block the app during a forced update**, and offer `Renovo.useMobileDataOnce()` while it waits for a network. See [Forced updates](#g-forced-updates) for the code.
- **Offer the install.** Downloads start by themselves, so most users meet `ReadyToInstall` first. Show it, and call `Renovo.installIfReady()` when the user agrees.
- **Hide background failures.** Show `Failed` only when `state.userInitiated` is `true`. Renovo retries network failures by itself.
- **Show downloads the user started.** `Downloading.userInitiated` is `true` for those.

The [Update state reference](#update-state-reference) lists every state, step, and failure reason.

### d. You already have a backend API

If your backend already knows the latest version, Renovo can ask it instead of reading a version file. Implement `VersionSource`, a function that returns an `UpdateInfo`, and set it as `versionSource` instead of `versionUrl`.

`UpdateInfo` holds the same fields as the version file. Its constructor throws `IllegalArgumentException` for a value that is not allowed, such as an `apkUrl` that is not HTTPS or a `sha256` that is not 64 hexadecimal characters.

This example calls `https://api.example.com/app/latest` with the platform's `HttpURLConnection` and reads a JSON answer like `{"build": 42, "name": "1.8.0", "url": "https://...", "sha256": "...", "force": false, "minBuild": 38, "notes": "- Bug fixes"}`. Change the field names to match your API.

```kotlin
import io.lightine.renovo.ReleaseNotes
import io.lightine.renovo.UpdateInfo
import io.lightine.renovo.VersionSource
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

class BackendVersionSource(private val endpoint: String) : VersionSource {
    // Renovo calls this on a background thread, so a blocking call is fine here.
    override suspend fun latest(): UpdateInfo {
        val connection = URI(endpoint).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return parse(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(body: String): UpdateInfo = try {
        val json = JSONObject(body)
        UpdateInfo(
            versionCode = json.getLong("build"),
            versionName = json.getString("name"),
            apkUrl = json.getString("url"),
            sha256 = if (json.isNull("sha256")) null else json.getString("sha256"),
            mandatory = json.optBoolean("force", false),
            minSupportedVersionCode = if (json.isNull("minBuild")) null else json.getLong("minBuild"),
            releaseNotes = if (json.isNull("notes")) null else ReleaseNotes(json.getString("notes")),
        )
    } catch (e: JSONException) {
        // IllegalArgumentException tells Renovo the answer could not be read (FailureReason.Parse).
        throw IllegalArgumentException("The version answer could not be read.", e)
    }
}
```

```kotlin
Renovo.init(this) {
    versionSource = BackendVersionSource("https://api.example.com/app/latest")
}
```

If your app already has an HTTP client with suspend functions, call it directly and map its answer:

```kotlin
Renovo.init(this) {
    versionSource = VersionSource {
        val latest = myApi.latestVersion() // your own suspend call
        UpdateInfo(
            versionCode = latest.build,
            versionName = latest.name,
            apkUrl = latest.downloadUrl,
            mandatory = latest.force,
            minSupportedVersionCode = latest.minBuild,
            releaseNotes = ReleaseNotes.of("en" to latest.notesEn, "az" to latest.notesAz),
        )
    }
}
```

How Renovo treats your source:

- It calls `latest()` for every check: at app start, every `checkInterval`, and on `Renovo.checkNow()`. There is no ETag here, so keep the call cheap.
- To report a failed check, throw. An `IllegalArgumentException` counts as an answer that could not be read (`FailureReason.Parse`). Any other exception, such as an `IOException`, counts as a network failure (`FailureReason.Network`), and a background check is retried later with growing delays.
- `versionHeaders` is not used with a `versionSource`. Your own client adds whatever headers it needs.

Release notes: `ReleaseNotes("- Bug fixes")` is one text for every language. `ReleaseNotes.of("en" to "...", "az" to "...")` is one text per language code, and the first entry is the last fallback. A code may carry a region, such as `"pt-BR"`. `notes.forLanguage("pt-BR")` returns the text Renovo would pick for that language tag, with the fallbacks described in [The version file](#the-version-file).

#### Hand over version information you already have

If the host app already receives version information, for example in its own sync response, pass it to Renovo. `submit` has the same effect as a successful check, and no extra request is made.

```kotlin
Renovo.submit(UpdateInfo(versionCode = 42, versionName = "1.8.0", apkUrl = "https://example.com/app/app-1.8.0.apk"))
```

`Renovo.init` still needs a `versionUrl` or a `versionSource`, because Renovo also checks on its own. There is no mode that only uses `submit`.

### e. You have no backend

Then host a static version file next to your APK, as described in [The version file](#the-version-file), and set `versionUrl`. Nothing runs on the server.

Release checklist for each new version:

1. **Raise `versionCode`** in the app's `build.gradle.kts`. It must be higher than every version in use.
2. **Build a signed release APK** with the same signing key as before, for example with `./gradlew assembleRelease`. The file is in `app/build/outputs/apk/release/`. Renovo installs one complete APK; if you build app bundles, build a universal APK instead.
3. **Compute the hash and the size** (see [Compute the hash and the size](#compute-the-hash-and-the-size)).
4. **Write `version.json`** with the new `versionCode`, `versionName`, `apkUrl`, `sha256`, and `size`. Set `mandatory` or `minSupportedVersionCode` if this is a forced update.
5. **Upload the APK first, then `version.json`.** In this order a device never reads a file that points to an APK that is not there yet.
6. **Check both URLs** in a browser or with `curl -I`. Both must answer `200` over HTTPS.

A small script for steps 3 and 4 on macOS (on Linux, replace `shasum -a 256` with `sha256sum`):

```sh
APK=app/build/outputs/apk/release/app-release.apk
SHA=$(shasum -a 256 "$APK" | cut -d ' ' -f 1)
SIZE=$(wc -c < "$APK" | tr -d ' ')
cat > version.json <<EOF
{
  "versionCode": 43,
  "versionName": "1.9.0",
  "apkUrl": "https://example.com/app/app-1.9.0.apk",
  "sha256": "$SHA",
  "size": $SIZE
}
EOF
```

### f. Authentication

If the version file needs a token, set `versionHeaders`. Renovo calls it before every request to the version file, so a token that changes is always current.

```kotlin
Renovo.init(this) {
    versionUrl = "https://example.com/app/version.json"
    versionHeaders = { mapOf("Authorization" to "Bearer ${tokens.current()}") }
}
```

- The headers go to the version file only. If the server redirects to another host or port, Renovo drops them, so a token never leaks to a third party.
- **The APK request carries no custom headers.** The `apkUrl` must work without them. Use a public URL or a signed URL that expires, such as a presigned URL from your object storage or a CDN token URL. Give a signed URL enough lifetime for a slow download, for example a day.
- Renovo recognizes a file by its version code and hash, not by its URL. A signed URL that changes at every check does not restart a download.
- With a `versionSource`, `versionHeaders` is not used. Your own client adds the headers, and it can return a freshly signed `apkUrl` at every check.

### g. Forced updates

**Declare one** in the version file with `"mandatory": true` or `"minSupportedVersionCode"` (see [Forced updates in the file](#forced-updates-in-the-file)). With a `versionSource`, set `mandatory` or `minSupportedVersionCode` in the `UpdateInfo`.

**What the user sees with the update UI.** A full screen covers the host app. It cannot be dismissed, and Back and Esc do nothing. With the default `autoDownload = true`, the download starts in the background and the screen shows its progress. When the file is ready, the user taps "Update now" and Android installs it. If the device waits for an allowed network, the screen says so and, when allowed, offers a "Download with mobile data" button. If the device does not allow the app to update itself, the screen says to ask whoever installed the app.

**What the host app should do.**

- With the update UI, nothing. The forced screen appears on its own, in every activity that has the update UI.
- `canInstallNow` still applies. While it returns `false`, a forced update waits too, so let it return `true` as soon as your data is safe.
- The host app keeps running below the forced screen. If your backend no longer accepts the old version, expect its requests to fail until the user updates.
- On a device that cannot install (`UpdateStep.Unsupported`), the user cannot get past the screen. Plan another way to deliver the APK there.

**With your own screens**, `UpdateState.Required` means "block the app". It stays set through download and install, and its `step`, an `UpdateStep`, says what is happening:

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        Renovo.state.collect { state ->
            val required = state as? UpdateState.Required
            if (required == null) {
                hideBlockingScreen()
                return@collect
            }
            showBlockingScreen(required.info.versionName)
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
    }
}
```

The functions such as `showBlockingScreen` are yours. `Renovo.startDownload()` starts the download, or starts the install when the file is already verified. `Renovo.useMobileDataOnce()` lets the waiting download use mobile data, for this version only. It works only when `step.canUseMobileData` is `true`.

### h. Install timing, notifications, networks, logging

All of these are settings in the `Renovo.init` block. The [Settings reference](#settings-reference) lists every one.

#### Choose when an install may start

An install closes the host app. If that would lose work, for example while data is not yet sent to your server, set `canInstallNow`:

```kotlin
canInstallNow = { !repository.hasUnsentData() }
```

- Renovo calls it on the main thread right before every install, optional or forced. Keep it fast. An exception counts as `false`.
- While it returns `false`, the update stays ready (`ReadyToInstall`, or `Required` waiting for the user). The update UI asks again when it is about to show the dialog and whenever the activity resumes. Without the update UI, Renovo asks again at the next `Renovo.installIfReady()`.
- The update UI shows its "Ready to install" dialog only while `canInstallNow` returns `true`. It asks when the dialog is about to show and every time the activity resumes.

#### Download only when the user asks

By default, Renovo downloads a newer version as soon as it finds one. With `autoDownload = false`, it waits: the state is `Available` (or `Required` waiting for the user) until `Renovo.startDownload()`, and the update UI shows its "Update available" dialog.

#### Notifications

- **When.** When a download becomes ready to install and no activity of the host app is in the foreground, Renovo shows one "Update ready" notification. Tapping it opens the host app's launcher activity, where the update UI shows "Ready to install". The notification never installs directly, because an install must start from the foreground. Renovo removes it when the update is installed or replaced by a newer version.
- **Only when allowed.** Renovo shows it only if `notifications` is `true` and Android already allows notifications for the host app. Otherwise nothing is shown, and the install is offered at the next app start.
- **Renovo never asks for the permission on its own.** On Android 13 and later, notifications need a runtime permission. Your app picks the moment, for example after the first successful sync, and calls:

  ```kotlin
  Renovo.requestNotificationPermission(activity)
  ```

  On Android 12L and earlier it does nothing. If the permission is already granted, it does nothing either.
- **Download progress.** A download the user starts runs as a foreground job, and Android requires a visible notification for it. That one follows Android's rules and is not controlled by `notifications`.
- **Turn the "Update ready" notification off** with `notifications = false`.

#### Networks

Optional and forced updates each have their own network setting:

```kotlin
optionalDownloadNetwork = Network.UnmeteredOnly // optional updates only on Wi-Fi and similar
forcedDownloadNetwork = Network.NotRoaming      // forced updates on any network except roaming
allowMobileDataOverride = true                  // a waiting forced update may offer mobile data
```

- `Network.Any` - any connected network, including mobile data. The default for both.
- `Network.UnmeteredOnly` - a network that is not billed by usage, such as most Wi-Fi.
- `Network.NotRoaming` - any network except mobile data while roaming.

When a forced update waits for an allowed network, the user can choose to download with mobile data, for this version only. This needs `allowMobileDataOverride = true` (the default) and a `forcedDownloadNetwork` other than `Network.Any`.

#### Check interval

Set `checkInterval = 12.hours` (with `import kotlin.time.Duration.Companion.hours`). The default is 6 hours and the minimum is 15 minutes, the shortest period WorkManager allows. Renovo also checks at every app start and on `Renovo.checkNow()`. Android decides the exact moment of a background check, and an app that the user force-stopped runs no background work until it is opened again.

#### Logging

Logging is off until you set a `logger`. Messages never contain personal data. A logger that throws cannot break Renovo.

```kotlin
logger = RenovoLogger { message, error -> Log.d("Renovo", message, error) }
```

For problems that the log does not explain, see the wiki page Troubleshooting.

### i. Theming the update UI

#### Colors

- **`RenovoUpdateHost` uses your theme.** Inside your `MaterialTheme`, the update UI uses your colors, typography, and shapes, including your choice of light or dark. The `ui { }` settings are then ignored, because your theme already decided.
- **`colorSchemes` replaces the inherited colors.** Pass a light and a dark Material 3 color scheme. The `ui { }` settings then choose between them.
- **`attachUi` has nothing to inherit.** It uses `ui { themeMode }` to choose light or dark, then your `colorSchemes` if you passed them, then dynamic color (colors from the wallpaper) on Android 12 and later, then the Material 3 baseline colors.
- One blind spot: a host theme that uses exactly the Material 3 baseline light scheme looks like no theme at all. The update UI then follows the `ui { }` settings.

```kotlin
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.lightine.renovo.ui.RenovoColorSchemes

val schemes = RenovoColorSchemes(
    light = lightColorScheme(primary = Color(0xFF6750A4)),
    dark = darkColorScheme(primary = Color(0xFFD0BCFF)),
)

// Compose:
RenovoUpdateHost(colorSchemes = schemes) { AppContent() }
// Views:
Renovo.attachUi(this, colorSchemes = schemes)
```

#### Dark mode and dynamic color

Set these in the `ui { }` block of `Renovo.init`. They apply when no theme is inherited.

```kotlin
ui {
    themeMode = ThemeMode.Dark // System (default), Light, or Dark
    dynamicColor = false       // default true: wallpaper colors on Android 12 and later
}
```

#### Texts

Every text of the update UI is an Android string resource whose name starts with `renovo_ui_`. The notification texts start with `renovo_`. To change one, define a string with the same name in your app. Android then uses yours, because app resources take precedence over library resources.

```xml
<!-- res/values/strings.xml of the host app -->
<string name="renovo_ui_forced_headline">Please update to continue</string>
```

- Keep the same placeholders (`%1$s`, `%2$s`) as the original.
- Change the Azerbaijani text too, in `res/values-az/strings.xml`. Otherwise Azerbaijani devices still see the original.
- To add a language, define every string in your own folder for it, such as `res/values-de/strings.xml`.
- All names are in [`renovo-ui/src/main/res/values/strings.xml`](renovo-ui/src/main/res/values/strings.xml) and [`renovo-core/src/main/res/values`](renovo-core/src/main/res/values).
- Resource names are not covered by the API compatibility check. Check your overrides after you upgrade Renovo.

#### Icons

The notification small icons are two drawables: `renovo_ic_update_ready` (update ready) and `renovo_ic_download` (download progress). To use your own, add a drawable with the same name to your app, for example `res/drawable/renovo_ic_update_ready.xml`. Android draws a notification small icon as a mask, so only the shape matters. The update UI also uses `renovo_ic_download` in its dialogs and on the forced update screen, so replacing it changes that icon too.

## Settings reference

All settings go in the block of `Renovo.init`, which you call once in `Application.onCreate`. Set exactly one of `versionUrl` and `versionSource`. Every other setting has a default. Invalid settings throw `IllegalArgumentException`. A second call to `init` is ignored.

| Setting | Default | What it does |
|---|---|---|
| `versionUrl` | none | HTTPS URL of the version file. `versionUrl = "https://example.com/app/version.json"` |
| `versionSource` | none | Your own source of version information, instead of a version file. See [use case d](#d-you-already-have-a-backend-api). |
| `versionHeaders` | no headers | Extra headers for the version file request. `versionHeaders = { mapOf("Authorization" to "Bearer ${tokens.current()}") }` |
| `checkInterval` | `6.hours` | Time between background checks. The minimum is 15 minutes. `checkInterval = 12.hours` |
| `autoDownload` | `true` | `true` downloads a newer version without waiting for the user. `false` waits for `Renovo.startDownload()`. `autoDownload = false` |
| `optionalDownloadNetwork` | `Network.Any` | Networks an optional update may download on: `Any`, `UnmeteredOnly`, or `NotRoaming`. `optionalDownloadNetwork = Network.UnmeteredOnly` |
| `forcedDownloadNetwork` | `Network.Any` | The same choice for forced updates. `forcedDownloadNetwork = Network.NotRoaming` |
| `allowMobileDataOverride` | `true` | A forced update that waits for an allowed network lets the user download with mobile data instead. `allowMobileDataOverride = false` |
| `notifications` | `true` | Shows the "Update ready" notification, only when notifications are already allowed. `notifications = false` |
| `canInstallNow` | `{ true }` | Asked right before every install. Return `false` while an install would lose work. `canInstallNow = { !repository.hasUnsentData() }` |
| `logger` | `null` (silent) | Receives Renovo's diagnostic messages. `logger = RenovoLogger { message, error -> Log.d("Renovo", message, error) }` |
| `ui { }` | `themeMode = ThemeMode.System`, `dynamicColor = true` | Settings for the update UI, see [Theming](#i-theming-the-update-ui). `ui { themeMode = ThemeMode.Dark }` |

A complete example with every setting:

```kotlin
import android.util.Log
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

`Renovo.config` returns these settings after `init`, read-only.

**Main process only.** `Application.onCreate` runs in every process of the host app, such as a `:remote` service or a crash reporter. `Renovo.init` checks the process name itself and does nothing outside the main process (the one named after the package), so you can call it unconditionally. In the other processes the other `Renovo` members throw `IllegalStateException`, as they do anywhere before `init`.

## Update state reference

### Actions

| Call | What it does | Example |
|---|---|---|
| `Renovo.checkNow()` | Checks for a new version now. | `checkButton.setOnClickListener { Renovo.checkNow() }` |
| `Renovo.startDownload()` | Starts the download of the available version, or tries again after a failure. If the file is already downloaded and verified, it starts the install. | `updateButton.setOnClickListener { Renovo.startDownload() }` |
| `Renovo.installIfReady()` | Installs the downloaded and verified version after asking `canInstallNow`. When the user must first allow installs, it opens that setting. Does nothing when no verified file exists. | `installButton.setOnClickListener { Renovo.installIfReady() }` |
| `Renovo.submit(info)` | Hands over version information you already have. | `Renovo.submit(info)` |
| `Renovo.useMobileDataOnce()` | Lets a waiting forced update download with mobile data, for this version only. | `mobileDataButton.setOnClickListener { Renovo.useMobileDataOnce() }` |
| `Renovo.requestNotificationPermission(activity)` | Asks the user to allow notifications. | `Renovo.requestNotificationPermission(this)` |
| `Renovo.addListener(listener)` / `Renovo.removeListener(listener)` | Calls a listener on the main thread with every state. | `Renovo.addListener { state -> render(state) }` |

### `UpdateState`

`Renovo.state.value` is always one of these. Every state with `info` carries the `UpdateInfo` of the version concerned.

| State | Meaning |
|---|---|
| `Idle` | Nothing is known yet. No check has succeeded since the app started, and nothing was saved from an earlier run. A failed background check leaves this state. |
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

### `UpdateStep`

The `step` of `Required`:

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

### `FailureReason`

| Reason | Meaning |
|---|---|
| `Network` | No connection, or the server could not be reached. |
| `Parse` | The version information could not be read. |
| `HashMismatch` | The downloaded file is not the one the version information describes: the SHA-256 hash differs, the file is not a valid APK, or the APK has another version code than announced. |
| `SignatureMismatch` | The file is from a different publisher or a different app. |
| `NotNewer` | The downloaded file is not newer than the installed version. |
| `Storage` | Not enough free space, or the file could not be written or read. |
| `InstallRejected` | The system rejected the install. |
| `Cancelled` | The user cancelled the install. A download the user cancels from its notification is not a failure: the update goes back to "update available" (`Available`, or `Required` waiting for the user), it is not downloaded again by itself, and `startDownload()` restarts it. |

## What Renovo adds to your manifest

Renovo merges these permissions and components into the host app's manifest. You declare nothing yourself. To see the result, open the Merged Manifest tab of `AndroidManifest.xml` in Android Studio.

| Permission | Added by | Why |
|---|---|---|
| `INTERNET` | `renovo-core` | To fetch the version file and the APK. |
| `ACCESS_NETWORK_STATE` | `renovo-core`, `renovo-ui` | To honor the network setting for downloads. The update UI also uses it to show the offline note on the forced update screen. |
| `REQUEST_INSTALL_PACKAGES` | `renovo-core` | Android 8 and later require it to install an APK from outside a store. The user still has to allow the host app. |
| `UPDATE_PACKAGES_WITHOUT_USER_ACTION` | `renovo-core` | Lets a self-update skip the confirmation prompt on Android 12 and later when Android's conditions are met. A normal permission. |
| `FOREGROUND_SERVICE_DATA_SYNC` | `renovo-core` | Android 14 and later require it for the foreground job that runs a download the user started. |
| `POST_NOTIFICATIONS` | `renovo-core` | So `Renovo.requestNotificationPermission` can be granted on Android 13 and later. Renovo never asks on its own. |

WorkManager, which Renovo uses for its background jobs, adds three more permissions of its own: `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` (to reschedule the periodic check after a restart), and `FOREGROUND_SERVICE`.

| Component | Why |
|---|---|
| `SystemForegroundService` (WorkManager's), with type `dataSync` | Runs the download the user started. |
| `InstallStatusReceiver` (not exported) | Receives the result from the Android installer, through an explicit `PendingIntent`. |
| `PackageReplacedReceiver` (not exported) | Android sends `MY_PACKAGE_REPLACED` to the replaced app only. Renovo uses it as a safety net, to report a finished install when the installer's own result was lost. |

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
| 16 and later (API 36+) | Google's developer verification can stop an install. Renovo reports it as `InstallRejected`, and from API 36.1 it writes the reason the installer gives to your `logger`. See [Google developer verification](#google-developer-verification). |
| 17 (API 37) | Advanced Protection Mode, an optional Android setting for high-risk users, blocks installs from unknown sources. Renovo reports the device as unsupported. On Android 17 and later, the permission dialog and the forced update screen show the "Always allow" choice instead of the switch, as the system screen does. |

**When a self-update is silent (Android 12 and later).** Android skips the prompt only if all of these hold. Renovo asks for it, and Android decides:

- the host app holds `UPDATE_PACKAGES_WITHOUT_USER_ACTION` (Renovo declares it),
- the host app is updating itself, and the user has allowed installs from it,
- the new APK's target SDK is at or above a floor that rises with each Android release (table below),
- nothing else needs the user, such as a different update owner or a recent earlier silent update.

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
- **Developer verification is the owner's job.** See [Google developer verification](#google-developer-verification).
- **Data migration is yours.** The host app migrates its own stored data between versions.
- **Play Protect may warn.** Google Play Protect may show a warning when an app installs an APK from outside a store. Renovo cannot suppress it.
- **Same signing key, always.** Every APK must be signed with the same key as the installed app, or Android refuses it. Renovo checks this first so it can give a clear reason.
- **A v2 or newer APK signature is required.** An APK with only the old v1 (JAR) signature fails the file check with `HashMismatch`. Android's build tools add v2 and v3 signatures by default.
- **Unsupported stays until the next version.** When device policy forbids installs from the host app, Renovo reports `Unsupported` and keeps that answer until a different version of the host app is installed, even if the policy is lifted later.

**Local update servers on Android 17.** An app that targets Android 17 (API 37) or higher cannot reach a private network address, such as `10.0.2.2` or `192.168.1.20`, without the `ACCESS_LOCAL_NETWORK` permission. The request times out. If the host app fetches its version file or APK from such an address, for example in a test setup, declare the permission and, where the platform asks, request it at run time. Renovo does not add it, because public HTTPS hosts do not need it. See Android's [local network permission](https://developer.android.com/privacy-and-security/local-network-permission) page.

```xml
<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />
```

**Out of scope.** Renovo does not do these, and will not:

- **Apps that are also on the Play Store.** Renovo adds the permission to install packages, which Play policy restricts.
- **Split APKs and app bundles.** Renovo installs one complete APK. Publish a universal APK.
- **Updating other apps.** Renovo updates the host app only.
- **Rollback** to an earlier version.
- **Delta or patch updates.** Renovo downloads the full APK.
- **Device management** features.
- **Hosting** the version file or the APK. You provide the server.

## Google developer verification

Google is rolling out developer verification for apps on certified Android devices. A developer registers with Google, and registers each app's package name and signing key. On a device where verification is enforced, an app from an unregistered developer fails to install, and an update to an unregistered app fails too.

Enforcement started on 30 September 2026 in a first group of countries, and Google plans to extend it to all certified devices from 2027. Google's page has the current dates, the countries, and the exceptions, such as limited distribution accounts for students and hobbyists.

What it means for you:

- **The owner of the host app must register** the package name and the signing key, as a developer who distributes outside the Play Store. Renovo cannot do it for them. Do it before you ship the first Renovo update.
- Renovo reports a verification failure as `FailureReason.InstallRejected`, and on Android 16.1 (API 36.1) and later writes the reason to your `logger`.
- Google's page: [developer.android.com/developer-verification](https://developer.android.com/developer-verification).

## Requirements

| What | Value |
|---|---|
| Android | `minSdk` 24 (Android 7.0) or higher |
| `compileSdk` of the host app | 37 or higher. The libraries' dependencies require it, and the Android Gradle Plugin stops the build otherwise. |
| Kotlin | 2.4.0 or higher. See below. |
| Java | The class files are Java 17 bytecode. Set the host app's `sourceCompatibility` and `targetCompatibility` to Java 17 or higher. |
| Compose (`renovo-ui` only) | Built with Compose 1.12.1 and Material 3 1.4.0. Gradle raises your app to these versions if it uses older ones. |
| Distribution | Not on the Play Store. |

**Kotlin version.** Renovo is compiled with Kotlin 2.4.21, so its class files carry Kotlin metadata version 2.4. Kotlin documents compatibility in its [evolution principles](https://kotlinlang.org/docs/kotlin-evolution-principles.html): a newer compiler reads the binaries of older ones, and a compiler can usually, but with no guarantee, read the binaries of the next language release. So build the host app with **Kotlin 2.4.0 or higher**. Kotlin 2.3 can usually read Renovo, but Kotlin does not guarantee it. Kotlin 2.2 and older are not supported. With Compose, keep the Kotlin Compose compiler plugin at the same version as Kotlin.

**Dependencies.** `renovo-core` depends on WorkManager, `androidx.core`, and kotlinx.coroutines, which most apps already have. `renovo-ui` adds Jetpack Compose, Material 3, `androidx.activity`, and the Compose window size libraries.

## Versioning, changelog, license

- **Versioning.** Renovo follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html). `renovo-core` and `renovo-ui` always have the same version, so use the same version for both. The public API is checked for accidental breaking changes between releases.
- **Changelog.** [`CHANGELOG.md`](CHANGELOG.md) lists every change.
- **API reference.** Every public class, function, and setting has a description and an example in its KDoc. Build the reference with Dokka: `./gradlew dokkaGenerate`. The HTML pages are in `renovo-core/build/dokka/html` and `renovo-ui/build/dokka/html`.
- **More help.** The wiki has deeper pages, such as Troubleshooting.
- **Security.** Report a vulnerability as described in [`SECURITY.md`](SECURITY.md).
- **License.** Apache License 2.0. See [`LICENSE`](LICENSE). Copyright Asker Asadov (Lightine).
