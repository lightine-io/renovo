#!/usr/bin/env bash
# Builds version 1 and version 2 of the sample (debug and release) and lays out the folder that
# the test server serves: four APKs and the version files for the acceptance tests.
#
# Usage: scripts/build-sample-versions.sh
# Optional environment:
#   SERVER_BASE             where the emulator reaches the server (default https://10.0.2.2:8443)
#   SAMPLE_FORCED_NETWORK   Any, UnmeteredOnly (default), or NotRoaming, for AC-12
#
# The debug build needs the test server's certificate in sample/src/debug/res/raw/test_server.pem;
# the build stops with a message when it is missing. See sample/README.md.
set -euo pipefail

# shellcheck source=scripts/sample-common.sh
source "$(dirname "${BASH_SOURCE[0]}")/sample-common.sh"
cd "$REPO_ROOT"

# One Gradle run per version code. -P properties change BuildConfig and the version, so the two
# versions cannot share a run. The build cache makes a repeated run cheap.
# -PsampleTrustTestServer makes the release APKs trust the test server's certificate too. Without
# it the shrunk release build could not reach the server, and AC-15 could not be tested.
gradle_args=(-PsampleVersionUrl="$SERVER_BASE/version.json" -PsampleTrustTestServer=true)
if [[ -n "${SAMPLE_FORCED_NETWORK:-}" ]]; then
    gradle_args+=(-PsampleForcedNetwork="$SAMPLE_FORCED_NETWORK")
fi

# The folder is not wiped: the test server keeps its certificate in .cert inside it, and a new
# certificate would need a new copy in the sample's debug resources.
mkdir -p "$SERVER_DIR"

for code in 1 2; do
    echo "== Building version code $code"
    ./gradlew :sample:assembleDebug :sample:assembleRelease -PsampleVersionCode="$code" "${gradle_args[@]}"
    cp sample/build/outputs/apk/debug/sample-debug.apk "$SERVER_DIR/app-$code-debug.apk"
    cp sample/build/outputs/apk/release/sample-release.apk "$SERVER_DIR/app-$code-release.apk"
done

debug_sha="$(sha256_of "$SERVER_DIR/app-2-debug.apk")"
release_sha="$(sha256_of "$SERVER_DIR/app-2-release.apk")"
# 64 zeros are valid hex of the right length, so the file parses and the check reaches the hash
# comparison (AC-2).
wrong_sha="0000000000000000000000000000000000000000000000000000000000000000"

write_version_file version.json app-2-debug.apk "$debug_sha"
write_version_file version-forced.json app-2-debug.apk "$debug_sha" '"mandatory": true,'
# Not mandatory, but version 1 is below the minimum: the update is still forced (AC-5).
write_version_file version-minimum.json app-2-debug.apk "$debug_sha" '"minSupportedVersionCode": 2,'
write_version_file version-bad-hash.json app-2-debug.apk "$wrong_sha"

# The release APK is a different file with a different hash, so it needs its own version files.
write_version_file version-release.json app-2-release.apk "$release_sha"
write_version_file version-forced-release.json app-2-release.apk "$release_sha" '"mandatory": true,'

echo
echo "== Done. Folder: $SERVER_DIR"
ls -l "$SERVER_DIR" | grep -E '\.(apk|json)$'
echo
echo "Start the test server for this folder:"
echo "  ./gradlew :test-server:run --args=\"--dir $SERVER_DIR\""
echo "The app on the emulator reads $SERVER_BASE/version.json. To try another case, copy that"
echo "file over version.json, for example:"
echo "  cp $SERVER_DIR/version-forced.json $SERVER_DIR/version.json"
