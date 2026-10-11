#!/usr/bin/env bash
# Re-signs app-2-debug.apk with a throw-away key, to test that Renovo rejects an update signed by
# a different key (AC-2). Writes app-2-other-key.apk and version-other-key.json next to it.
#
# Run scripts/build-sample-versions.sh first.
# The key lives in a temporary folder that is deleted on exit, so no keystore reaches the repository.
set -euo pipefail

# shellcheck source=scripts/sample-common.sh
source "$(dirname "${BASH_SOURCE[0]}")/sample-common.sh"

source_apk="$SERVER_DIR/app-2-debug.apk"
[[ -f "$source_apk" ]] || { echo "Missing $source_apk. Run scripts/build-sample-versions.sh first." >&2; exit 1; }

# Finds the Android SDK the way Gradle does: environment first, then local.properties.
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk" && -f "$REPO_ROOT/local.properties" ]]; then
    sdk="$(sed -n 's/^sdk\.dir=//p' "$REPO_ROOT/local.properties" | head -1)"
fi
[[ -d "$sdk/build-tools" ]] || { echo "Android SDK not found. Set ANDROID_HOME." >&2; exit 1; }

# The newest build-tools folder that has apksigner (sort -V orders version numbers correctly).
apksigner=""
while IFS= read -r dir; do
    if [[ -x "$dir/apksigner" ]]; then apksigner="$dir/apksigner"; fi
done < <(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V)
[[ -n "$apksigner" ]] || { echo "apksigner not found in $sdk/build-tools." >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# The password goes through the environment so it does not show up in the process list.
export OTHER_KEY_PASSWORD
OTHER_KEY_PASSWORD="$(openssl rand -hex 16)"

keytool -genkeypair -keystore "$work/other.p12" -storetype PKCS12 \
    -storepass:env OTHER_KEY_PASSWORD -keypass:env OTHER_KEY_PASSWORD \
    -alias other -keyalg RSA -keysize 2048 -validity 2 -dname "CN=Throw-away test key" 2>/dev/null

out="$SERVER_DIR/app-2-other-key.apk"
cp "$source_apk" "$out"
# apksigner drops the old signature and writes the new one.
"$apksigner" sign --ks "$work/other.p12" --ks-key-alias other \
    --ks-pass env:OTHER_KEY_PASSWORD --key-pass env:OTHER_KEY_PASSWORD "$out"
"$apksigner" verify "$out"

# The hash and size of the new file are correct on purpose: the only thing wrong with this update
# must be its signature.
write_version_file version-other-key.json app-2-other-key.apk "$(sha256_of "$out")"

echo
echo "Wrote $out"
echo "Wrote $SERVER_DIR/version-other-key.json"
echo "Serve it with: cp $SERVER_DIR/version-other-key.json $SERVER_DIR/version.json"
