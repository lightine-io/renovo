# Shared helpers for the sample scripts. Sourced, never run on its own.

# Repository root, found from this file so the scripts work from any folder.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Where the test server finds the APKs and version files.
SERVER_DIR="$REPO_ROOT/build/sample-server"

# How the emulator reaches the test server. The version files carry absolute APK URLs, so a
# different host or port has to be given when the files are written.
SERVER_BASE="${SERVER_BASE:-https://10.0.2.2:8443}"

# SHA-256 as 64 lowercase hex characters. macOS has shasum, most Linux systems have sha256sum.
sha256_of() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d' ' -f1
    else
        sha256sum "$1" | cut -d' ' -f1
    fi
}

# File size in bytes. `wc -c` behaves the same on macOS and Linux, unlike `stat`.
size_of() {
    wc -c <"$1" | tr -d ' '
}

# write_version_file <file name> <apk file name> <sha256> [extra JSON fields]
# The release notes come from the example in the technical spec. The extra fields, for example
# `"mandatory": true,`, go in front of versionCode and must end with a comma.
write_version_file() {
    local name="$1" apk="$2" sha="$3" extra="${4:-}" extra_line=""
    if [[ -n "$extra" ]]; then extra_line="  $extra"$'\n'; fi
    cat >"$SERVER_DIR/$name" <<EOF
{
${extra_line}  "versionCode": 2,
  "versionName": "2.0",
  "apkUrl": "$SERVER_BASE/$apk",
  "sha256": "$sha",
  "size": $(size_of "$SERVER_DIR/$apk"),
  "releaseNotes": {
    "az": "- Sinxronizasiya **sürətləndirildi**\n- Xətalar aradan qaldırıldı",
    "en": "- Sync is **faster**\n- Bug fixes"
  }
}
EOF
}
