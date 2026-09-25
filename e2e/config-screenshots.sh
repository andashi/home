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
# Fold: eight columns inside; the cover shows columns 4-7 (#93), so what the
# cover shows sits in the right half. The Fold has seven rows on both
# displays, one more than this phone instance, so its dock sits in row 6: y is
# absolute and each layout places the dock in its own last row. A bottom dock
# both displays show is the right half's four columns.
FOLD_BOTTOM="$(item analog "$ANALOG" 4 0 2 2), $(item messages "$MESSAGES" 6 0 2 2), \
$(item search "$SEARCH" 4 2 4 1), $(item clock "$DIGITAL" 4 3 2 1), $(item bookmarks "$BOOKMARKS" 6 3 2 2), \
$(item clock-2 "$DIGITAL" 4 4 2 1), $(item clock-3 "$DIGITAL" 4 5 2 1), $(item clock-4 "$DIGITAL" 6 5 2 1), \
$(item bookmarks-2 "$BOOKMARKS" 0 0 4 3), $(item messages-2 "$MESSAGES" 0 3 4 2), $(item search-2 "$SEARCH" 0 5 4 1), \
$(item dock favorites 4 6 4 1)"
# A side dock that both displays show sits in column 7, the right edge of both.
FOLD_SIDE="$(item analog "$ANALOG" 4 0 3 2), $(item search "$SEARCH" 4 2 3 1), \
$(item messages "$MESSAGES" 4 3 3 2), $(item clock "$DIGITAL" 4 5 3 1), $(item clock-2 "$DIGITAL" 4 6 3 1), \
$(item dock favorites 7 0 1 7), \
$(item bookmarks "$BOOKMARKS" 0 0 4 3), $(item messages-2 "$MESSAGES" 0 3 4 3), $(item search-2 "$SEARCH" 0 6 4 1)"

# Every scene states the whole baseline plus its one variant. An absent key
# is "unmanaged" and keeps what the previous scene set, so a partial config
# would inherit the scene before it (review on #89).
scene_config() { # blur tint radius contrast wallpaperBlur labels themed position searchWallpaperBlur searchLayout searchFavorites phone-items fold-items
  cat <<EOF
{
  "schemaVersion": 2,
  "icons": { "themed": $7 },
  "appearance": {
    "glass": { "blur": $1, "tint": $2, "radius": $3, "contrast": "$4", "wallpaperBlur": $5, "searchWallpaperBlur": $9 },
    "wallpaper": { "image": "mauritius.jpg", "target": "both" }
  },
  "home": {
    "searchBar": { "position": "$8" },
    "favorites": $FAVORITES,
    "widgets": { "enabled": true },
    "grid": {
      "columns": 4,
      "labels": $6,
      "layouts": {
        "phone": { "items": [ ${12} ] },
        "fold": { "items": [ ${13} ] }
      }
    }
  },
  "search": {
    "favorites": ${11}, "allApps": true, "layout": "${10}", "labels": true,
    "contacts": true, "shortcuts": true, "filterBar": true, "openKeyboard": true,
    "launchOnEnter": true, "reversed": false, "hiddenItemsButton": false
  }
}
EOF
}

# blur tint radius contrast wallpaperBlur labels themed position searchWallpaperBlur
# searchLayout searchFavorites
BASE=(24 0.12 28 medium true true true top true grid true)
variant() { # name, the two layouts, then index=value overrides of BASE
  local name="$1" phone="$2" fold="$3"; shift 3
  local v=("${BASE[@]}") kv
  for kv in "$@"; do v[${kv%%=*}]="${kv#*=}"; done
  SCENES_CFG[$name]="$(scene_config "${v[@]}" "$phone" "$fold")"
}
declare -A SCENES_CFG
variant full-dock-bottom "$PHONE_BOTTOM" "$FOLD_BOTTOM"
variant full-dock-side "$PHONE_SIDE" "$FOLD_SIDE"
variant contrast-low "$PHONE_BOTTOM" "$FOLD_BOTTOM" 3=low
variant contrast-high "$PHONE_BOTTOM" "$FOLD_BOTTOM" 3=high
variant blur-0 "$PHONE_BOTTOM" "$FOLD_BOTTOM" 0=0 4=false
variant tint-0-4 "$PHONE_BOTTOM" "$FOLD_BOTTOM" 1=0.4
variant radius-8 "$PHONE_BOTTOM" "$FOLD_BOTTOM" 2=8
variant wallpaper-sharp "$PHONE_BOTTOM" "$FOLD_BOTTOM" 4=false
variant labels-off "$PHONE_BOTTOM" "$FOLD_BOTTOM" 5=false
variant icons-themed-off "$PHONE_BOTTOM" "$FOLD_BOTTOM" 6=false
variant search-bottom "$PHONE_BOTTOM" "$FOLD_BOTTOM" 7=bottom
# The baseline with search open on a query: shows what of the glass look
# reaches the search screen.
variant search-open "$PHONE_BOTTOM" "$FOLD_BOTTOM"
# A sharp home with search blurred behind it, and the reverse (#91).
variant search-sharp-home "$PHONE_BOTTOM" "$FOLD_BOTTOM" 4=false
variant search-sharp "$PHONE_BOTTOM" "$FOLD_BOTTOM" 8=false
# search (#91 part 3): app results as a list, and search opened without a
# query - where the favorites row shows - with the row switched off.
variant search-list "$PHONE_BOTTOM" "$FOLD_BOTTOM" 9=list
variant search-no-favorites "$PHONE_BOTTOM" "$FOLD_BOTTOM" 10=false
# The query a scene types after opening search; @open opens it without one.
declare -A SCENE_QUERY=([search-open]=c [search-sharp-home]=c [search-sharp]=c [search-list]=c [search-no-favorites]=@open)
ALL_SCENES="full-dock-bottom full-dock-side contrast-low contrast-high blur-0 tint-0-4 radius-8 wallpaper-sharp labels-off icons-themed-off search-bottom search-open search-sharp-home search-sharp search-list search-no-favorites"
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

