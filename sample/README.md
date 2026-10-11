# sample

A small host app (the app that uses Renovo) that updates itself from version 1 to version 2. It
talks to the local `test-server`, and it is how the acceptance tests run. It is not published.

The main screen shows the version, the current update state, a switch that makes the install
unsafe (AC-4), and buttons for every Renovo action. The "Open the Views activity" button opens a
second screen that uses `Renovo.attachUi` instead of Compose.

## Build both versions

1. Start the test server once, so it creates its certificate, then stop it:
   `mkdir -p build/sample-server && ./gradlew :test-server:run --args="--dir $PWD/build/sample-server"`
2. Copy `server.pem` from its cert folder to `sample/src/debug/res/raw/test_server.pem`.
   The default cert folder is `build/sample-server/.cert`. The file is ignored by git.
3. Run `scripts/build-sample-versions.sh`. It builds debug and release for version codes 1 and 2
   and fills `build/sample-server/` with four APKs and the version files. At the end it prints the
   command that starts the test server for that folder.
4. Optional: `scripts/make-other-key-apk.sh` adds an update signed by another key.

Build properties: `-PsampleVersionCode` (default 1), `-PsampleVersionUrl` (default
`https://10.0.2.2:8443/version.json`), `-PsampleForcedNetwork` (`Any`, `UnmeteredOnly`, `NotRoaming`;
default `UnmeteredOnly`). The script reads `SAMPLE_FORCED_NETWORK` and `SERVER_BASE` for the last two.

## Run on an emulator (API 24, 26, 31, or 37)

```
adb install build/sample-server/app-1-debug.apk
```

Open the app, then start the server. The app reads `version.json` from `10.0.2.2`, the address
an emulator uses for this machine. To try another case, copy its version file over `version.json`
in `build/sample-server/`. The server reads files on every request. Running the build script
again restores `version.json` to the optional update.

## Which version file for which test

| Test | Serve |
|---|---|
| AC-1 optional update | `version.json` |
| AC-1 forced update | `version-forced.json` |
| AC-2 wrong hash | `version-bad-hash.json` |
| AC-2 other signing key | `version-other-key.json` |
| AC-2 equal or lower version code | a copy of `version.json` with a higher `versionCode` that points at an older APK |
| AC-3 interrupted download | `version.json`, server started with `--cut-after 6900000` |
| AC-4 not safe to install | `version.json`, switch on, then "Install if ready" |
| AC-5 minimum version | `version-minimum.json` |
| AC-12 mobile data choice | `version-forced.json`, emulator on mobile data only |
| AC-13, AC-14 notification, kill | `version.json`; `adb shell am force-stop io.lightine.renovo.sample` |
| AC-15 release build | `version-release.json` or `version-forced-release.json` |

## Notes

- The debug build trusts the test certificate through `src/debug/res/xml/network_security_config.xml`.
  The release build trusts system certificates only, as a real host app does, unless it is built
  with `-PsampleTrustTestServer=true`. `scripts/build-sample-versions.sh` sets it, so its release
  APKs can reach the test server (AC-15). Never ship an APK built that way.
- Both build types are signed with the debug key, so an update from 1 to 2 passes the certificate check.
- Logcat tag: `RenovoSample`.
