#!/usr/bin/env bash
# Feature screenshots of the home grid (#23, plan step 8), taken on the
# GrapheneOS emulator through the public surface only: the ingest provider,
# the reload broadcast, `input` gestures and `screencap`. Every scene is a
# config the launcher receives plus, where it applies, a gesture; nothing is
# staged by hand, so the pictures can be regenerated for every release.
#
#   e2e/screenshots.sh [path/to/app-default-debug.apk]        # phone scenes
#   FOLD=1 SERIAL=emulator-5560 \
#     OVERLAY_DIR=$GOS_REPO/emulator/instances/test-fold \
#     e2e/screenshots.sh [apk]                                 # fold scenes
#
# Output: e2e/screenshots/<phone|fold>/NN-<scene>.png plus captions.tsv
# (scene, caption, guarding test) and, for the write-back scene, the pulled
# launcher.json. The captions are what the summary page is built from.
#
# Same harness as e2e/l4-grid.sh: one instance under the device lock, booted
# from the `clean` snapshot, HOME role and bind grant given by shell (the
# bind grant stands in for the system's always-allow dialog; it is a test
# convenience, see andashi/provisioning#2).
set -euo pipefail

gos_repo_default() {
  local d; d="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  while [ "$d" != "/" ]; do
    [ -d "$d/provisioning/emulator" ] && { printf '%s\n' "$d/provisioning"; return; }
    d="$(dirname "$d")"
  done
  printf '%s\n' "$HOME/Development/GrapheneOS"
}
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GOS_REPO="${GOS_REPO:-$(gos_repo_default)}"
FOLD="${FOLD:-0}"
if [ "$FOLD" = 1 ]; then
  SERIAL="${SERIAL:-emulator-5560}"
  export OVERLAY_DIR="${OVERLAY_DIR:-$GOS_REPO/emulator/instances/test-fold}"
  LAYOUT=fold; DOCK_W=8; OUT="$HERE/screenshots/fold"
else
  SERIAL="${SERIAL:-emulator-5556}"
  export OVERLAY_DIR="${OVERLAY_DIR:-$GOS_REPO/emulator/instances/test}"
  LAYOUT=phone; DOCK_W=4; OUT="$HERE/screenshots/phone"
fi
export SERIAL
LOCK_OWNER="screenshots@$SERIAL#$$"
SNAPSHOT="${SNAPSHOT:-clean}"
APK="${1:-$HERE/../app/app/build/outputs/apk/default/debug/app-default-debug.apk}"
PKG="${PKG:-org.andashi.home.debug}"
CLOCK_PKG="com.android.deskclock"
DIGITAL="$CLOCK_PKG/com.android.alarmclock.DigitalAppWidgetProvider"
ANALOG="$CLOCK_PKG/com.android.alarmclock.AnalogAppWidgetProvider"

c(){ [ -t 1 ] && printf '\033[%sm%s\033[0m\n' "$1" "$2" || printf '%s\n' "$2"; }
log(){ c '1;34' ":: $*"; }; ok(){ c '1;32' " + $*"; }; warn(){ c '1;33' " ! $*"; }
die(){ c '1;31' " x $*" >&2; exit 1; }

[ -d "$GOS_REPO/emulator" ] || die "provisioning repo not found at $GOS_REPO (set GOS_REPO)"
[ -f "$APK" ] || die "APK not found: $APK"
command -v jq >/dev/null || die "jq not found"
command -v python3 >/dev/null || die "python3 not found"

WORK="$(mktemp -d)"
HAVE_LOCK=0

