# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Project skeleton: two library modules, sample app, test server, and the CI and publishing setup.
- Public API of renovo-core: `Renovo`, `RenovoConfig`, `UpdateInfo`, `ReleaseNotes`, `UpdateState`, `UpdateStep`, and the settings types. The update engine behind them, with the component interfaces the rest of the core implements.
- Persistent store: the engine's snapshot survives the app being closed or killed.
- Version file parser and fetcher: HTTPS only, ETag conditional requests, host app headers, and the host app's own version source.
- Update ready notification (English and Azerbaijani) and the foreground tracker behind it.
- Periodic and on-demand checks through WorkManager, and the resumable APK download with network constraints, a progress notification for user-started downloads, and retries with backoff.
- Installer on PackageInstaller sessions with the install permission flow per Android version, silent self-updates where Android 12 and later allow them, and the unsupported-device detection.
- APK verification before install: SHA-256, package name, version code, and the signing certificate read from the APK signing block (v2, v3, v3.1) and compared with the installed app's, including key rotation.
- Local HTTPS test server for the sample app: version file with ETag, APK with Range and If-Range, and a switch that cuts a download to test resume.
- Update UI foundations in renovo-ui: theme and progress wrappers, window width classes, every screen string in English and Azerbaijani, the release notes renderer, the shared tiles and the forced-screen shape, previews, and the first Roborazzi screenshot test.
- The runtime behind `Renovo.init`: one event loop feeds the engine and runs its effects through the real components, restores the state from the store on start, and keeps every component failure away from the host app.
- The five dialogs of the optional update flow: update available, downloading, ready to install, first-time permission, and update not installed, with keyboard handling, previews, and screenshots.
- Review fixes in renovo-core: a deferred install confirmation now opens when the user returns and asks `canInstallNow` again, a mistimed permission tap no longer marks the device unsupported, a wrong `size` in the version file no longer breaks resume, and an empty APK is reported as a failed file check.
- The forced update screen with its seven states and the one-column and two-column layouts, with previews and screenshots.
- Notification icons a host app can replace: define a drawable named `renovo_ic_update_ready` (update ready notification) or `renovo_ic_download` (download progress) in the host app.
- Hardening of the core: a worker that starts before `Renovo.init` retries instead of failing, an unexpected exception in a download or an install is reported to the engine instead of being lost, verification results are matched to their file, the version is checked as newer before it is compared with the announced one, and a host logger that throws can no longer drop an event.
