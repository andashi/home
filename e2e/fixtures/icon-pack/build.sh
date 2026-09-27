#!/usr/bin/env bash
# Builds the icon-pack fixture APK with the SDK's own tools, no Gradle module:
# a package with two drawables and an appfilter, and no code, which the
# launcher indexes as an icon pack (l4-config step 10f).
#
#   e2e/fixtures/icon-pack/build.sh <out.apk>
#
# ANDROID_HOME (default /opt/android-sdk) supplies the newest build-tools
# (aapt2, zipalign, apksigner) and platform (android.jar). Signed with the
# debug key. For the emulator only. Every tool is checked before anything
# runs, so a missing one is named with the installation it belongs to.
set -euo pipefail

out="${1:?usage: build.sh <out.apk>}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
sdk="${ANDROID_HOME:-/opt/android-sdk}"
# `|| true`: under pipefail a failed ls would end the script here, silently,
# before the checks below could say what is missing.
bt="$(ls -d "$sdk"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)"
platform="$(ls -d "$sdk"/platforms/*/ 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$bt" ] || { echo "no build-tools under $sdk (ANDROID_HOME)" >&2; exit 1; }
for tool in aapt2 zipalign apksigner; do
  [ -x "$bt/$tool" ] || { echo "$tool not found in $bt (ANDROID_HOME build-tools)" >&2; exit 1; }
done
jar="$platform/android.jar"
[ -f "$jar" ] || { echo "android.jar not found under $sdk/platforms (ANDROID_HOME)" >&2; exit 1; }
keystore="${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
[ -f "$keystore" ] || { echo "debug keystore not found at $keystore (DEBUG_KEYSTORE)" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

"$bt/aapt2" compile --dir "$here/res" -o "$work/res.zip"
"$bt/aapt2" link -I "$jar" --manifest "$here/AndroidManifest.xml" "$work/res.zip" -o "$work/unsigned.apk"
"$bt/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
"$bt/apksigner" sign --ks "$keystore" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out "$out" "$work/aligned.apk"
