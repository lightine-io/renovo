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
