#!/usr/bin/env bash
# Screenshots for the configuration docs (docs/configuration, #88), taken on
# the GrapheneOS emulator through the launcher's public surface only: the
# ingest provider, the reload broadcast and `screencap`. Every picture is a
# config the launcher receives, so the docs can be regenerated per release.
#
#   e2e/config-screenshots.sh [path/to/app.apk]                      # phone
#   SERIAL=emulator-5560 OVERLAY_DIR=$GOS_REPO/emulator/instances/test-fold \
#     e2e/config-screenshots.sh [apk]                                # fold
#
# One boot per instance: install Lawnicons (as provisioning does) and the
# launcher, upload provisioning's Mauritius wallpaper, then for every scene
# push its config, wait for the reload and capture. On a foldable instance
# each scene is captured closed (cover) and opened (inner display).
#
# Output: docs/configuration/img/<scene>-<phone|fold-cover|fold-inner>.jpg,
# downscaled; SCENES="a b" limits the run to some scenes.
#
# Instance and lock as in the L4 scripts; everything runs as the unrooted
# shell (uid 2000, asserted).
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
SERIAL="${SERIAL:-emulator-5556}"
export SERIAL
export OVERLAY_DIR="${OVERLAY_DIR:-$GOS_REPO/emulator/instances/test}"
LOCK_OWNER="config-screenshots@$SERIAL#$$"
SNAPSHOT="${SNAPSHOT:-clean}"
HERE="$(cd "$(dirname "$0")" && pwd)"
APK="${1:-$HERE/../app/app/build/outputs/apk/default/debug/app-default-debug.apk}"
PKG="${PKG:-org.andashi.home.debug}"
OUT="${OUT:-$HERE/../docs/configuration/img}"
WALLPAPER_TALL="$GOS_REPO/themes/mauritius/tall/wallpaper.jpg"
WALLPAPER_SQUARE="$GOS_REPO/themes/mauritius/square/wallpaper.jpg"

c(){ [ -t 1 ] && printf '\033[%sm%s\033[0m\n' "$1" "$2" || printf '%s\n' "$2"; }
log(){ c '1;34' ":: $*"; }; ok(){ c '1;32' " + $*"; }; warn(){ c '1;33' " ! $*"; }
die(){ c '1;31' " x $*" >&2; exit 1; }

[ "$INSTANCE_OVERRIDE" = "" ] || [ "$INSTANCE_OVERRIDE" = "so" ] \
  || die "SERIAL and OVERLAY_DIR name ONE instance - set both or neither"
[ -f "$APK" ] || die "APK not found: $APK"
command -v magick >/dev/null || die "ImageMagick (magick) not found"
LAWNICONS_APK="$(ls "$GOS_REPO"/apks/universal/app.lawnchair.lawnicons-*.apk 2>/dev/null | head -1)"
[ -n "$LAWNICONS_APK" ] || die "no Lawnicons APK under $GOS_REPO/apks/universal"

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

# --- widgets available on the GrapheneOS image ---------------------------------

ANALOG="com.android.deskclock/com.android.alarmclock.AnalogAppWidgetProvider"
DIGITAL="com.android.deskclock/com.android.alarmclock.DigitalAppWidgetProvider"
MESSAGES="com.android.messaging/com.android.messaging.widget.BugleWidgetProvider"
SEARCH="app.vanadium.browser/org.chromium.chrome.browser.searchwidget.SearchWidgetProvider"
BOOKMARKS="app.vanadium.browser/com.google.android.apps.chrome.appwidget.bookmarks.BookmarkThumbnailWidgetProvider"
FAVORITES='["com.android.dialer", "com.android.messaging", "app.vanadium.browser", "app.grapheneos.camera", "com.android.contacts", "com.android.settings"]'

item() { printf '{ "id": "%s", "widget": "%s", "x": %s, "y": %s, "w": %s, "h": %s }' "$@"; }

