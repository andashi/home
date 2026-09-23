#!/usr/bin/env bash
# Frame time of the glass backdrop on the GrapheneOS foldable (#74, #77).
#
#   e2e/measure-glass-frametime.sh [path/to/app.apk]
#
# Measured, recorded, not asserted (ADR 0004: "performance on the Pixel Fold
# must be measured"). The home screen carries eight surfaces - seven DeskClock
# widgets and the favorites dock - in the left four columns, so the cover and
# the inner display show the same eight. The wallpaper is provisioning's
# themes/mauritius (the reference's own), applied through the config like a
# zone would.
#
# For each display (cover = CLOSED, inner = OPENED) the full glass stack is
# measured (VARIANTS=glass): backdrop region, tint, highlight and specular on
# every surface (#75). The backdrop-only number from before #75 is
# e2e/measurements/glass-off-hook-c33378d24.tsv; that build drew the region
# through a debug hook that no longer exists.
#
# frames come from RUNS transitions home -> search -> home (a swipe up and
# BACK), which animate the whole home content, so every surface redraws on
# every frame. `dumpsys gfxinfo` is reset before and read after each series.
# The blur itself happens once per wallpaper, glass setting and display, off
# the frame; its duration is read from the launcher's own log line.
#
# Output: a TSV (metric <TAB> value) under e2e/measurements/, default
# glass-<variant set>-<git short sha>.tsv, OUT overrides the path. With
# SCREENSHOTS=<dir>, a screencap of each display's home screen lands there
# (<variant>-<display>.png) before its series starts.
#
# Instance: SERIAL + OVERLAY_DIR (default the foldable, emulator-5560 with
# instances/test-fold), snapshot `clean`, under the instance's device lock.
# Everything runs as the unrooted shell (uid 2000, asserted).
set -euo pipefail

gos_repo_default() {
  local d; d="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  while [ "$d" != "/" ]; do
    [ -d "$d/provisioning/emulator" ] && { printf '%s\n' "$d/provisioning"; return; }
    d="$(dirname "$d")"
  done
  printf '%s\n' "$HOME/Development/GrapheneOS"
}
GOS_REPO="${GOS_REPO:-$(gos_repo_default)}"
INSTANCE_OVERRIDE="${SERIAL:+s}${OVERLAY_DIR:+o}"
SERIAL="${SERIAL:-emulator-5560}"
export SERIAL
export OVERLAY_DIR="${OVERLAY_DIR:-$GOS_REPO/emulator/instances/test-fold}"
LOCK_OWNER="glass-frametime@$SERIAL#$$"
SNAPSHOT="${SNAPSHOT:-clean}"
HERE="$(cd "$(dirname "$0")" && pwd)"
APK="${1:-$HERE/../app/app/build/outputs/apk/default/debug/app-default-debug.apk}"
PKG="${PKG:-org.andashi.home.debug}"
RUNS="${RUNS:-15}"
VARIANTS="${VARIANTS:-glass}"
WALLPAPER="${WALLPAPER:-$GOS_REPO/themes/mauritius/tall/wallpaper.jpg}"
REV="$(git -C "$HERE/.." rev-parse --short HEAD)"
# The APK is built from the working tree; a dirty tree is recorded as such.
git -C "$HERE/.." diff --quiet HEAD -- app core services data || REV="$REV-dirty"
OUT="${OUT:-$HERE/measurements/glass-$(tr ' ' '-' <<<"$VARIANTS")-$REV.tsv}"
CLOCK="com.android.deskclock/com.android.alarmclock.DigitalAppWidgetProvider"

c(){ [ -t 1 ] && printf '\033[%sm%s\033[0m\n' "$1" "$2" || printf '%s\n' "$2"; }
log(){ c '1;34' ":: $*"; }; ok(){ c '1;32' " + $*"; }; warn(){ c '1;33' " ! $*"; }
die(){ c '1;31' " x $*" >&2; exit 1; }

[ "$INSTANCE_OVERRIDE" = "" ] || [ "$INSTANCE_OVERRIDE" = "so" ] \
  || die "SERIAL and OVERLAY_DIR name ONE instance - set both or neither"
[ -d "$GOS_REPO/emulator" ] || die "provisioning repo not found at $GOS_REPO (set GOS_REPO)"
[ -f "$APK" ] || die "APK not found: $APK"
[ -f "$WALLPAPER" ] || die "wallpaper not found: $WALLPAPER"
command -v jq >/dev/null || die "jq not found"

WORK="$(mktemp -d)"
# shellcheck source=lib/grid-device.sh
. "$HERE/lib/grid-device.sh"

