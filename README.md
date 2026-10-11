# Renovo

[![Maven Central](https://img.shields.io/maven-central/v/io.lightine.renovo/renovo-core?label=Maven%20Central)](https://central.sonatype.com/artifact/io.lightine.renovo/renovo-core)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![check](https://github.com/lightine-io/renovo/actions/workflows/check.yml/badge.svg)](https://github.com/lightine-io/renovo/actions/workflows/check.yml)

Renovo is a Kotlin library that lets an Android app update itself: it checks for a new version, downloads the APK, verifies it, and installs it. It is made for apps that people install from an APK file, outside the Play Store. It is not for apps on the Play Store, and it does not update other apps.

In this guide, the **host app** is your app, the one that uses Renovo.

## Is Renovo for you

- **Yes**, if your app is installed from an APK file, for example from your website or a file share.
- **Yes**, if you can put two files on an HTTPS server, or if your backend can tell the app which version is the latest.
- **Yes**, if your app supports Android 7.0 (API 24) or later.
- **No**, if your app is on the Play Store. Renovo adds the permission to install packages, and Play policy restricts it.
- **No**, if you ship split APKs or app bundles. Renovo installs one complete APK. A universal APK works.
- **No**, if you want to update other apps. Renovo updates the host app only.

Renovo makes only two kinds of request: one for the version file and one for the APK. It sends no analytics.

Building with another stack? More in the wiki: Other stacks.

## Choose your path

Pick the row that describes your app. Each path is a complete tutorial that you can follow from top to bottom.

| Your situation | Go to |
|---|---|
| My app uses Jetpack Compose and I want ready-made update screens | [Path 1: Ready-made screens in a Compose app](#path-1-ready-made-screens-in-a-compose-app) |
| My app uses XML layouts (Views) and I want ready-made update screens | [Path 2: Ready-made screens in an XML app](#path-2-ready-made-screens-in-an-xml-app) |
| I want to draw my own update screens | [Path 3: Your own screens](#path-3-your-own-screens) |

Every path ends with "Publish your first version file". The two ways to give Renovo the version information, a file on any HTTPS host or your own backend, are explained after the paths, in [Where the version information comes from](#where-the-version-information-comes-from).

## Path 1: Ready-made screens in a Compose app

Follow this path if your app draws its screens with Jetpack Compose. At the end, your app checks for updates by itself and shows ready-made update screens. In this guide these screens are called the **update UI**. You write about 20 lines of Kotlin and one small JSON file.

You need an app with `minSdk` 24 or higher, `compileSdk` 37 or higher, Kotlin 2.4.0 or higher, and Java 17 or higher. You also need a place on the web that serves files over HTTPS.

### Step 1. Add the dependency

Renovo is on Maven Central. Add `renovo-ui` to your app module. It brings `renovo-core` with it. `renovo-core` checks, downloads, and installs. `renovo-ui` draws the screens.

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.lightine.renovo:renovo-ui:0.1.0")
}
```

Check that `mavenCentral()` is listed under `repositories` in `settings.gradle.kts`. New Android Studio projects have it.

### Step 2. Start Renovo

Renovo starts once, when the host app's process starts. The place for that is your `Application` class. If your app has no `Application` class yet, create this file in the same package as `MainActivity.kt`:

```kotlin
// App.kt
import android.app.Application
import io.lightine.renovo.Renovo

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Renovo.init(this) {
            // Example. Replace it with the address of your own version file.
            versionUrl = "https://example.com/app/version.json"
        }
    }
}
```

`versionUrl` is the HTTPS address of your **version file**. The version file is a small JSON document that describes the latest version of your app. You write it in Step 4.

Register the class in `app/src/main/AndroidManifest.xml`. Add only the `android:name` attribute and keep everything else:

```xml
<application
    android:name=".App"
    ... >
```

You declare no permissions. Renovo adds the ones it needs to your manifest when you build.

### Step 3. Show the screens

`RenovoUpdateHost` draws your content and, on top of it, the update UI. Wrap your content in it, inside your theme:

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

- `AppTheme` stands for your `MaterialTheme`, and `AppContent` for your own screen. Keep your names.
- Put `RenovoUpdateHost` inside your theme. The update UI then uses your colors, fonts, and shapes.
- Nothing else is needed. The update UI follows the update by itself.
- If users stay for a long time in other activities too, wrap their content the same way. The update UI appears in the activity the user saw last.

### Step 4. Publish your first version file

The version file tells Renovo three things: the newest version code, its name, and where to download the APK. Two more fields let Renovo check the file and show its size.

Example. Replace the host, the file name, and the numbers with yours.

```json
{
  "versionCode": 2,
  "versionName": "1.1.0",
  "apkUrl": "https://example.com/app/app-1.1.0.apk",
  "sha256": "3b4c8f0e2a9d7c61e5f04b2a8d9c3e7f1a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d",
  "size": 25165824
}
```

| Field | What to put there |
|---|---|
| `versionCode` | The `versionCode` of the new APK, from its `build.gradle.kts`. Required. |
| `versionName` | The version name the user sees. Required. |
| `apkUrl` | The HTTPS address of the new APK. Required. |
| `sha256` | The SHA-256 hash of the APK. Renovo installs the file only if the hash matches. Recommended. |
| `size` | The size of the APK in bytes. Renovo uses it to show the download size and progress. Optional. |

Compute the hash and the size of your APK with these two commands:

```sh
shasum -a 256 app-release.apk   # the hash is the first word of the output (on Linux: sha256sum)
wc -c < app-release.apk         # the size in bytes
```

On Windows PowerShell, use `Get-FileHash app-release.apk -Algorithm SHA256` and `(Get-Item app-release.apk).Length`.

Save the file as `version.json`. Put it and the APK in one folder on your HTTPS server. The address of `version.json` must be exactly the `versionUrl` from Step 2.

### Step 5. Try it

1. Install the current version of your app, the one with Renovo, on a phone or an emulator. In this example its `versionCode` is 1.
2. In `app/build.gradle.kts`, raise `versionCode` to 2 and set `versionName` to `"1.1.0"`.
3. Build the new APK with the same signing key as the installed app, for example with `./gradlew assembleRelease`. The file is in `app/build/outputs/apk/release/`. Two debug builds made on the same computer share the debug key, so they also work for a test.
4. Compute the hash and the size, and write `version.json` as in Step 4.
5. Upload the APK first, then `version.json`. Open both addresses in a browser to check that they work.
6. Open the installed app.

What you should see:

1. At first, nothing. Renovo checks and downloads in the background.
2. When the download is verified, a dialog says "Ready to install". Tap "Install".
3. The first time, a dialog says "Allow this app to update itself" and explains the Android setting. Tap "Open settings", turn on the switch, and go back. The update continues.
4. Android may ask you to confirm. Then the app closes and the new version is installed. Open the app again: it is version 1.1.0.

Nothing happens? Add `logger = RenovoLogger { message, error -> Log.d("Renovo", message, error) }` to the `Renovo.init` block, with the imports `android.util.Log` and `io.lightine.renovo.RenovoLogger`. Then read Logcat: Renovo writes what it does and why it stops.

### What happens next

Renovo checks the version file every time the app starts, and every six hours in the background. When it finds a higher `versionCode`, it downloads the APK in the background, and an interrupted download resumes where it stopped. The same version is never downloaded twice, and files of older versions are deleted. Before it installs, it checks the hash, the package name, the version code, and the signing certificate, so only a newer build of your own app can be installed. The "Ready to install" dialog appears while the app is open, and the install starts only when the user taps "Install". If an install fails or the user cancels it, the current version keeps running with its data.

Next steps:

- To publish the next version, raise `versionCode`, build, and replace the two files. The [release checklist](#release-checklist) has a script.
- To make an update that the user cannot postpone, see [Forced updates](#forced-updates).
- To get the version information from your own server API, see [Option B: Your own backend](#option-b-your-own-backend).

## Path 2: Ready-made screens in an XML app

Follow this path if your app draws its screens with XML layouts and Views, for example with `AppCompatActivity`. At the end, your app checks for updates by itself and shows ready-made update screens. In this guide these screens are called the **update UI**. You write about 15 lines of Kotlin and one small JSON file. Your app needs no Compose code of its own.

You need an app with `minSdk` 24 or higher, `compileSdk` 37 or higher, Kotlin 2.4.0 or higher, and Java 17 or higher. You also need a place on the web that serves files over HTTPS.

### Step 1. Add the dependency

Renovo is on Maven Central. Add `renovo-ui` to your app module. It brings `renovo-core` with it. `renovo-core` checks, downloads, and installs. `renovo-ui` draws the screens. The screens are built with Jetpack Compose, and Gradle adds the Compose libraries for you. You do not need the Compose compiler plugin.

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.lightine.renovo:renovo-ui:0.1.0")
}
```

Check that `mavenCentral()` is listed under `repositories` in `settings.gradle.kts`. New Android Studio projects have it.

### Step 2. Start Renovo

Renovo starts once, when the host app's process starts. The place for that is your `Application` class. If your app has no `Application` class yet, create this file in the same package as `MainActivity.kt`:

```kotlin
// App.kt
import android.app.Application
import io.lightine.renovo.Renovo

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Renovo.init(this) {
            // Example. Replace it with the address of your own version file.
            versionUrl = "https://example.com/app/version.json"
        }
    }
}
```

`versionUrl` is the HTTPS address of your **version file**. The version file is a small JSON document that describes the latest version of your app. You write it in Step 4.

Register the class in `app/src/main/AndroidManifest.xml`. Add only the `android:name` attribute and keep everything else:

```xml
<application
    android:name=".App"
    ... >
```

You declare no permissions. Renovo adds the ones it needs to your manifest when you build.

### Step 3. Show the screens

Call `Renovo.attachUi(this)` in your activity's `onCreate`, after `setContentView`. It draws the update UI on top of your views.

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

- Call it every time the activity is created. After a rotation, the call brings the update UI back. A second call for the same activity does nothing.
- The activity must be a `ComponentActivity`. Every `AppCompatActivity` and `FragmentActivity` is one. A plain `android.app.Activity` is not supported, because Compose needs what `ComponentActivity` provides.
- The update UI takes its colors from the wallpaper on Android 12 and later, and uses the Material 3 default colors on older versions. You can give it your own colors later.
- If users stay for a long time in other activities too, call `attachUi` there as well. The update UI appears in the activity the user saw last.

### Step 4. Publish your first version file

The version file tells Renovo three things: the newest version code, its name, and where to download the APK. Two more fields let Renovo check the file and show its size.

Example. Replace the host, the file name, and the numbers with yours.

```json
{
  "versionCode": 2,
  "versionName": "1.1.0",
  "apkUrl": "https://example.com/app/app-1.1.0.apk",
  "sha256": "3b4c8f0e2a9d7c61e5f04b2a8d9c3e7f1a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d",
  "size": 25165824
}
```

| Field | What to put there |
|---|---|
| `versionCode` | The `versionCode` of the new APK, from its `build.gradle.kts`. Required. |
| `versionName` | The version name the user sees. Required. |
| `apkUrl` | The HTTPS address of the new APK. Required. |
| `sha256` | The SHA-256 hash of the APK. Renovo installs the file only if the hash matches. Recommended. |
| `size` | The size of the APK in bytes. Renovo uses it to show the download size and progress. Optional. |

Compute the hash and the size of your APK with these two commands:

```sh
shasum -a 256 app-release.apk   # the hash is the first word of the output (on Linux: sha256sum)
wc -c < app-release.apk         # the size in bytes
```

On Windows PowerShell, use `Get-FileHash app-release.apk -Algorithm SHA256` and `(Get-Item app-release.apk).Length`.

Save the file as `version.json`. Put it and the APK in one folder on your HTTPS server. The address of `version.json` must be exactly the `versionUrl` from Step 2.

### Step 5. Try it

1. Install the current version of your app, the one with Renovo, on a phone or an emulator. In this example its `versionCode` is 1.
2. In `app/build.gradle.kts`, raise `versionCode` to 2 and set `versionName` to `"1.1.0"`.
3. Build the new APK with the same signing key as the installed app, for example with `./gradlew assembleRelease`. The file is in `app/build/outputs/apk/release/`. Two debug builds made on the same computer share the debug key, so they also work for a test.
4. Compute the hash and the size, and write `version.json` as in Step 4.
5. Upload the APK first, then `version.json`. Open both addresses in a browser to check that they work.
6. Open the installed app.

What you should see:

1. At first, nothing. Renovo checks and downloads in the background.
2. When the download is verified, a dialog says "Ready to install". Tap "Install".
3. The first time, a dialog says "Allow this app to update itself" and explains the Android setting. Tap "Open settings", turn on the switch, and go back. The update continues.
4. Android may ask you to confirm. Then the app closes and the new version is installed. Open the app again: it is version 1.1.0.

Nothing happens? Add `logger = RenovoLogger { message, error -> Log.d("Renovo", message, error) }` to the `Renovo.init` block, with the imports `android.util.Log` and `io.lightine.renovo.RenovoLogger`. Then read Logcat: Renovo writes what it does and why it stops.

### What happens next

Renovo checks the version file every time the app starts, and every six hours in the background. When it finds a higher `versionCode`, it downloads the APK in the background, and an interrupted download resumes where it stopped. The same version is never downloaded twice, and files of older versions are deleted. Before it installs, it checks the hash, the package name, the version code, and the signing certificate, so only a newer build of your own app can be installed. The "Ready to install" dialog appears while the app is open, and the install starts only when the user taps "Install". If an install fails or the user cancels it, the current version keeps running with its data.

Next steps:

- To publish the next version, raise `versionCode`, build, and replace the two files. The [release checklist](#release-checklist) has a script.
- To give the update UI your app's colors, see [Theme and texts of the ready-made screens](#theme-and-texts-of-the-ready-made-screens).
- To make an update that the user cannot postpone, see [Forced updates](#forced-updates).
- To get the version information from your own server API, see [Option B: Your own backend](#option-b-your-own-backend).

## Path 3: Your own screens

Follow this path if you want to draw every update screen yourself. You use only `renovo-core`, which checks, downloads, verifies, and installs. Your screens read the current update state and call Renovo's actions, such as "install now". This path shows a Compose screen and an XML screen. Use the one that fits your app.

You need an app with `minSdk` 24 or higher, `compileSdk` 37 or higher, Kotlin 2.4.0 or higher, and Java 17 or higher. You also need a place on the web that serves files over HTTPS.

### Step 1. Add the dependency

Renovo is on Maven Central. Add `renovo-core` to your app module:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.lightine.renovo:renovo-core:0.1.0")

    // Compose apps only: for collectAsStateWithLifecycle in Step 3. Skip it if your app has it.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
}
```

Check that `mavenCentral()` is listed under `repositories` in `settings.gradle.kts`. New Android Studio projects have it.

### Step 2. Start Renovo

Renovo starts once, when the host app's process starts. The place for that is your `Application` class. If your app has no `Application` class yet, create this file in the same package as `MainActivity.kt`:

```kotlin
// App.kt
import android.app.Application
import io.lightine.renovo.Renovo

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Renovo.init(this) {
            // Example. Replace it with the address of your own version file.
            versionUrl = "https://example.com/app/version.json"
        }
    }
}
```

`versionUrl` is the HTTPS address of your **version file**. The version file is a small JSON document that describes the latest version of your app. You write it in Step 4.

Register the class in `app/src/main/AndroidManifest.xml`. Add only the `android:name` attribute and keep everything else:

```xml
<application
    android:name=".App"
    ... >
```

You declare no permissions. Renovo adds the ones it needs to your manifest when you build.

### Step 3. Show the screens

`Renovo.state` always holds the current update state, an `UpdateState`. For example, `Downloading` while the APK downloads, and `ReadyToInstall` when it is verified. It is a `StateFlow`, a value that changes over time and that your screen can observe. It lives as long as the app's process, so it survives rotation.

Your screen does two things:

- It shows a message for the current state.
- It offers the one button that fits the state, such as "Install", and calls the matching Renovo action.

This file turns a state into a message and a button. Both screens below use it. The `when` blocks have no `else` for the messages, so the compiler tells you if a future version of Renovo adds a state.

```kotlin
// UpdateTexts.kt
import io.lightine.renovo.DownloadProgress
import io.lightine.renovo.Renovo
import io.lightine.renovo.UpdateState
import io.lightine.renovo.UpdateStep

/** The label of a button and what the button does. */
class UpdateAction(val label: String, val run: () -> Unit)

/** One sentence for the user about the current state. */
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
    is UpdateState.Required -> "Version ${state.info.versionName} is required. ${describe(state.step)}"
}

/** The same for a forced update, which the user cannot postpone. */
fun describe(step: UpdateStep): String = when (step) {
    UpdateStep.WaitingForUser -> "Tap Update now."
    is UpdateStep.Downloading -> "Downloading${percent(step.progress)}."
    is UpdateStep.WaitingForNetwork -> "Waiting for an allowed network."
    UpdateStep.PermissionNeeded -> "Allow this app to install updates in the settings."
    UpdateStep.Installing -> "Installing..."
    is UpdateStep.Failed -> "The update failed: ${step.reason}."
    UpdateStep.Unsupported -> "This device cannot install it. Ask whoever installed the app."
}

/** The one button that fits the state, or null when the user has nothing to do. */
fun actionFor(state: UpdateState): UpdateAction? = when (state) {
    is UpdateState.Available -> UpdateAction("Download") { Renovo.startDownload() }
    is UpdateState.ReadyToInstall -> UpdateAction("Install") { Renovo.installIfReady() }
    is UpdateState.PermissionNeeded -> UpdateAction("Open settings") { Renovo.installIfReady() }
    is UpdateState.Failed ->
        if (state.info == null) {
            UpdateAction("Check again") { Renovo.checkNow() }
        } else {
            UpdateAction("Try again") { Renovo.startDownload() }
        }
    is UpdateState.Required -> when (val step = state.step) {
        UpdateStep.WaitingForUser -> UpdateAction("Update now") { Renovo.startDownload() }
        UpdateStep.PermissionNeeded -> UpdateAction("Open settings") { Renovo.installIfReady() }
        is UpdateStep.Failed -> UpdateAction("Try again") { Renovo.startDownload() }
        is UpdateStep.WaitingForNetwork ->
            if (step.canUseMobileData) UpdateAction("Use mobile data") { Renovo.useMobileDataOnce() } else null
        else -> null
    }
    else -> null
}

private fun percent(progress: DownloadProgress): String =
    progress.totalBytes?.let { total -> ", ${progress.bytesDownloaded * 100 / total}%" } ?: ""
```

What the actions do:

- `Renovo.startDownload()` starts the download, or tries again after a failure. If the file is already verified, it starts the install.
- `Renovo.installIfReady()` installs the verified file. When the user must first allow installs from your app, it opens that Android setting instead.
- `Renovo.useMobileDataOnce()` lets a waiting forced update download over mobile data, for this version only.
- `Renovo.checkNow()` checks for a new version now.

#### With Jetpack Compose

This composable shows the message, the button, and a "Check for updates" link:

```kotlin
// UpdateSection.kt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.lightine.renovo.Renovo
import io.lightine.renovo.UpdateState

@Composable
fun UpdateSection(state: UpdateState, modifier: Modifier = Modifier) {
    val action = actionFor(state)
    Column(modifier.padding(16.dp)) {
        Text(describe(state))
        if (action != null) {
            Button(onClick = action.run) { Text(action.label) }
        }
        TextButton(onClick = { Renovo.checkNow() }) { Text("Check for updates") }
    }
}
```

The activity reads the state and places the section above your content. During a forced update it shows only the section, so the app cannot be used until the update is installed:

```kotlin
// MainActivity.kt
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.lightine.renovo.Renovo
import io.lightine.renovo.UpdateState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val state by Renovo.state.collectAsStateWithLifecycle()
                if (state is UpdateState.Required) {
                    // A forced update: nothing but the update section until it is installed.
                    UpdateSection(state, Modifier.fillMaxSize())
                } else {
                    Column {
                        UpdateSection(state)
                        AppContent()
                    }
                }
            }
        }
    }
}
```

`AppTheme` stands for your `MaterialTheme`, and `AppContent` for your own screen. Keep your names.

#### With XML layouts

The layout has a status line, an action button, a "Check for updates" button, and a container for your app's own views:

```xml
<!-- res/layout/activity_main.xml -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        android:id="@+id/update_status"
        android:layout_width="match_parent"
        android:layout_height="wrap_content" />

    <Button
        android:id="@+id/update_action"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:visibility="gone" />

    <Button
        android:id="@+id/update_check"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Check for updates" />

    <!-- Your app's own views go here. -->
    <FrameLayout
        android:id="@+id/app_content"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />
</LinearLayout>
```

The activity listens to the state with an `UpdateListener`. Renovo calls the listener on the main thread: once with the current state when you add it, then after every change. Add it in `onStart` and remove it in `onStop`, so it runs only while the activity is visible.

```kotlin
// MainActivity.kt
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.lightine.renovo.Renovo
import io.lightine.renovo.UpdateListener
import io.lightine.renovo.UpdateState

class MainActivity : AppCompatActivity() {
    private lateinit var appContent: View
    private lateinit var updateStatus: TextView
    private lateinit var updateAction: Button

    private val listener = UpdateListener { state -> render(state) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        appContent = findViewById(R.id.app_content)
        updateStatus = findViewById(R.id.update_status)
        updateAction = findViewById(R.id.update_action)
        findViewById<Button>(R.id.update_check).setOnClickListener { Renovo.checkNow() }
    }

    override fun onStart() {
        super.onStart()
        Renovo.addListener(listener)
    }

    override fun onStop() {
        Renovo.removeListener(listener)
        super.onStop()
    }

    private fun render(state: UpdateState) {
        updateStatus.text = describe(state)
        val action = actionFor(state)
        updateAction.visibility = if (action == null) View.GONE else View.VISIBLE
        updateAction.text = action?.label
        updateAction.setOnClickListener { action?.run?.invoke() }
        // A forced update: hide the app's own views until the update is installed.
        appContent.visibility = if (state is UpdateState.Required) View.GONE else View.VISIBLE
    }
}
```

If you prefer coroutines, collect `Renovo.state` inside `lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { ... } }` instead of using a listener.

#### What your screens must do

The ready-made screens do these things for you. Your own screens must do them too:

- **Block the app during a forced update.** `UpdateState.Required` means the user cannot postpone this update. Both examples above hide the app's own content while it is set.
- **Offer the install.** Downloads start by themselves, so most users first meet `ReadyToInstall`. Show it, and call `Renovo.installIfReady()` when the user agrees.
- **Keep background failures quiet.** A failure has `userInitiated = false` when nobody tapped anything, for example a background check without a network. Do not show a pop-up for it. Renovo retries network failures by itself.
- **Show a download the user started.** `UpdateState.Downloading.userInitiated` is `true` for those.

### Step 4. Publish your first version file

The version file tells Renovo three things: the newest version code, its name, and where to download the APK. Two more fields let Renovo check the file and show its size.

Example. Replace the host, the file name, and the numbers with yours.

```json
{
  "versionCode": 2,
  "versionName": "1.1.0",
  "apkUrl": "https://example.com/app/app-1.1.0.apk",
  "sha256": "3b4c8f0e2a9d7c61e5f04b2a8d9c3e7f1a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d",
  "size": 25165824
}
```

| Field | What to put there |
|---|---|
| `versionCode` | The `versionCode` of the new APK, from its `build.gradle.kts`. Required. |
| `versionName` | The version name the user sees. Required. |
| `apkUrl` | The HTTPS address of the new APK. Required. |
| `sha256` | The SHA-256 hash of the APK. Renovo installs the file only if the hash matches. Recommended. |
| `size` | The size of the APK in bytes. Renovo uses it to report the total size in `DownloadProgress`. Optional. |

Compute the hash and the size of your APK with these two commands:

```sh
shasum -a 256 app-release.apk   # the hash is the first word of the output (on Linux: sha256sum)
wc -c < app-release.apk         # the size in bytes
```

On Windows PowerShell, use `Get-FileHash app-release.apk -Algorithm SHA256` and `(Get-Item app-release.apk).Length`.

Save the file as `version.json`. Put it and the APK in one folder on your HTTPS server. The address of `version.json` must be exactly the `versionUrl` from Step 2.

### Step 5. Try it

1. Install the current version of your app, the one with Renovo, on a phone or an emulator. In this example its `versionCode` is 1.
2. In `app/build.gradle.kts`, raise `versionCode` to 2 and set `versionName` to `"1.1.0"`.
3. Build the new APK with the same signing key as the installed app, for example with `./gradlew assembleRelease`. The file is in `app/build/outputs/apk/release/`. Two debug builds made on the same computer share the debug key, so they also work for a test.
4. Compute the hash and the size, and write `version.json` as in Step 4.
5. Upload the APK first, then `version.json`. Open both addresses in a browser to check that they work.
6. Open the installed app.

What you should see, with the screens from Step 3:

1. "Checking for updates...", then "Downloading version 1.1.0" with a percentage.
2. "Version 1.1.0 is ready to install." with an "Install" button. Tap it.
3. The first time, "Allow this app to install updates in the settings." with an "Open settings" button. Tap it, turn on the switch, and go back. The install continues by itself.
4. Android may ask you to confirm. Then the app closes and the new version is installed. Open the app again: it is version 1.1.0, and the screen says "You have the latest version."

Nothing happens? Add `logger = RenovoLogger { message, error -> Log.d("Renovo", message, error) }` to the `Renovo.init` block, with the imports `android.util.Log` and `io.lightine.renovo.RenovoLogger`. Then read Logcat: Renovo writes what it does and why it stops.

### What happens next

Renovo checks the version file every time the app starts, and every six hours in the background. When it finds a higher `versionCode`, it downloads the APK in the background, and an interrupted download resumes where it stopped. The same version is never downloaded twice, and files of older versions are deleted. Before it installs, it checks the hash, the package name, the version code, and the signing certificate, so only a newer build of your own app can be installed. The install starts only when your screen calls `Renovo.installIfReady()` or `Renovo.startDownload()` while the app is open. If an install fails or the user cancels it, the current version keeps running with its data.

Next steps:

- To publish the next version, raise `versionCode`, build, and replace the two files. The [release checklist](#release-checklist) has a script.
- Every state, step, and failure reason is listed in [Update states, steps, and failure reasons](#update-states-steps-and-failure-reasons).
- A detailed blocking screen for a forced update is in [Forced updates](#forced-updates).
- To get the version information from your own server API, see [Option B: Your own backend](#option-b-your-own-backend).

More in the wiki: Core without the UI.

## Where the version information comes from

Renovo needs to know the latest version: its version code, its name, and where its APK is. There are two ways to tell it. Set exactly one of them in `Renovo.init`.

| Option | Setting | Choose it when |
|---|---|---|
| [A. A version file on any HTTPS host](#option-a-a-version-file-on-any-https-host) | `versionUrl` | You have no backend, or you want nothing to run on the server. A static file is enough. |
| [B. Your own backend](#option-b-your-own-backend) | `versionSource` | Your server already knows the latest version, or the answer depends on the user. |

### Option A: A version file on any HTTPS host

The version file is a JSON document that describes the latest version. Renovo downloads it from `versionUrl` at every check. It can be a static file on any server that speaks HTTPS.

Example. This version file uses every field. Replace the host, the file name, and the numbers with yours.

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

```kotlin
Renovo.init(this) {
    versionUrl = "https://example.com/app/version.json"
}
```

#### Fields

Only the first three fields are required.

| Field | Required | Meaning |
|---|---|---|
| `versionCode` | yes | Version code of the new APK, the same number as `versionCode` in its `build.gradle.kts`. A whole number of 1 or more. Always raise it: Renovo installs only a higher version code than the installed one. |
| `versionName` | yes | Version name shown to the user. A string. |
| `apkUrl` | yes | HTTPS URL of the APK. |
| `sha256` | no | SHA-256 hash of the APK, 64 hexadecimal characters, upper or lower case. When present, a file with another hash is never installed. |
| `size` | no | APK size in bytes. Used only for display, such as the download size and the progress. A positive number. |
| `mandatory` | no, default `false` | `true` makes this update a forced update for every older installed version. |
| `minSupportedVersionCode` | no | Installed versions below this version code get a forced update. Versions at or above it get an optional one. |
| `releaseNotes` | no | One string for every language, or an object with one string per language code, such as `en` or `pt-BR`. See [Release notes](#release-notes). |

A **forced update** is an update the user cannot postpone. The host app stays blocked until it is installed. See [Forced updates](#forced-updates).

#### Compute the hash and the size

Add `sha256`. Without it, Renovo still checks the package name, the version code, and the signing certificate. The hash adds a check that the file is exactly the one you published.

- **macOS:** `shasum -a 256 app-release.apk` prints the hash as the first word. `wc -c < app-release.apk` prints the size in bytes.
- **Linux:** `sha256sum app-release.apk` and `wc -c < app-release.apk`.
- **Windows PowerShell:** `Get-FileHash app-release.apk -Algorithm SHA256` prints the hash in capitals, which Renovo accepts. `(Get-Item app-release.apk).Length` prints the size.

#### Hosting

Any server that serves files over HTTPS with a certificate the device trusts works. The APK and the version file can be on different hosts. The `Content-Type` of the version file does not matter.

- **A plain web server** or any static file hosting. Put `version.json` and the APK in one folder. Static file servers already send an ETag or a Last-Modified date and answer range requests, which Renovo uses to resume a download.
- **Object storage**, meaning any bucket with an S3-compatible API. Make the two objects publicly readable, or put a CDN in front of the bucket, and use their HTTPS URLs. For a private bucket, see [Authentication](#authentication).
- **GitHub Releases of a public repository.** Attach the APK and `version.json` to each release. Set `versionUrl` to `https://github.com/OWNER/REPO/releases/latest/download/version.json`. This address always points to the newest release that is not a draft or a pre-release. In the file, set `apkUrl` to that release's APK, such as `https://github.com/OWNER/REPO/releases/download/v1.8.0/app-1.8.0.apk`. GitHub redirects both to its download host over HTTPS, sends an ETag, and answers range requests, so checks and resumed downloads work. Private repositories do not work: their files need a token, and Renovo sends no headers with the APK request.