# A phone screen full of widgets, the dock as the bottom row.
PHONE_BOTTOM="$(item analog "$ANALOG" 0 0 2 2), $(item messages "$MESSAGES" 2 0 2 2), \
$(item search "$SEARCH" 0 2 4 1), $(item clock "$DIGITAL" 0 3 2 1), $(item bookmarks "$BOOKMARKS" 2 3 2 2), \
$(item clock-2 "$DIGITAL" 0 4 2 1), $(item dock favorites 0 5 4 1)"
# The same with the dock as a column on the right: one wide, six tall.
PHONE_SIDE="$(item analog "$ANALOG" 0 0 3 2), $(item search "$SEARCH" 0 2 3 1), \
$(item messages "$MESSAGES" 0 3 3 2), $(item clock "$DIGITAL" 0 5 3 1), $(item dock favorites 3 0 1 6)"
# Fold: eight columns inside; the cover shows columns 0-3.
FOLD_BOTTOM="$(item analog "$ANALOG" 0 0 2 2), $(item messages "$MESSAGES" 2 0 2 2), \
$(item search "$SEARCH" 0 2 4 1), $(item clock "$DIGITAL" 0 3 2 1), $(item bookmarks "$BOOKMARKS" 2 3 2 2), \
$(item clock-2 "$DIGITAL" 0 4 2 1), $(item bookmarks-2 "$BOOKMARKS" 4 0 4 3), \
$(item messages-2 "$MESSAGES" 4 3 4 2), $(item dock favorites 0 5 8 1)"
# A side dock that both displays show has to sit in column 3.
FOLD_SIDE="$(item analog "$ANALOG" 0 0 3 2), $(item search "$SEARCH" 0 2 3 1), \
$(item messages "$MESSAGES" 0 3 3 2), $(item clock "$DIGITAL" 0 5 3 1), $(item dock favorites 3 0 1 6), \
$(item bookmarks "$BOOKMARKS" 4 0 4 3), $(item messages-2 "$MESSAGES" 4 3 4 3)"

# scene <name> <appearance.glass JSON> <icons JSON> <grid extras JSON> <searchBar position> <phone items> <fold items>
scene_config() {
  cat <<EOF
{
  "schemaVersion": 2,
  "icons": $3,
  "appearance": {
    "glass": $2,
    "wallpaper": { "image": "mauritius.jpg", "target": "both" }
  },
  "home": {
    "searchBar": { "position": "$5" },
    "favorites": $FAVORITES,
    "widgets": { "enabled": true },
    "grid": {
      "columns": 4,
      $4
      "layouts": {
        "phone": { "items": [ $6 ] },
        "fold": { "items": [ $7 ] }
      }
    }
  }
}
EOF
}

GLASS='{ "blur": 24, "tint": 0.12, "radius": 28, "contrast": "medium", "wallpaperBlur": true }'
ICONS='{ "themed": true }'
declare -A SCENES_CFG
SCENES_CFG[full-dock-bottom]="$(scene_config x "$GLASS" "$ICONS" '"labels": true,' top "$PHONE_BOTTOM" "$FOLD_BOTTOM")"
SCENES_CFG[full-dock-side]="$(scene_config x "$GLASS" "$ICONS" '"labels": true,' top "$PHONE_SIDE" "$FOLD_SIDE")"
SCENES_CFG[contrast-low]="$(scene_config x '{ "contrast": "low" }' "$ICONS" '' top "$PHONE_BOTTOM" "$FOLD_BOTTOM")"
SCENES_CFG[contrast-high]="$(scene_config x '{ "contrast": "high" }' "$ICONS" '' top "$PHONE_BOTTOM" "$FOLD_BOTTOM")"
SCENES_CFG[blur-0]="$(scene_config x '{ "blur": 0, "wallpaperBlur": false }' "$ICONS" '' top "$PHONE_BOTTOM" "$FOLD_BOTTOM")"
SCENES_CFG[tint-0-4]="$(scene_config x '{ "tint": 0.4 }' "$ICONS" '' top "$PHONE_BOTTOM" "$FOLD_BOTTOM")"
SCENES_CFG[radius-8]="$(scene_config x '{ "radius": 8 }' "$ICONS" '' top "$PHONE_BOTTOM" "$FOLD_BOTTOM")"
SCENES_CFG[wallpaper-sharp]="$(scene_config x '{ "wallpaperBlur": false }' "$ICONS" '' top "$PHONE_BOTTOM" "$FOLD_BOTTOM")"
SCENES_CFG[labels-off]="$(scene_config x "$GLASS" "$ICONS" '"labels": false,' top "$PHONE_BOTTOM" "$FOLD_BOTTOM")"
SCENES_CFG[icons-themed-off]="$(scene_config x "$GLASS" '{ "themed": false }' '' top "$PHONE_BOTTOM" "$FOLD_BOTTOM")"
SCENES_CFG[search-bottom]="$(scene_config x "$GLASS" "$ICONS" '' bottom "$PHONE_BOTTOM" "$FOLD_BOTTOM")"
ALL_SCENES="full-dock-bottom full-dock-side contrast-low contrast-high blur-0 tint-0-4 radius-8 wallpaper-sharp labels-off icons-themed-off search-bottom"
SCENES="${SCENES:-$ALL_SCENES}"