HAVE_LOCK=0
cleanup() {
  if [ "$HAVE_LOCK" = 1 ]; then
    (cd "$GOS_REPO" && SERIAL="$SERIAL" emulator/run.sh stop) >/dev/null 2>&1 || true
    (cd "$GOS_REPO" && emulator/device-lock.sh release "$LOCK_OWNER" "$SERIAL") >/dev/null 2>&1 || true
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

# --- fixture ----------------------------------------------------------------

# Seven clocks, two per row in the left four columns, plus the dock: the same
# eight surfaces on the cover (columns 0-3) and on the inner display.
items() { # $1 = dock width
  local out="" i=0 x y
  for y in 0 1 2 3; do
    for x in 0 2; do
      [ "$i" -lt 7 ] || break
      out+="{ \"id\": \"clock-$i\", \"widget\": \"$CLOCK\", \"x\": $x, \"y\": $y, \"w\": 2, \"h\": 1 },"
      i=$((i + 1))
    done
  done
  printf '%s{ "id": "dock", "widget": "favorites", "x": 0, "y": 5, "w": %s, "h": 1 }' "$out" "$1"
}
CONFIG="$WORK/glass.json"
cat > "$CONFIG" <<EOF
{
  "schemaVersion": 2,
  "appearance": {
    "wallpaper": { "image": "mauritius.jpg", "target": "both" },
    "glass": { "blur": 24, "tint": 0.12, "radius": 28, "contrast": "medium", "wallpaperBlur": true }
  },
  "home": {
    "widgets": { "enabled": true },
    "grid": {
      "columns": 4,
      "layouts": {
        "phone": { "items": [ $(items 4) ] },
        "fold": { "items": [ $(items 8) ] }
      }
    }
  }
}
EOF

# --- boot -------------------------------------------------------------------

(cd "$GOS_REPO" && emulator/device-lock.sh acquire "$LOCK_OWNER" "$SERIAL")
HAVE_LOCK=1
log "booting $SERIAL from snapshot '$SNAPSHOT' (overlays: $OVERLAY_DIR)"
(cd "$GOS_REPO" && SNAPSHOT="$SNAPSHOT" emulator/run.sh start)
adb -s "$SERIAL" unroot >/dev/null 2>&1 || true
adb -s "$SERIAL" wait-for-device
[ "$(adb -s "$SERIAL" shell id -u | tr -d '\r')" = "2000" ] || die "adb is not the unrooted shell"
ok "adb as unrooted shell (uid 2000)"

adb -s "$SERIAL" install -r "$APK" | grep -q Success || die "install failed"
adb -s "$SERIAL" shell appwidget grantbind --package "$PKG" --user 0 >/dev/null
resolve_postures() {
  local states
  states="$(adb -s "$SERIAL" shell cmd device_state print-states 2>/dev/null | tr -d '\r')"
  POSTURE_CLOSED="$(sed -n "s/.*identifier=\([0-9]*\), name='CLOSED'.*/\1/p" <<<"$states" | head -1)"
  POSTURE_OPENED="$(sed -n "s/.*identifier=\([0-9]*\), name='OPENED'.*/\1/p" <<<"$states" | head -1)"
  [ -n "$POSTURE_CLOSED" ] && [ -n "$POSTURE_OPENED" ] || die "$SERIAL is not a foldable: $states"
}
resolve_postures
posture() { # $1 = closed | opened
  local id; [ "$1" = closed ] && id="$POSTURE_CLOSED" || id="$POSTURE_OPENED"
  adb -s "$SERIAL" shell cmd device_state state "$id" >/dev/null
  sleep 4
  show_home
  sleep 3
}

show_home
sleep 5
adb -s "$SERIAL" shell content write --uri "content://$PKG.config-ingest/wallpapers/mauritius.jpg" < "$WALLPAPER"
push_config "$CONFIG" "glass fixture"
jq -e '.success == true' <<<"$(query_json diagnostics)" >/dev/null || die "fixture did not apply: $(query_json diagnostics)"
ok "fixture applied: mauritius wallpaper, seven clocks and the dock"

# --- measure ------------------------------------------------------------------

: > "$WORK/out.tsv"
record() { printf '%s\t%s\n' "$1" "$2" >> "$WORK/out.tsv"; }
record rev "$REV"
record serial "$SERIAL"
record runs "$RUNS"

series() { # $1 = label
  local size w h i
  size="$(adb -s "$SERIAL" shell wm size | tr -d '\r' | awk '/size/ {s=$NF} END {print s}')"
  w="${size%x*}"; h="${size#*x}"
  adb -s "$SERIAL" shell dumpsys gfxinfo "$PKG" reset >/dev/null
  for i in $(seq "$RUNS"); do
    adb -s "$SERIAL" shell input swipe $((w / 2)) $((h * 3 / 4)) $((w / 2)) $((h / 4)) 250
    sleep 1.2
    adb -s "$SERIAL" shell input keyevent KEYCODE_BACK
    sleep 1.2
  done
  adb -s "$SERIAL" shell dumpsys gfxinfo "$PKG" | tr -d '\r' > "$WORK/gfx.txt"
  local total janky p50 p90 p95 p99
  total="$(awk -F': ' '/^Total frames rendered/ {print $2; exit}' "$WORK/gfx.txt")"
  janky="$(awk -F': ' '/^Janky frames:/ {split($2, a, " "); print a[1]; exit}' "$WORK/gfx.txt")"
  p50="$(awk '/^50th percentile/ {print $3+0; exit}' "$WORK/gfx.txt")"
  p90="$(awk '/^90th percentile/ {print $3+0; exit}' "$WORK/gfx.txt")"
  p95="$(awk '/^95th percentile/ {print $3+0; exit}' "$WORK/gfx.txt")"
  p99="$(awk '/^99th percentile/ {print $3+0; exit}' "$WORK/gfx.txt")"
  record "$1.frames" "$total"; record "$1.janky" "$janky"
  record "$1.p50_ms" "$p50"; record "$1.p90_ms" "$p90"; record "$1.p95_ms" "$p95"; record "$1.p99_ms" "$p99"
  ok "$1: $total frames, $janky janky, p50 ${p50} ms, p90 ${p90} ms, p99 ${p99} ms"
}

# A foldable has two physical displays, and a plain `screencap` refuses to
# pick one. Capture each and keep the one whose size is the current `wm size`
# (the PNG header carries width and height at bytes 16..23).
capture() { # $1 = output png
  local size want id got i
  # After a posture change the activity is recreated; wait until the grid is
  # on screen again (the dock cell), or the picture is the blank in between.
  for i in $(seq 20); do
    [ -n "$(desc_bounds grid-item:dock 2>/dev/null)" ] && break
    wake_screen; sleep 1
  done
  size="$(adb -s "$SERIAL" shell wm size | tr -d '\r' | awk '/size/ {s=$NF} END {print s}')"
  for id in $(adb -s "$SERIAL" shell dumpsys SurfaceFlinger --display-id | tr -d '\r' | sed -n 's/^Display \([0-9]*\).*/\1/p'); do
    adb -s "$SERIAL" exec-out screencap -d "$id" -p > "$WORK/shot.png" 2>/dev/null || continue
    got="$(python3 -c 'import struct,sys; d=open(sys.argv[1],"rb").read(24); print("%dx%d" % struct.unpack(">II", d[16:24])) if d[:8]==b"\x89PNG\r\n\x1a\n" else print("")' "$WORK/shot.png")"
    if [ "$got" = "$size" ]; then cp "$WORK/shot.png" "$1"; ok "screenshot $1 (display $id, $got)"; return 0; fi
  done
  warn "no display matched $size; no screenshot for $1"
}

for variant in $VARIANTS; do
  [ "$variant" = glass ] || die "unknown variant $variant"
  # A fresh process, so the backdrop is made (and logged) once per display.
  adb -s "$SERIAL" shell am force-stop "$PKG"
  adb -s "$SERIAL" logcat -c
  for display in cover inner; do
    [ "$display" = cover ] && posture closed || posture opened
    if [ -n "${SCREENSHOTS:-}" ]; then
      mkdir -p "$SCREENSHOTS"
      capture "$SCREENSHOTS/$variant-$display.png"
    fi
    series "$display.$variant"
  done
  # The blur, once per display and wallpaper, as the renderer logged it.
  adb -s "$SERIAL" logcat -d -s GlassBackdrop:I | tr -d '\r' \
    | sed -n 's/.*backdrop \([0-9]*x[0-9]*\) blur \([0-9]*\)px for \([0-9]*x[0-9]*\) in \([0-9]*\) ms (\(.*\))$/\3|\1|\2|\4|\5/p' \
    | while IFS='|' read -r window size blur ms detail; do
        record "backdrop.$variant.$window" "${ms} ms (${size}, blur ${blur}px; ${detail})"
        ok "backdrop for $window: $size, blur ${blur}px, ${ms} ms ($detail)"
      done
done

mkdir -p "$(dirname "$OUT")"
cp "$WORK/out.tsv" "$OUT"
ok "recorded $OUT"