More in the wiki: Hosting the version file.

#### Release checklist

Do this for each new version:

1. **Raise `versionCode`** in the app's `build.gradle.kts`. It must be higher than every version in use.
2. **Build a signed release APK** with the same signing key as before, for example with `./gradlew assembleRelease`. The file is in `app/build/outputs/apk/release/`. If you build app bundles, build a universal APK instead.
3. **Compute the hash and the size.**
4. **Write `version.json`** with the new `versionCode`, `versionName`, `apkUrl`, `sha256`, and `size`. Set `mandatory` or `minSupportedVersionCode` if this is a forced update.
5. **Upload the APK first, then `version.json`.** In this order, a device never reads a file that points to an APK that is not there yet.
6. **Check both URLs** in a browser or with `curl -I`. Both must answer `200` over HTTPS.

A small script for steps 3 and 4 on macOS. On Linux, replace `shasum -a 256` with `sha256sum`. The version numbers and the address are examples.

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

#### Caching and ETag

Renovo sends the ETag of the last version file it read, so the server answers `304 Not Modified` with no body when nothing changed, and a server without ETags still works. Renovo turns off the HTTP cache on the device, so if a CDN caches `version.json`, give it a short cache time such as `Cache-Control: max-age=300`, or clear the CDN cache after each release.