# --- boot ---------------------------------------------------------------------

(cd "$GOS_REPO" && emulator/device-lock.sh acquire "$LOCK_OWNER" "$SERIAL")
HAVE_LOCK=1
log "booting $SERIAL from snapshot '$SNAPSHOT' (overlays: $OVERLAY_DIR)"
(cd "$GOS_REPO" && SNAPSHOT="$SNAPSHOT" emulator/run.sh start)
adb -s "$SERIAL" unroot >/dev/null 2>&1 || true
adb -s "$SERIAL" wait-for-device
[ "$(adb -s "$SERIAL" shell id -u | tr -d '\r')" = "2000" ] || die "adb is not the unrooted shell"
ok "adb as unrooted shell (uid 2000)"
adb -s "$SERIAL" install -r "$LAWNICONS_APK" | grep -q Success || die "Lawnicons install failed"
adb -s "$SERIAL" install -r "$APK" | grep -q Success || die "launcher install failed"
adb -s "$SERIAL" shell appwidget grantbind --package "$PKG" --user 0 >/dev/null
adb -s "$SERIAL" shell cmd role add-role-holder android.app.role.HOME "$PKG" >/dev/null 2>&1 || true

FOLDABLE=0
states="$(adb -s "$SERIAL" shell cmd device_state print-states 2>/dev/null | tr -d '\r')"
POSTURE_CLOSED="$(sed -n "s/.*identifier=\([0-9]*\), name='CLOSED'.*/\1/p" <<<"$states" | head -1)"
POSTURE_OPENED="$(sed -n "s/.*identifier=\([0-9]*\), name='OPENED'.*/\1/p" <<<"$states" | head -1)"
[ -n "$POSTURE_CLOSED" ] && [ -n "$POSTURE_OPENED" ] && FOLDABLE=1
ok "$( [ "$FOLDABLE" = 1 ] && echo foldable || echo phone ) instance"

show_home
sleep 5
wallpaper="$WALLPAPER_TALL"
adb -s "$SERIAL" shell content write --uri "content://$PKG.config-ingest/wallpapers/mauritius.jpg" < "$wallpaper"

wait_grid() {
  local i
  for i in $(seq 30); do
    [ -n "$(desc_bounds grid-item:dock 2>/dev/null)" ] && return 0
    wake_screen; show_home; sleep 1
  done
  die "the grid is not on screen"
}

posture() { # closed | opened
  local id; [ "$1" = closed ] && id="$POSTURE_CLOSED" || id="$POSTURE_OPENED"
  adb -s "$SERIAL" shell cmd device_state state "$id" >/dev/null
  sleep 4
  show_home
  wait_grid
  sleep 3
}

# Captures the physical display whose size is the current wm size (a
# foldable has two, and a plain screencap refuses to pick).
capture() { # $1 = output jpg, $2 = width
  local size id got
  size="$(adb -s "$SERIAL" shell wm size | tr -d '\r' | awk '/size/ {s=$NF} END {print s}')"
  for id in $(adb -s "$SERIAL" shell dumpsys SurfaceFlinger --display-id | tr -d '\r' | sed -n 's/^Display \([0-9]*\).*/\1/p'); do
    adb -s "$SERIAL" exec-out screencap -d "$id" -p > "$WORK/shot.png" 2>/dev/null || continue
    got="$(magick identify -format '%wx%h' "$WORK/shot.png" 2>/dev/null || true)"
    if [ "$got" = "$size" ]; then
      magick "$WORK/shot.png" -resize "${2}x" -quality 85 "$1"
      ok "$(basename "$1") (display $id, $got)"
      return 0
    fi
  done
  die "no display matched $size"
}

mkdir -p "$OUT"
for name in $SCENES; do
  cfg="${SCENES_CFG[$name]:-}"
  [ -n "$cfg" ] || die "unknown scene $name"
  printf '%s\n' "$cfg" > "$WORK/$name.json"
  push_config "$WORK/$name.json" "$name"
  report="$(query_json diagnostics)"
  jq -e '.success == true' <<<"$report" >/dev/null || die "$name did not apply: $report"
  jq -r '(.diagnostics // [])[] | "   diagnostic: \(.code) \(.path) \(.message)"' <<<"$report" | head -5
  if [ "$FOLDABLE" = 1 ]; then
    posture closed
    capture "$OUT/$name-fold-cover.jpg" 540
    posture opened
    capture "$OUT/$name-fold-inner.jpg" 780
  else
    show_home
    wait_grid
    sleep 3
    capture "$OUT/$name-phone.jpg" 540
  fi
done
ok "scenes written to $OUT"
