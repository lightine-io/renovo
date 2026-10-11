# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Project skeleton: two library modules and the CI and publishing setup.
- Public API of renovo-core: `Renovo`, `RenovoConfig`, `UpdateInfo`, `ReleaseNotes`, `UpdateState`, `UpdateStep`, and the settings types. The update engine behind them, with the component interfaces the rest of the core implements.
- Persistent store: the engine's snapshot survives the app being closed or killed.
- Version file parser and fetcher: HTTPS only, ETag conditional requests, host app headers, and the host app's own version source.
- Update ready notification (English and Azerbaijani) and the foreground tracker behind it.
- Periodic and on-demand checks through WorkManager, and the resumable APK download with network constraints, a progress notification for user-started downloads, and retries with backoff.
- Installer on PackageInstaller sessions with the install permission flow per Android version, silent self-updates where Android 12 and later allow them, and the unsupported-device detection.
- APK verification before install: SHA-256, package name, version code, and the signing certificate read from the APK signing block (v2, v3, v3.1) and compared with the installed app's, including key rotation.
- Update UI foundations in renovo-ui: theme and progress wrappers, window width classes, every screen string in English and Azerbaijani, the release notes renderer, the shared tiles and the forced-screen shape, previews, and the first Roborazzi screenshot test.
- The runtime behind `Renovo.init`: one event loop feeds the engine and runs its effects through the real components, restores the state from the store on start, and keeps every component failure away from the host app.
- The five dialogs of the optional update flow: update available, downloading, ready to install, first-time permission, and update not installed, with keyboard handling, previews, and screenshots.
- Review fixes in renovo-core: a deferred install confirmation now opens when the user returns and asks `canInstallNow` again, a mistimed permission tap no longer marks the device unsupported, a wrong `size` in the version file no longer breaks resume, and an empty APK is reported as a failed file check.
- The forced update screen with its seven states and the one-column and two-column layouts, with previews and screenshots.
- Notification icons a host app can replace: define a drawable named `renovo_ic_update_ready` (update ready notification) or `renovo_ic_download` (download progress) in the host app.
- Hardening of the core: a worker that starts before `Renovo.init` retries instead of failing, an unexpected exception in a download or an install is reported to the engine instead of being lost, verification results are matched to their file, the version is checked as newer before it is compared with the announced one, and a host logger that throws can no longer drop an event.
- The update UI entry points: `RenovoUpdateHost` for Compose host apps and `Renovo.attachUi` for the others, with the state-to-screen mapping, dismissals that survive rotation, and the one-window rule.
- Review fixes in renovo-ui: the forced update screen now lives in its own window so nothing underneath can take focus or be read by a screen reader, a hidden download dialog stays hidden through a network wait, step tiles no longer clip at 200 percent font scale, the forced-screen icon matches the mockups, dialogs use the Material scrim, and "Hide" and "Close" are remembered for the whole process.
- Proof of coverage: one parameterized screenshot test renders all 12 states at three widths, two themes, and two languages plus compact landscape (156 images), with keyboard-only, rotation, no-clipping, and contrast tests.
- Sample app that updates itself from the local test server through both UI entry points, with scripts that build version 1 and 2 and lay out the server folder for every acceptance criterion.
- README with the quick start, the version file format, every setting, the update states, the update UI, notifications, the permissions the library adds, behavior per Android version, known limits, developer verification, and requirements.

### Changed

- Azerbaijani texts reworded to read more naturally.

### Fixed

- Restored the review fixes that the hardening change had reverted (deferred install confirmation, canInstallNow re-check, no false unsupported on a mistimed tap, resume with a wrong size, empty APK as a failed file check).
- Findings of the emulator acceptance run: the forced update screen sets its status and navigation bar icons to suit its own background, its permission state now fits without scrolling on a 411 x 731 dp phone, and an installer "aborted" status is reported as success when the new version is already installed.
- The forced update screen names Wi-Fi in its waiting note only when `forcedDownloadNetwork` is `Network.UnmeteredOnly`. For `NotRoaming` and `Any` it now says it is waiting for a network.
- The update UI asks `canInstallNow` again whenever the activity resumes, so the "Ready to install" dialog appears once the host app allows it instead of at some later recomposition.