### Option B: Your own backend

Choose this option when your server already knows the latest version, or when the answer depends on the user, for example a test group that gets builds earlier. Renovo then asks your code instead of reading a version file.

You implement `VersionSource`, a function that returns an `UpdateInfo`, and set it as `versionSource` instead of `versionUrl`. `UpdateInfo` holds the same fields as the version file. Its constructor throws `IllegalArgumentException` for a value that is not allowed, such as an `apkUrl` that is not HTTPS or a `sha256` that is not 64 hexadecimal characters.

Example. This source calls `https://api.example.com/app/latest` with the platform's `HttpURLConnection`. It reads a JSON answer like this one. Change the address and the field names to match your API.

```json
{ "build": 42, "name": "1.8.0", "url": "https://example.com/app/app-1.8.0.apk", "sha256": null, "force": false, "minBuild": 38, "notes": "- Bug fixes" }
```

```kotlin
// BackendVersionSource.kt
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

Set it in `Renovo.init`, in your `Application` class, instead of `versionUrl`:

```kotlin
Renovo.init(this) {
    versionSource = BackendVersionSource("https://api.example.com/app/latest")
}
```

If your app already has an HTTP client with suspend functions, call it directly and map its answer. In this example, `myApi.latestVersion()` stands for your own call:

```kotlin
Renovo.init(this) {
    versionSource = VersionSource {
        val latest = myApi.latestVersion()
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

#### How errors map

- Renovo calls `latest()` for every check: at app start, every `checkInterval`, and on `Renovo.checkNow()`. There is no ETag here, so keep the call cheap.
- To report a failed check, throw. An `IllegalArgumentException` counts as an answer that could not be read (`FailureReason.Parse`).
- Any other exception, such as an `IOException`, counts as a network failure (`FailureReason.Network`). A background check is then retried later with growing delays.
- `versionHeaders` is not used with a `versionSource`. Your own client adds whatever headers it needs.

#### Hand over version information you already have

If the host app already receives version information, for example in its own sync response, pass it to Renovo with `Renovo.submit`. It has the same effect as a successful check, and no extra request is made.

```kotlin
Renovo.submit(UpdateInfo(versionCode = 42, versionName = "1.8.0", apkUrl = "https://example.com/app/app-1.8.0.apk"))
```

`Renovo.init` still needs a `versionUrl` or a `versionSource`, because Renovo also checks on its own. There is no mode that only uses `submit`.

### Authentication

If the version file needs a token, set `versionHeaders`. Renovo calls it before every request to the version file, so a token that changes is always current. In this example, `tokens.current()` stands for your own code:

```kotlin
Renovo.init(this) {
    versionUrl = "https://example.com/app/version.json"
    versionHeaders = { mapOf("Authorization" to "Bearer ${tokens.current()}") }
}
```

- The headers go to the version file only. If the server redirects to another host or port, Renovo drops them, so a token never leaks to a third party.
- **The APK request carries no custom headers.** The `apkUrl` must work without them. Use a public URL, or a signed URL that expires, such as a presigned URL from your object storage or a CDN token URL. Give a signed URL enough lifetime for a slow download, for example a day.
- Renovo recognizes a file by its version code and hash, not by its URL. A signed URL that changes at every check does not restart a download.
- With a `versionSource`, `versionHeaders` is not used. Your own client adds the headers, and it can return a freshly signed `apkUrl` at every check.

More in the wiki: Signing and verification.

## Going further

Each section below stands on its own. All settings go in the block of `Renovo.init`. The [Settings](#settings) table lists every one.

### Forced updates

A forced update is an update the user cannot postpone. Use it after a security fix, or when your server no longer accepts old versions.

**Declare it in the version file.** There are two ways. Use the one that matches your rule.

Example. Force everyone onto version 42:

```json
{ "versionCode": 42, "versionName": "1.8.0", "apkUrl": "https://example.com/app/app-1.8.0.apk", "mandatory": true }
```

Example. Force only old versions. Devices on version code 37 or lower must update. Devices on 38 to 41 get an optional update:

```json
{ "versionCode": 42, "versionName": "1.8.0", "apkUrl": "https://example.com/app/app-1.8.0.apk", "minSupportedVersionCode": 38 }
```

`minSupportedVersionCode` works even when `mandatory` is `false`. A device that skipped several releases is still blocked. With a `versionSource`, set `mandatory` or `minSupportedVersionCode` in the `UpdateInfo`.

**What the user sees with the update UI.** A full screen covers the host app. It cannot be dismissed, and Back and Esc do nothing. The download starts in the background and the screen shows its progress. When the file is ready, the user taps "Update now" and Android installs it. If the download waits for an allowed network, the screen says so and, when allowed, offers "Download with mobile data". If the device does not allow the app to update itself, the screen says to ask whoever installed the app.

**What the host app should know.**

- With the update UI, you do nothing. The forced screen appears on its own, in every activity that has the update UI.
- `canInstallNow` still applies. While it returns `false`, a forced update waits too, so let it return `true` as soon as your data is safe.
- The host app keeps running below the forced screen. If your backend no longer accepts the old version, expect its requests to fail until the user updates.
- On a device that cannot install (`UpdateStep.Unsupported`), the user cannot get past the screen. Plan another way to deliver the APK there.

**With your own screens**, `UpdateState.Required` means "block the app". It stays set through download and install. Its `step`, an `UpdateStep`, says what is happening, and the [UpdateStep table](#updatestep) gives the action for each step. Hide the host app's content while it is set, as the screens in [Path 3](#path-3-your-own-screens) do. `Renovo.useMobileDataOnce()` works only when the step is `WaitingForNetwork` with `canUseMobileData = true`.

More in the wiki: Forced updates.

### Release notes

Release notes tell the user what is new. The update UI shows them in the "Update available" dialog and on the forced update screen.

Example. One text for every language:

```json
"releaseNotes": "- Sync is **faster**\n- Bug fixes"
```

Example. One text per language code:

```json
"releaseNotes": {
  "az": "- Sinxronizasiya **sürətləndirildi**\n- Xətalar aradan qaldırıldı",
  "en": "- Sync is **faster**\n- Bug fixes"
}
```

- **Language codes.** A key is a language code, optionally with a region, such as `pt` or `pt-BR`. Case does not matter, and `pt_BR` means the same as `pt-BR`.
- **Which text is picked.** For a phone set to Brazilian Portuguese, Renovo picks `pt-BR`, then `pt`, then any other `pt-` key in file order, then `en` (or the first `en-` key when there is no plain `en`), then the first entry. A phone set to plain `pt` gets `pt`, or the first `pt-` key when there is no plain `pt`.
- **Which language counts.** The update UI uses the host app's language as its activity sees it, so a per-app language set on Android 13 and later is respected. The "Update ready" notification has no activity, so its texts come from the application context. On Android 12 and older, a language set only through AppCompat's per-app language support reaches activities, not the application context, so the notification may use the device language.
- **Formatting.** Release notes may use `**bold**`, `*italic*`, lines that start with `- ` as bullets, `[text](https://...)` links, and line breaks. Everything else is shown as typed. Links open only if they are HTTPS.

In Kotlin, for a `versionSource` or `Renovo.submit`: `ReleaseNotes("- Bug fixes")` is one text for every language. `ReleaseNotes.of("en" to "...", "az" to "...")` is one text per language code, and the first entry is the last fallback. `notes.forLanguage("pt-BR")` returns the text Renovo would pick for that language tag.

### Choose when an install may start

An install closes the host app. If that would lose work, for example while data is not yet sent to your server, set `canInstallNow`. In this example, `repository.hasUnsentData()` stands for your own code:

```kotlin
Renovo.init(this) {
    versionUrl = "https://example.com/app/version.json"
    canInstallNow = { !repository.hasUnsentData() }
}
```

- Renovo calls it on the main thread right before every install, optional or forced. Keep it fast. An exception counts as `false`.
- While it returns `false`, the update stays ready (`ReadyToInstall`, or `Required` waiting for the user).
- The update UI shows its "Ready to install" dialog only while `canInstallNow` returns `true`. It asks when the dialog is about to show and every time the activity resumes. Without the update UI, Renovo asks again at the next `Renovo.installIfReady()`.

**Download only when the user asks.** By default, Renovo downloads a newer version as soon as it finds one. With `autoDownload = false`, it waits. The state is then `Available` (or `Required` waiting for the user) until `Renovo.startDownload()`, and the update UI shows its "Update available" dialog.

### Notifications

When a download becomes ready to install and no activity of the host app is in the foreground, Renovo shows one "Update ready" notification. Tapping it opens the host app's launcher activity, where the update UI shows "Ready to install".

- **It never installs directly**, because an install must start from the foreground. Renovo removes the notification when the update is installed or replaced by a newer version.
- **Only when allowed.** Renovo shows it only if `notifications` is `true` and Android already allows notifications for the host app. Otherwise nothing is shown, and the install is offered at the next app start.
- **Renovo never asks for the permission on its own.** On Android 13 and later, notifications need a runtime permission. Your app picks the moment, for example after the first successful sync, and calls:

  ```kotlin
  Renovo.requestNotificationPermission(activity)
  ```

  On Android 12L and earlier it does nothing. If the permission is already granted, it does nothing either.
- **Download progress.** A download the user starts runs as a foreground job, and Android requires a visible notification for it. That one follows Android's rules and is not controlled by `notifications`.
- **Turn the "Update ready" notification off** with `notifications = false`.

### Networks and the check interval

Optional and forced updates each have their own network setting. Example:

```kotlin
Renovo.init(this) {
    versionUrl = "https://example.com/app/version.json"
    optionalDownloadNetwork = Network.UnmeteredOnly // optional updates only on Wi-Fi and similar
    forcedDownloadNetwork = Network.NotRoaming // forced updates on any network except roaming
    allowMobileDataOverride = true // a waiting forced update may offer mobile data
    checkInterval = 12.hours // import kotlin.time.Duration.Companion.hours
}
```

- `Network.Any` - any connected network, including mobile data. The default for both.
- `Network.UnmeteredOnly` - a network that is not billed by usage, such as most Wi-Fi.
- `Network.NotRoaming` - any network except mobile data while roaming.

When a forced update waits for an allowed network, the user can choose to download with mobile data, for this version only. This needs `allowMobileDataOverride = true` (the default) and a `forcedDownloadNetwork` other than `Network.Any`.

**Check interval.** The default is 6 hours and the minimum is 15 minutes, the shortest period WorkManager allows. WorkManager is the Android library that runs Renovo's background work. Renovo also checks at every app start and on `Renovo.checkNow()`. Android decides the exact moment of a background check, and an app that the user force-stopped runs no background work until it is opened again.

### Theme and texts of the ready-made screens

The update UI has these screens: "Update available", "Downloading update", "Ready to install", "Allow this app to update itself", "Update not installed", and the full "Update required" screen for a forced update. Every text is in English and Azerbaijani and follows the device language. Other languages show English.

#### Colors

- **`RenovoUpdateHost` uses your theme.** Inside your `MaterialTheme`, the update UI uses your colors, typography, and shapes, including your choice of light or dark. The `ui { }` settings are then ignored, because your theme already decided.
- **`colorSchemes` replaces the inherited colors.** Pass a light and a dark Material 3 color scheme. The `ui { }` settings then choose between them.
- **`attachUi` has nothing to inherit.** It uses `ui { themeMode }` to choose light or dark, then your `colorSchemes` if you passed them, then dynamic color (colors from the wallpaper) on Android 12 and later, then the Material 3 baseline colors.
- One blind spot: a host theme that uses exactly the Material 3 baseline light scheme looks like no theme at all. The update UI then follows the `ui { }` settings.

Example. The colors are placeholders:

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
// XML layouts:
Renovo.attachUi(this, colorSchemes = schemes)
```

#### Dark mode and dynamic color

Set these in the `ui { }` block of `Renovo.init`. They apply when no theme is inherited.

```kotlin
ui {
    themeMode = ThemeMode.Dark // System (default), Light, or Dark
    dynamicColor = false // default true: wallpaper colors on Android 12 and later
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
- **To add a language**, define every string in your own folder for it, such as `res/values-de/strings.xml`.
- All names are in [`renovo-ui/src/main/res/values/strings.xml`](renovo-ui/src/main/res/values/strings.xml) and [`renovo-core/src/main/res/values`](renovo-core/src/main/res/values).
- Resource names are not covered by the API compatibility check. Check your overrides after you upgrade Renovo.

#### Icons

The notification small icons are two drawables: `renovo_ic_update_ready` (update ready) and `renovo_ic_download` (download progress). To use your own, add a drawable with the same name to your app, for example `res/drawable/renovo_ic_update_ready.xml`. Android draws a notification small icon as a mask, so only the shape matters. The update UI also uses `renovo_ic_download` in its dialogs and on the forced update screen, so replacing it changes that icon too.

More in the wiki: The update UI.

### Logging

Logging is off until you set a `logger`. Messages never contain personal data. A logger that throws cannot break Renovo.

```kotlin
Renovo.init(this) {
    versionUrl = "https://example.com/app/version.json"
    logger = RenovoLogger { message, error -> Log.d("Renovo", message, error) }
}
```

More in the wiki: Troubleshooting.

## Reference

### Version file fields

The fields are listed in the [Fields](#fields) table of Option A. These rules apply when Renovo reads the file:

- **Unknown fields are ignored**, so the format can grow.
- **A file that cannot be read** is a failed check, not a crash. Renovo tries again at the next check. This covers broken JSON, a missing required field, a plain HTTP `apkUrl`, and a file that is larger than 1 MiB or not in UTF-8.
- **HTTPS only**, for the version file and for the APK. The server may redirect, up to five times, as long as every step is HTTPS.
- **The body is read as JSON** whatever its `Content-Type`.

### Settings

All settings go in the block of `Renovo.init`, which you call once in `Application.onCreate`. Set exactly one of `versionUrl` and `versionSource`. Every other setting has a default. Invalid settings throw `IllegalArgumentException`. A second call to `init` is ignored.

| Setting | Default | What it does |
|---|---|---|
| `versionUrl` | none | HTTPS URL of the version file. `versionUrl = "https://example.com/app/version.json"` |
| `versionSource` | none | Your own source of version information, instead of a version file. See [Option B](#option-b-your-own-backend). |
| `versionHeaders` | no headers | Extra headers for the version file request. `versionHeaders = { mapOf("Authorization" to "Bearer ${tokens.current()}") }` |
| `checkInterval` | `6.hours` | Time between background checks. The minimum is 15 minutes. `checkInterval = 12.hours` |
| `autoDownload` | `true` | `true` downloads a newer version without waiting for the user. `false` waits for `Renovo.startDownload()`. `autoDownload = false` |
| `optionalDownloadNetwork` | `Network.Any` | Networks an optional update may download on: `Any`, `UnmeteredOnly`, or `NotRoaming`. `optionalDownloadNetwork = Network.UnmeteredOnly` |
| `forcedDownloadNetwork` | `Network.Any` | The same choice for forced updates. `forcedDownloadNetwork = Network.NotRoaming` |
| `allowMobileDataOverride` | `true` | A forced update that waits for an allowed network lets the user download with mobile data instead. `allowMobileDataOverride = false` |
| `notifications` | `true` | Shows the "Update ready" notification, only when notifications are already allowed. `notifications = false` |
| `canInstallNow` | `{ true }` | Asked right before every install. Return `false` while an install would lose work. `canInstallNow = { !repository.hasUnsentData() }` |
| `logger` | `null` (silent) | Receives Renovo's diagnostic messages. `logger = RenovoLogger { message, error -> Log.d("Renovo", message, error) }` |
| `ui { }` | `themeMode = ThemeMode.System`, `dynamicColor = true` | Settings for the update UI. `ui { themeMode = ThemeMode.Dark }` |

Example. Every setting at once. `tokens.current()` and `repository.hasUnsentData()` stand for your own code:

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

### Update states, steps, and failure reasons

#### Actions

| Call | What it does | Example |
|---|---|---|
| `Renovo.checkNow()` | Checks for a new version now. | `checkButton.setOnClickListener { Renovo.checkNow() }` |
| `Renovo.startDownload()` | Starts the download of the available version, or tries again after a failure. If the file is already downloaded and verified, it starts the install. | `updateButton.setOnClickListener { Renovo.startDownload() }` |
| `Renovo.installIfReady()` | Installs the downloaded and verified version after asking `canInstallNow`. When the user must first allow installs, it opens that setting. Does nothing when no verified file exists. | `installButton.setOnClickListener { Renovo.installIfReady() }` |
| `Renovo.submit(info)` | Hands over version information you already have. | `Renovo.submit(info)` |
| `Renovo.useMobileDataOnce()` | Lets a waiting forced update download with mobile data, for this version only. | `mobileDataButton.setOnClickListener { Renovo.useMobileDataOnce() }` |
| `Renovo.requestNotificationPermission(activity)` | Asks the user to allow notifications. | `Renovo.requestNotificationPermission(this)` |
| `Renovo.addListener(listener)` / `Renovo.removeListener(listener)` | Calls a listener on the main thread with every state. | `Renovo.addListener { state -> render(state) }` |

#### `UpdateState`

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

#### `UpdateStep`

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

#### `FailureReason`

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

### What Renovo adds to your manifest

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

### Behavior per Android version

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

### Known limits

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
- **Do not publish an APK from Android Studio's Run button.** Run marks the APK as test-only (`android:testOnly`), and Android installs such an APK only through adb, so Renovo cannot install it. Build the APK you publish, also for a test, with `./gradlew assembleDebug` or `./gradlew assembleRelease` from the command line.

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

### Google developer verification

Google is rolling out developer verification for apps on certified Android devices. A developer registers with Google, and registers each app's package name and signing key. On a device where verification is enforced, an app from an unregistered developer fails to install, and an update to an unregistered app fails too.

Enforcement started on 30 September 2026 in a first group of countries, and Google plans to extend it to all certified devices from 2027. Google's page has the current dates, the countries, and the exceptions, such as limited distribution accounts for students and hobbyists.

What it means for you:

- **The owner of the host app must register** the package name and the signing key, as a developer who distributes outside the Play Store. Renovo cannot do it for them. Do it before you ship the first Renovo update.
- Renovo reports a verification failure as `FailureReason.InstallRejected`, and on Android 16.1 (API 36.1) and later writes the reason to your `logger`.
- Google's page: [developer.android.com/developer-verification](https://developer.android.com/developer-verification).

More in the wiki: Signing and verification.

### Requirements

| What | Value |
|---|---|
| Android | `minSdk` 24 (Android 7.0) or higher |
| `compileSdk` of the host app | 37 or higher. The libraries' dependencies require it, and the Android Gradle Plugin stops the build otherwise. |
| Kotlin | 2.4.0 or higher. See below. |
| Java | The class files are Java 17 bytecode. Set the host app's `sourceCompatibility` and `targetCompatibility` to Java 17 or higher. |
| Compose (`renovo-ui` only) | Built with Compose 1.12.1 and Material 3 1.4.0. Gradle raises your app to these versions if it uses older ones. |
| Distribution | Not on the Play Store. |

**Kotlin version.** Renovo is compiled with Kotlin 2.4.21, so its class files carry Kotlin metadata version 2.4. Kotlin documents compatibility in its [evolution principles](https://kotlinlang.org/docs/kotlin-evolution-principles.html): a newer compiler reads the binaries of older ones, and a compiler can usually, but with no guarantee, read the binaries of the next language release. So build the host app with **Kotlin 2.4.0 or higher**. Kotlin 2.3 can usually read Renovo, but Kotlin does not guarantee it. Kotlin 2.2 and older are not supported. With Compose, keep the Kotlin Compose compiler plugin at the same version as Kotlin.

**Modules and dependencies.** Renovo has two modules, and they always have the same version.

| Module | What it has | Depends on |
|---|---|---|
| `renovo-core` | Checking, downloading, verifying, installing, and the update state. No screens. | WorkManager, `androidx.core`, and kotlinx.coroutines, which most apps already have. |
| `renovo-ui` | The update UI, built with Jetpack Compose. Works in Compose apps and in apps without Compose. | `renovo-core`, Jetpack Compose, Material 3, `androidx.activity`, and the Compose window size libraries. |

### Versioning, changelog, license

- **Versioning.** Renovo follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html). `renovo-core` and `renovo-ui` always have the same version, so use the same version for both. The public API is checked for accidental breaking changes between releases. More in the wiki: Versioning policy.
- **Changelog.** [`CHANGELOG.md`](CHANGELOG.md) lists every change.
- **API reference.** Every public class, function, and setting has a description and an example in its KDoc. Build the reference with Dokka: `./gradlew dokkaGenerate`. The HTML pages are in `renovo-core/build/dokka/html` and `renovo-ui/build/dokka/html`.
- **More help.** The wiki has deeper pages, such as Troubleshooting.
- **Security.** Report a vulnerability as described in [`SECURITY.md`](SECURITY.md).
- **License.** Apache License 2.0. See [`LICENSE`](LICENSE). Copyright Asker Asadov (Lightine).
