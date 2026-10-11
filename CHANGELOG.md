# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Nothing yet.

## [1.0.0] - 2026-10-11

The first release.

### Added

- `renovo-core`: the update engine with no screens. It reads a version file over HTTPS, or asks the host app's own `VersionSource`, checks in the background through WorkManager and on demand, downloads the APK with resume after an interruption and only on the allowed networks, verifies the file (SHA-256 hash, package name, version code, and the signing certificate read from the APK signature, with key rotation), and installs it through Android's `PackageInstaller` with the right permission steps for each Android version. Android 12 and later install without a prompt where the system allows it.
- The update state as a `StateFlow` and a listener, with every state, forced update step, and failure reason as a Kotlin type. The state is saved on the device and survives the app being closed or killed.
- Forced updates through `mandatory` or `minSupportedVersionCode` in the version file.
- Release notes with simple formatting, as one text or one text per language, with region codes such as `pt-BR`.
- Settings for the check interval, automatic download, networks for optional and forced downloads, a one-time mobile data override, notifications, the moment an install may start (`canInstallNow`), extra request headers, and a logger.
- The "update ready" notification and the download progress notification, with icons the host app can replace by resource name.
- `renovo-ui`: the ready-made update UI in Jetpack Compose. Five dialogs for an optional update and a full screen for a forced update, in English and Azerbaijani, laid out by window width, usable with a keyboard and a screen reader, with Material 3 theming, dark mode, and dynamic color. Two ways to add it: `RenovoUpdateHost` for Compose apps and `Renovo.attachUi` for XML apps.
- Permission steps that match each Android version's settings screen, including the "Always allow" choice on Android 17.
- README with one complete path per use case, and a wiki with the deeper guides.