wait_grid() { wait_on_home grid-item:dock; }

# The launcher sets a wallpaper deferred for a profile in the background
# asynchronously when its activity resumes; foregrounding alone does not
# mean it is rendered (review on #89). Wait until the system reports a set
# system wallpaper (id > 0) for user 0, as e2e/l4-config.sh reads it.
wallpaper_id() {
  adb -s "$SERIAL" shell dumpsys wallpaper 2>/dev/null | tr -d '\r' \
    | awk '/wallpaper state:/ { in_sec = (index($0, "System wallpaper state:") > 0); next }
           in_sec && index($0, "User 0:") { if (match($0, /id=[0-9]+/)) { print substr($0, RSTART+3, RLENGTH-3); exit } }'
}
wait_wallpaper() {
  local i id
  for i in $(seq 60); do
    id="$(wallpaper_id)"
    [ -n "$id" ] && [ "$id" != 0 ] && return 0
    sleep 1
  done
  die "the wallpaper was not rendered within 60 s (system id ${id:-none})"
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

# Opens search from the launcher's own bar (content-desc "Search"; the grid's
# search widgets only carry that as text) and types the scene's query.
# Closes search again after the picture, so the next scene starts on home.
ime_shown() {
  # Captured first: `grep -q` stops at the first match, the writer upstream
  # dies of SIGPIPE, and under pipefail a match would read as "not shown".
  local state
  state="$(adb -s "$SERIAL" shell dumpsys input_method | tr -d '\r')"
  grep -q 'mInputShown=true' <<<"$state"
}

search_is_open() { [ -n "$(desc_bounds 'Show filters')" ]; }

# Opens search from the launcher's own bar (content-desc "Search"; the grid's
# search widgets only carry that as text), types the scene's query and
# closes the keyboard, which would cover the results the picture is about.
open_search() { # $1 = scene
  local query="${SCENE_QUERY[$1]:-}" i
  [ -n "$query" ] || return 0
  tap_desc Search
  for i in $(seq 10); do search_is_open && break; sleep 1; done
  search_is_open || die "$1: tapping the bar did not open search"
  [ "$query" = @open ] || adb -s "$SERIAL" shell input text "$query"
  # The keyboard comes up a moment after the field is focused.
  for i in $(seq 5); do ime_shown && break; sleep 1; done
  if ime_shown; then
    adb -s "$SERIAL" shell input keyevent KEYCODE_BACK
    for i in $(seq 10); do ime_shown || break; sleep 1; done
  fi
  sleep 2
}

# Back from search to home, so the next scene starts there (#95).
close_search() { # $1 = scene
  local i
  [ -n "${SCENE_QUERY[$1]:-}" ] || return 0
  adb -s "$SERIAL" shell input keyevent KEYCODE_BACK
  for i in $(seq 5); do search_is_open || return 0; sleep 1; done
  die "$1: Back did not leave search"
}

mkdir -p "$OUT"
for name in $SCENES; do
  cfg="${SCENES_CFG[$name]:-}"
  [ -n "$cfg" ] || die "unknown scene $name"
  printf '%s\n' "$cfg" > "$WORK/$name.json"
  push_config "$WORK/$name.json" "$name"
  report="$(query_json diagnostics)"
  jq -e '.success == true' <<<"$report" >/dev/null || die "$name did not apply: $report"
  # A warning (a grid correction, say) still applies the file, but the
  # picture would then not match the config next to it in the docs.
  # wallpaper-pending-foreground only says the system is still cropping the
  # first wallpaper set in this run; the launcher is in front, so it renders.
  jq -e '[(.diagnostics // [])[] | select(.code != "wallpaper-pending-foreground")] | length == 0' <<<"$report" >/dev/null \
    || die "$name applied with diagnostics, the picture would not match its config: $(jq -c '.diagnostics' <<<"$report")"
  if [ "$FOLDABLE" = 1 ]; then
    posture closed grid-item:dock
    wait_wallpaper
    open_search "$name"
    capture "$OUT/$name-fold-cover.jpg" 540
    close_search "$name"
    posture opened grid-item:dock
    wait_wallpaper
    open_search "$name"
    capture "$OUT/$name-fold-inner.jpg" 780
    close_search "$name"
  else
    show_home
    wait_grid
    wait_wallpaper
    sleep 3
    open_search "$name"
    capture "$OUT/$name-phone.jpg" 540
    close_search "$name"
  fi
done
ok "scenes written to $OUT"