# The accessibility "Time to take action" (ms), set for scene 07 only. Its
# value before is kept so it is restored, not reset to a guess; "null"
# means it was unset, and restoring deletes it.
UI_TIMEOUT_KEY=accessibility_interactive_ui_timeout_ms
UI_TIMEOUT_BEFORE=""
set_ui_timeout() { # $1 = ms
  UI_TIMEOUT_BEFORE="$(adb -s "$SERIAL" shell settings get secure "$UI_TIMEOUT_KEY" | tr -d '\r')"
  adb -s "$SERIAL" shell settings put secure "$UI_TIMEOUT_KEY" "$1"
}
restore_ui_timeout() {
  [ -n "$UI_TIMEOUT_BEFORE" ] || return 0
  if [ "$UI_TIMEOUT_BEFORE" = "null" ]; then
    adb -s "$SERIAL" shell settings delete secure "$UI_TIMEOUT_KEY" >/dev/null
  else
    adb -s "$SERIAL" shell settings put secure "$UI_TIMEOUT_KEY" "$UI_TIMEOUT_BEFORE"
  fi
  UI_TIMEOUT_BEFORE=""
}

cleanup() {
  local rc=$?
  if [ "$HAVE_LOCK" = 1 ]; then
    restore_ui_timeout 2>/dev/null || true
    if [ "$rc" -ne 0 ]; then
      printf '\n--- launcher logcat (last 60 lines) ---\n' >&2
      adb -s "$SERIAL" logcat -d -s HomeGridVM:* ConfigReloader:* AndroidRuntime:E 2>/dev/null \
        | tr -d '\r' | tail -n 60 >&2 || true
    fi
    (cd "$GOS_REPO" && SERIAL="$SERIAL" emulator/run.sh stop) >/dev/null 2>&1 || true
    (cd "$GOS_REPO" && emulator/device-lock.sh release "$LOCK_OWNER" "$SERIAL") >/dev/null 2>&1 || true
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

# shellcheck source=lib/grid-device.sh
. "$HERE/lib/grid-device.sh"

mkdir -p "$OUT"
rm -f "$OUT"/*.png "$OUT"/captions.tsv "$OUT"/*.json "$OUT"/*.jsonc
CAPTIONS="$OUT/captions.tsv"
printf 'scene\tcaption\tguarded by\n' > "$CAPTIONS"

# scene <file-stem> <caption> <guarding test>: screenshot + caption row.
scene() {
  sleep 2
  screenshot "$OUT/$1.png"
  printf '%s\t%s\t%s\n' "$1" "$2" "$3" >> "$CAPTIONS"
  ok "$1"
}

# --- fixtures ----------------------------------------------------------

installed_favorites() {
  local pkgs="" p installed
  installed="$(adb -s "$SERIAL" shell pm list packages 2>/dev/null | tr -d '\r')"
  for p in com.android.deskclock com.android.settings com.android.contacts com.android.messaging com.android.calendar com.android.camera2; do
    if grep -qx "package:$p" <<<"$installed"; then pkgs="$pkgs \"$p\","; fi
  done
  printf '%s' "${pkgs%,}"
}

# $1 = file, $2 = items json (the layout's items array body), [$3 = extra grid keys]
config_with_grid() {
  cat > "$1" <<EOF
{
  "schemaVersion": 2,
  "icons": { "themed": true, "enforceThemed": true },
  "appearance": { "transparency": { "background": 0.5, "surface": 0.7, "elevatedSurface": 0.9 } },
  "home": {
    // note: this comment must survive a write-back
    "searchBar": { "position": "bottom" },
    "favorites": [ $FAVS ],
    "widgets": { "enabled": true },
    "grid": {
      "columns": 4,
      ${3:-}
      "layouts": { "$LAYOUT": { "items": [
$2
      ] } }
    }
  }
}
EOF
}

# --- boot ----------------------------------------------------------------

(cd "$GOS_REPO" && emulator/device-lock.sh acquire "$LOCK_OWNER" "$SERIAL")
HAVE_LOCK=1
log "booting $SERIAL from snapshot '$SNAPSHOT' (overlays: $OVERLAY_DIR)"
(cd "$GOS_REPO" && SNAPSHOT="$SNAPSHOT" emulator/run.sh start)
unrooted_shell
adb -s "$SERIAL" install -r "$APK" 2>&1 | grep -q Success || die "adb install failed"
adb -s "$SERIAL" shell cmd role add-role-holder android.app.role.HOME "$PKG" >/dev/null 2>&1 || die "HOME role"
adb -s "$SERIAL" shell appwidget grantbind --package "$PKG" --user 0 >/dev/null 2>&1 || die "grantbind"
adb -s "$SERIAL" shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
FAVS="$(installed_favorites)"
[ "$FOLD" = 1 ] && resolve_postures
ok "installed, HOME role and bind grant given, favorites: $FAVS"

# --- scenes --------------------------------------------------------------

if [ "$FOLD" != 1 ]; then
  # 01: no grid in the config: the launcher's only default, the favorites row.
  cat > "$WORK/01.jsonc" <<EOF
{ "schemaVersion": 2, "home": { "favorites": [ $FAVS ], "widgets": { "enabled": true } } }
EOF
  push_config "$WORK/01.jsonc" "default"
  show_home; wait_cells 1 60
  scene 01-default-dock "A fresh launcher with favorites but no grid in the config: the only default is the favorites widget as a dock in the bottom row." "e2e/l4-grid.sh step 1; HomeGridDefaultsTest"

  # 02: the configured grid.
  config_with_grid "$WORK/02.jsonc" "        { \"id\": \"digital\", \"widget\": \"$DIGITAL\", \"x\": 0, \"y\": 0, \"w\": 3, \"h\": 1 },
        { \"id\": \"analog\", \"widget\": \"$ANALOG\", \"x\": 0, \"y\": 1, \"w\": 2, \"h\": 2 },
        { \"id\": \"dock\", \"widget\": \"favorites\", \"x\": 0, \"y\": 5, \"w\": 4, \"h\": 1 },"
  push_config "$WORK/02.jsonc" "grid"
  show_home; wait_cells 3 60
  scene 02-config-grid "The grid from launcher.json: two AOSP clock widgets at their configured cells and spans, the dock full width in the bottom row." "e2e/l4-grid.sh step 2; HomeGridScreenshotTest.phone"

  # 03-04: edit mode and a selected cell.
  enter_edit_mode "$DOCK_W"
  scene 03-edit-mode "Long-press on free cells enters edit mode: the cells wiggle, the edit bar offers Add and Done." "HomeGridEditModeTest (L2); HomeGridEditVMTest"
  tap_bounds "$(cell_center analog | awk '{print $1, $2, $1, $2}')"
  sleep 1
  scene 04-selected-handles "A tapped cell is selected: resize handle, remove badge and the +/- per axis, clamped to what the widget declares." "HomeGridEditModeTest.plusAndMinusStopAtTheLimits"

  # 05-06: a drag, photographed mid-air, then dropped onto the analog clock.
  drag_cell digital 0 2 "$DOCK_W" hold
  scene 05-drag-in-progress "A drag with the finger still down: the digital clock has been carried onto the analog clock's cells and the engine already previews the push-down, the analog clock moved one row down before the drop." "HomeGridEditModeTest.draggingACellOntoAnOccupiedCellPushesTheOccupantDown"
  drag_release
  sleep 1
  scene 06-after-drop "Dropped onto the analog clock: the occupant was pushed down, nothing overlaps." "GridLayoutTest (push-down); e2e/l4-grid.sh step 5"

  # 07: remove with undo. The snackbar is SnackbarDuration.Short, about 4 s,
  # and on the emulator one uiautomator dump alone takes about 3.8 s. Measured
  # on 2026-09-25: 1.0 s from the remove to the scene, 3.4 s for the scene,
  # 3.8 s for the dump that finds Undo. So Undo was gone before it was found
  # (#127). Waiting longer cannot fix that. What does: the platform's own
  # "Time to take action" accessibility setting, which Compose's SnackbarHost
  # honours through calculateRecommendedTimeoutMillis. A user who needs longer
  # to reach Undo gets the same snackbar for longer. Only scene 07 runs under
  # it: it is set here and restored right after the tap.
  set_ui_timeout 30000
  tap_bounds "$(cell_center analog | awk '{print $1, $2, $1, $2}')"
  sleep 1
  tap_id grid-remove
  sleep 1
  scene 07-remove-undo "Remove shows a snackbar with Undo; the AppWidget host id is released only after it expires." "HomeGridEditModeTest.removeThenUndoRestoresTheItem"
  tap_text "Undo" 2>/dev/null || tap_text "UNDO" 2>/dev/null \
    || die "no Undo action on screen: scenes 08 onward would run without the analog clock"
  wait_cell analog 10 "Undo did not restore the analog clock"
  restore_ui_timeout

  # 08: the favorites editor.
  tap_bounds "$(cell_center dock | awk '{print $1, $2, $1, $2}')"
  sleep 2
  scene 08-favorites-editor "Tapping the dock in edit mode opens the favorites editor: reorder, remove, add; it writes the one pin list search uses too." "HomeGridEditModeTest (favorites sheet)"
  adb -s "$SERIAL" shell input keyevent KEYCODE_BACK
  sleep 1

  # 09: Done writes back.
  tap_id grid-edit-done
  sleep 3
  scene 09-after-done "Done leaves edit mode and writes home.grid back into launcher.json on the device; the file is pulled next to this picture." "GridWriteBackTest; e2e/l4-grid.sh steps 3 and 4"
  adb -s "$SERIAL" pull "$DEVICE_CONFIG" "$OUT/09-launcher.json" >/dev/null 2>&1 || warn "could not pull launcher.json"
  query_json diagnostics > "$OUT/09-diagnostics.json" || true

  # 10-11: the dock in other shapes.
  config_with_grid "$WORK/10.jsonc" "        { \"id\": \"dock\", \"widget\": \"favorites\", \"x\": 0, \"y\": 0, \"w\": 1, \"h\": 6 },
        { \"id\": \"digital\", \"widget\": \"$DIGITAL\", \"x\": 1, \"y\": 0, \"w\": 3, \"h\": 1 },
        { \"id\": \"analog\", \"widget\": \"$ANALOG\", \"x\": 1, \"y\": 1, \"w\": 2, \"h\": 2 },"
  push_config "$WORK/10.jsonc" "side-dock"
  show_home; wait_cells 3 60
  scene 10-side-dock "The same favorites widget one column wide at the left edge: a side dock, the shape that stays visible on both displays of a fold." "HomeGridArrangementTest; FavoritesGridWidget layout by span"
  config_with_grid "$WORK/11.jsonc" "        { \"id\": \"digital\", \"widget\": \"$DIGITAL\", \"x\": 0, \"y\": 0, \"w\": 3, \"h\": 1 },
        { \"id\": \"dock\", \"widget\": \"favorites\", \"x\": 0, \"y\": 3, \"w\": 2, \"h\": 2 },
        { \"id\": \"analog\", \"widget\": \"$ANALOG\", \"x\": 2, \"y\": 3, \"w\": 2, \"h\": 2 },"
  push_config "$WORK/11.jsonc" "dock-block"
  show_home; wait_cells 3 60
  scene 11-dock-block "Favorites as a 2x2 block next to a widget: the list lays out as w times h icons, the rest stays reachable in search." "FavoritesGridWidget; D2 overflow rule"

  # 12: below the declared minimum.
  config_with_grid "$WORK/12.jsonc" "        { \"id\": \"digital\", \"widget\": \"$DIGITAL\", \"x\": 0, \"y\": 0, \"w\": 1, \"h\": 1 },
        { \"id\": \"analog\", \"widget\": \"$ANALOG\", \"x\": 0, \"y\": 1, \"w\": 2, \"h\": 2 },
        { \"id\": \"dock\", \"widget\": \"favorites\", \"x\": 0, \"y\": 5, \"w\": 4, \"h\": 1 },"
  push_config "$WORK/12.jsonc" "too-small"
  show_home; wait_cells 3 60
  query_json diagnostics > "$OUT/12-diagnostics.json" || true
  scene 12-widget-too-small "The config asked for a one-column digital clock, below its declared minimum: the launcher draws two columns and reports widget-too-small (diagnostics saved next to this picture)." "e2e/l4-grid.sh step 6; DefaultConfigStoreTest"

  # 13: locked.
  config_with_grid "$WORK/13.jsonc" "        { \"id\": \"digital\", \"widget\": \"$DIGITAL\", \"x\": 0, \"y\": 0, \"w\": 3, \"h\": 1 },
        { \"id\": \"analog\", \"widget\": \"$ANALOG\", \"x\": 0, \"y\": 1, \"w\": 2, \"h\": 2 },
        { \"id\": \"dock\", \"widget\": \"favorites\", \"x\": 0, \"y\": 5, \"w\": 4, \"h\": 1 }," '"locked": true,'
  push_config "$WORK/13.jsonc" "locked"
  show_home; wait_cells 3 60
  long_press "$(free_cell_point "$DOCK_W")"
  sleep 3
  # The picture must show the absence of the edit bar, so assert it before
  # capturing; a failed dump is an error, not an absence.
  bar="$(id_bounds grid-edit-done)" || die "uiautomator dump failed while checking the locked screen"
  [ -z "$bar" ] || die "locked layout showed the edit bar after a long press"
  scene 13-locked "locked: true in the config: a long press shows no edit bar and nothing is ever written back." "HomeGridEditModeTest.aLockedLayoutRefusesEditMode; e2e/l4-grid.sh step 7"
else
  # 20-22: one fold layout, three postures. The cover is the right half (#93).
  config_with_grid "$WORK/20.jsonc" "        { \"id\": \"digital\", \"widget\": \"$DIGITAL\", \"x\": 5, \"y\": 0, \"w\": 3, \"h\": 1 },
        { \"id\": \"analog\", \"widget\": \"$ANALOG\", \"x\": 6, \"y\": 1, \"w\": 2, \"h\": 2 },
        { \"id\": \"left\", \"widget\": \"$DIGITAL\", \"x\": 0, \"y\": 0, \"w\": 3, \"h\": 1 },
        { \"id\": \"dock\", \"widget\": \"favorites\", \"x\": 0, \"y\": 5, \"w\": 8, \"h\": 1 },"
  push_config "$WORK/20.jsonc" "fold"
  posture opened; wait_cells 4 60
  scene 20-fold-opened "Unfolded: one layout eight columns wide, a widget in the left half, the dock spanning the fold line." "HomeGridFoldTest (L2, foldable AVD); HomeGridScreenshotTest.foldInner"
  posture closed; wait_cells 3 60
  scene 21-fold-closed "Folded: the cover renders columns 4 to 7 of the same layout, where they were on the inner display; the left-half widget is inner-only, the dock shows its right half." "HomeGridFoldTest.closedShowsTheCoverAsTheRightHalf...; HomeGridScreenshotTest.foldCover"
  posture half; wait_cells 4 60
  scene 22-fold-half "Half-folded is rendered as opened (D7)." "HomeGridFoldTest.halfOpenedRendersAsOpened"

  # 23-24: side dock on both displays: the right edge (#93).
  config_with_grid "$WORK/23.jsonc" "        { \"id\": \"dock\", \"widget\": \"favorites\", \"x\": 7, \"y\": 0, \"w\": 1, \"h\": 6 },
        { \"id\": \"digital\", \"widget\": \"$DIGITAL\", \"x\": 4, \"y\": 0, \"w\": 3, \"h\": 1 },
        { \"id\": \"analog\", \"widget\": \"$ANALOG\", \"x\": 4, \"y\": 1, \"w\": 2, \"h\": 2 },
        { \"id\": \"left\", \"widget\": \"$DIGITAL\", \"x\": 0, \"y\": 0, \"w\": 3, \"h\": 1 },"
  push_config "$WORK/23.jsonc" "fold-side-dock"
  posture opened; wait_cells 4 60
  scene 23-fold-side-dock-opened "A side dock on the inner display: one column at the right edge, the edge that is an edge in both states." "HomeGridArrangementTest; D7, #93"
  posture closed; wait_cells 3 60
  scene 24-fold-side-dock-closed "The same side dock on the cover, unchanged and in the same place, next to the three columns that remain." "HomeGridFoldTest; clampToWindow"
  posture opened
fi

ok "screenshots: $(ls "$OUT"/*.png | wc -l) files in $OUT"
