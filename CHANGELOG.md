# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- The project itself: two library modules, `renovo-core` (the update engine, no screens) and `renovo-ui` (ready-made screens), with the CI and publishing setup.
- The public API of `renovo-core`: `Renovo`, `RenovoConfig`, `UpdateInfo`, `ReleaseNotes`, `UpdateState`, `UpdateStep`, and the types the settings use. Behind them sits the update engine, which decides every change of the update state.
- The update state is saved on the device, so it survives the app being closed or killed.
- Reading the version file: HTTPS only, ETag support so an unchanged file is not sent again, extra request headers from the host app, and `VersionSource` for a host app that gets version information from its own API.
- The "update ready" notification, in English and Azerbaijani.
- Checks in the background through WorkManager, and on demand. The APK download continues where it stopped after an interruption, uses only the allowed networks, shows a progress notification when the user started it, and retries with growing delays.
- Installing through Android's `PackageInstaller`, with the right "install unknown apps" permission steps for each Android version, updates without a confirmation prompt where Android 12 and later allow them, and detection of devices that cannot install updates.
- Checks of the APK before it is installed: the SHA-256 hash, the package name, the version code, and the signing certificate. The certificate is read from the APK's signature (schemes v2, v3, and v3.1) and compared with the installed app's. A rotated signing key is supported.
- The base of the update UI in `renovo-ui`: theme and progress components, layouts by window width, every screen text in English and Azerbaijani, release notes with simple formatting, shared building blocks, previews, and the first screenshot test.
- The runtime behind `Renovo.init`: it loads the saved update state on start, handles every event in order, and keeps every internal failure away from the host app.
- The five dialogs for an optional update: update available, downloading, ready to install, permission needed, and update not installed. They work with a keyboard and come with previews and screenshot tests.
- Review fixes in `renovo-core`: an install confirmation that had to wait now opens when the user comes back, and asks `canInstallNow` again first. A tap on the permission setting at the wrong moment no longer marks the device as unsupported. A wrong `size` in the version file no longer breaks a resumed download. An empty APK is reported as a failed file check.
- The forced update screen, with its seven states and its one-column and two-column layouts, previews, and screenshot tests.
- Notification icons a host app can replace: define a drawable named `renovo_ic_update_ready` (update ready notification) or `renovo_ic_download` (download progress) in the host app.
- Hardening of `renovo-core`: a background worker that starts before `Renovo.init` tries again later instead of failing. An unexpected error during a download or an install is reported instead of lost. Each verification result is matched to its file. The APK's version code is checked as newer before it is compared with the one in the version file. A host logger that throws can no longer drop an event.
- The two ways to add the update UI: `RenovoUpdateHost` for Compose host apps and `Renovo.attachUi` for the others. They show the right screen for each update state, keep what the user dismissed through a rotation, and show the update UI in one activity only.
- Review fixes in `renovo-ui`: the forced update screen now has its own window, so nothing behind it can take keyboard focus or be read by a screen reader. A hidden download dialog stays hidden while the download waits for a network. Step numbers are no longer cut off at 200 percent font size. The icon on the forced update screen matches the design. Dialogs dim the background with the Material scrim color. "Hide" and "Close" are remembered until the app restarts.
- Test coverage: one screenshot test draws all 12 states at three window widths, in light and dark, in two languages, and in compact landscape (156 images). Further tests cover keyboard-only use, rotation, cut-off text, and contrast.
- README with the quick start, the version file format, every setting, the update states, the update UI, notifications, the permissions the library adds, behavior per Android version, known limits, developer verification, and requirements.

### Changed

- The Azerbaijani texts are reworded to read more naturally.
- Release notes keys may carry a region, such as `pt-BR`, and the lookup falls back from the region to the language to English.

### Fixed

- The review fixes that the hardening change had undone are back: the waiting install confirmation, the second `canInstallNow` check, no false "unsupported" after a tap at the wrong moment, resuming with a wrong `size`, and an empty APK as a failed file check.
- Fixes from the test run on an emulator: the forced update screen picks status bar and navigation bar icons that suit its own background. Its permission state now fits without scrolling on a 411 x 731 dp phone. When the installer reports "aborted" but the new version is already installed, Renovo now reports success.
- The forced update screen names Wi-Fi in its waiting note only when `forcedDownloadNetwork` is `Network.UnmeteredOnly`. For `NotRoaming` and `Any` it now says it is waiting for a network.
- The update UI asks `canInstallNow` again whenever the activity resumes. The "Ready to install" dialog now appears as soon as the host app allows it, not at some later redraw.
- A download the user cancels from its notification goes back to "update available" instead of failing, and the next check no longer starts it again. `startDownload()` starts it again.
- A failure while cancelling an installer session no longer blocks every later install until the app restarts.
- The `size` in the version file no longer decides anything. A wrong value can no longer fail a download for lack of space, finish a cut-off file, or throw away a finished one.
- An install confirmation that had to wait now opens the next time an activity resumes. So it also appears after a system dialog closes, without the user leaving the app.
- `Renovo.init` does nothing outside the host app's main process, so a `:remote` process no longer builds a second engine that overwrites the saved state.
- The permission steps of the update UI match the Android 17 settings screen: step 2 and the picture show the "Always allow" choice instead of the switch.
