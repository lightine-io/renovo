#!/usr/bin/env bash
#
# Builds the signed test APKs in src/test/resources/apks/ from scratch.
#
# Last run with Android SDK build-tools 37.0.0 (aapt2, zipalign, apksigner 0.9), platform
# android-37.0, and the JDK's keytool.
#
# The two signing keys are generated into a temporary directory and deleted at the end, so they
# never reach the repository. Every run therefore makes new keys: after a run, copy the two
# "certificate SHA-256" lines this script prints into ApkFixtures.kt, or the tests fail.
#
# Usage: renovo-core/src/test/fixtures/make-fixtures.sh
# Needs ANDROID_HOME, or local.properties with sdk.dir, and keytool on the PATH.

set -euo pipefail

BUILD_TOOLS_VERSION="37.0.0"
PLATFORM="android-37.0"

script_dir="$(cd "$(dirname "$0")" && pwd)"
module_dir="$(cd "$script_dir/../../.." && pwd)"
out_dir="$module_dir/src/test/resources/apks"

sdk="${ANDROID_HOME:-}"
if [[ -z "$sdk" && -f "$module_dir/../local.properties" ]]; then
    sdk="$(sed -n 's/^sdk\.dir=//p' "$module_dir/../local.properties")"
fi
if [[ -z "$sdk" ]]; then
    echo "Set ANDROID_HOME or sdk.dir in local.properties." >&2
    exit 1
fi

tools="$sdk/build-tools/$BUILD_TOOLS_VERSION"
android_jar="$sdk/platforms/$PLATFORM/android.jar"
aapt2="$tools/aapt2"
zipalign="$tools/zipalign"
apksigner="$tools/apksigner"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

pass="pass:renovo-test"

# Key A: RSA, the "installed app" key. Key B: EC P-256, a different publisher or the rotated key.
# Two key types make the fixtures cover both RSA PKCS#1 and ECDSA signatures.
keytool -genkeypair -keystore "$work/a.p12" -storetype PKCS12 -storepass renovo-test \
    -alias a -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=Renovo test key A" >/dev/null 2>&1
keytool -genkeypair -keystore "$work/b.p12" -storetype PKCS12 -storepass renovo-test \
    -alias b -keyalg EC -groupname secp256r1 -validity 36500 -dname "CN=Renovo test key B" >/dev/null 2>&1

signer_a=(--ks "$work/a.p12" --ks-pass "$pass" --ks-key-alias a)
signer_b=(--ks "$work/b.p12" --ks-pass "$pass" --ks-key-alias b)

# One string resource keeps the APK a normal, minimal app.
mkdir -p "$work/res/values"
cat >"$work/res/values/strings.xml" <<'XML'
<resources>
    <string name="app_name">Sample</string>
</resources>
XML
"$aapt2" compile --dir "$work/res" -o "$work/res.zip"

# Builds an unsigned, aligned APK: unsigned_apk <name> <package> <versionCode> [versionCodeMajor]
unsigned_apk() {
    local name="$1" package="$2" version_code="$3" major="${4:-}"
    local major_attr=""
    if [[ -n "$major" ]]; then
        major_attr="android:versionCodeMajor=\"$major\""
    fi
    cat >"$work/$name.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$package"
    android:versionCode="$version_code"
    $major_attr
    android:versionName="$version_code">
    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="37" />
    <application android:label="@string/app_name" />
</manifest>
XML
    "$aapt2" link -I "$android_jar" --manifest "$work/$name.xml" -o "$work/$name-raw.apk" "$work/res.zip"
    "$zipalign" -f -P 16 4 "$work/$name-raw.apk" "$work/$name-unsigned.apk"
}

# sign <unsigned name> <output file> <apksigner options...>
sign() {
    local name="$1" output="$2"
    shift 2
    "$apksigner" sign --v4-signing-enabled false --in "$work/$name-unsigned.apk" --out "$out_dir/$output" "$@" \
        2> >(grep -v '^WARNING' >&2)
    rm -f "$out_dir/$output.idsig"
}

mkdir -p "$out_dir"
rm -f "$out_dir"/*.apk

unsigned_apk sample io.lightine.renovo.sample 2
unsigned_apk lower io.lightine.renovo.sample 1
unsigned_apk other io.lightine.renovo.other 2
unsigned_apk major io.lightine.renovo.sample 2 1

v2v3=(--v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true)

sign sample good-v2v3.apk "${v2v3[@]}" "${signer_a[@]}"
sign sample good-v2-only.apk --v1-signing-enabled false --v2-signing-enabled true \
    --v3-signing-enabled false "${signer_a[@]}"
sign sample other-key.apk "${v2v3[@]}" "${signer_b[@]}"
sign sample v1-only.apk --v1-signing-enabled true --v2-signing-enabled false \
    --v3-signing-enabled false "${signer_a[@]}"
sign lower lower-version.apk "${v2v3[@]}" "${signer_a[@]}"
sign other other-package.apk "${v2v3[@]}" "${signer_a[@]}"
sign major version-major.apk "${v2v3[@]}" "${signer_a[@]}"

# Key rotation from A to B. The proof-of-rotation lineage is signed by A and names B as its
# successor.
"$apksigner" rotate --out "$work/lineage" --old-signer "${signer_a[@]}" --new-signer "${signer_b[@]}" \
    2> >(grep -v '^WARNING' >&2)
# The same rotation, but key A loses the "installed data" capability, so the platform refuses
# to update an app signed with A to this APK.
"$apksigner" rotate --out "$work/lineage-no-data" --old-signer "${signer_a[@]}" \
    --set-installed-data false --new-signer "${signer_b[@]}" 2> >(grep -v '^WARNING' >&2)

# A rotation-min-sdk-version of 32 or lower puts the rotated signer B, with its lineage, into the
# v3 block itself (no v3.1 block). v2 stays signed with the original key A.
sign sample rotated.apk "${v2v3[@]}" --lineage "$work/lineage" --rotation-min-sdk-version 28 \
    "${signer_a[@]}" --next-signer "${signer_b[@]}"
sign sample rotated-no-installed-data.apk "${v2v3[@]}" --lineage "$work/lineage-no-data" \
    --rotation-min-sdk-version 28 "${signer_a[@]}" --next-signer "${signer_b[@]}"
# apksigner's default rotation: v3 signed by A for API 28 to 32, v3.1 signed by B (with the
# lineage) for API 33 and higher.
sign sample rotated-v31.apk "${v2v3[@]}" --lineage "$work/lineage" \
    "${signer_a[@]}" --next-signer "${signer_b[@]}"
# Rotation targeted at API 34: the v3 signer A covers API 24 to 33 and names 34 as the rotation
# minSdk, which is what the platform's v3.1 stripping protection checks on API 33.
sign sample rotated-v31-api34.apk "${v2v3[@]}" --lineage "$work/lineage" --rotation-min-sdk-version 34 \
    "${signer_a[@]}" --next-signer "${signer_b[@]}"

echo "Fixtures written to $out_dir:"
ls -l "$out_dir"
echo
echo "Certificate SHA-256 (copy into ApkFixtures.kt):"
echo "A: $(keytool -exportcert -keystore "$work/a.p12" -storepass renovo-test -alias a | shasum -a 256 | cut -d' ' -f1)"
echo "B: $(keytool -exportcert -keystore "$work/b.p12" -storepass renovo-test -alias b | shasum -a 256 | cut -d' ' -f1)"
