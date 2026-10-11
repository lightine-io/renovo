# sample

A small host app (the app that uses Renovo) that updates itself from version 1 to version 2. It
talks to the local `test-server`, and it is how the acceptance tests run. It is not published.
The main screen has a switch that makes the install unsafe (AC-4) and a button for every Renovo
action. "Open the Views activity" opens a screen that uses `Renovo.attachUi` instead of Compose.

## Build both versions

1. Start the test server once, so it creates its certificate, then stop it:
   `mkdir -p build/sample-server && ./gradlew :test-server:run --args="--dir $PWD/build/sample-server"`
2. Copy `build/sample-server/.cert/server.pem` to `sample/src/debug/res/raw/test_server.pem`
   (the file is not committed).
3. Run `scripts/build-sample-versions.sh`. It builds debug and release for version codes 1 and 2,
   fills `build/sample-server/` with four APKs and the version files, and prints the server command.
4. Optional: `scripts/make-other-key-apk.sh` adds an update signed by another key.

Build properties: `-PsampleVersionCode` (default 1), `-PsampleVersionUrl` (default
`https://10.0.2.2:8443/version.json`), `-PsampleForcedNetwork` (`Any`, `UnmeteredOnly`, `NotRoaming`;
default `UnmeteredOnly`). The script reads `SAMPLE_FORCED_NETWORK` and `SERVER_BASE`.

## Run on an emulator (API 24, 26, 31, or 37)

```
adb install build/sample-server/app-1-debug.apk
```

Open the app, then start the server. The app reads `version.json` from `10.0.2.2`, the address
an emulator uses for this machine. To try another case, copy its version file over `version.json`
in `build/sample-server/`. The build script restores it to the optional update.

**Android 17.** The debug build declares `ACCESS_LOCAL_NETWORK`, a runtime permission ("Nearby
devices") that Android 17 asks for before an app reaches private addresses such as `10.0.2.2`.
The sample shows no prompt, so allow it in Settings, or use the loopback route of the acceptance run:
`SERVER_BASE=https://127.0.0.1:8443 scripts/build-sample-versions.sh`, then `adb reverse tcp:8443 tcp:8443`.

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

- The debug build trusts the test certificate (`src/debug/res/xml/network_security_config.xml`). The
  release build trusts system certificates only, unless built with `-PsampleTrustTestServer=true`,
  which the build script sets for AC-15. Never ship an APK built that way.
- Both build types use the debug key, so the update from 1 to 2 passes the certificate check.
- Logcat tag: `RenovoSample`.
