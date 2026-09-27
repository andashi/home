#!/usr/bin/env bash
# Builds the widget-only fixture APK with the SDK's own tools, no Gradle
# module: a package with one AppWidgetProvider and no launcher entry, which
# the launcher's app list never shows (l4-config step 10d).
#
#   e2e/fixtures/widget-only/build.sh <out.apk>
#
# ANDROID_HOME (default /opt/android-sdk) supplies the newest build-tools and
# platform; it is signed with the debug key. For the emulator only.
set -euo pipefail

out="${1:?usage: build.sh <out.apk>}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
sdk="${ANDROID_HOME:-/opt/android-sdk}"
bt="$(ls -d "$sdk"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
platform="$(ls -d "$sdk"/platforms/*/ 2>/dev/null | sort -V | tail -1)"
[ -n "$bt" ] && [ -x "$bt/aapt2" ] && [ -x "$bt/d8" ] || { echo "build-tools not found under $sdk" >&2; exit 1; }
jar="$platform/android.jar"
[ -f "$jar" ] || { echo "android.jar not found under $sdk/platforms" >&2; exit 1; }
keystore="${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
[ -f "$keystore" ] || { echo "debug keystore not found: $keystore" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

"$bt/aapt2" compile --dir "$here/res" -o "$work/res.zip"
"$bt/aapt2" link -I "$jar" --manifest "$here/AndroidManifest.xml" "$work/res.zip" \
  --java "$work/gen" -o "$work/unsigned.apk"
mkdir -p "$work/classes"
javac -source 17 -target 17 -nowarn -classpath "$jar" -d "$work/classes" \
  "$here/src/org/andashi/fixture/widgetonly/OnlyWidget.java" \
  "$(find "$work/gen" -name R.java)" 2>/dev/null
"$bt/d8" --lib "$jar" --min-api 36 --output "$work" $(find "$work/classes" -name '*.class')
(cd "$work" && zip -q unsigned.apk classes.dex)
"$bt/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
"$bt/apksigner" sign --ks "$keystore" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out "$out" "$work/aligned.apk"
