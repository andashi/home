#!/usr/bin/env bash
# L4 grid test: the single-page widget grid (ADR 0001, #23) end to end on the
# GrapheneOS emulator, through the public surface only: the ingest provider,
# the reload broadcast, the read-back provider, `adb shell input` and
# `uiautomator dump`. Same harness as e2e/l4-config.sh (ADR 0005).
#
#   e2e/l4-grid.sh [path/to/app-default-debug.apk]
#
# Steps (numbers are the plan's; the ones marked STUB land with later PRs):
#   1. push a schemaVersion 1 file, open the launcher: the read-back shows
#      schemaVersion 2, home.favorites, and a home.grid whose phone layout
#      holds the default favorites row in the bottom row (migration + the
#      one default a never-configured launcher gets, HomeGridDefaults)
#   2. push a schemaVersion 2 file with favorites and two AppWidgets of the
#      AOSP clock: the read-back `home` equals the file, and the uiautomator
#      bounds of every `grid-item:<id>` match the configured cells
#   3. edit by hand: long-press a free cell into edit mode, drag the
#      digital clock three rows down, tap Done; pull the file; the digital
#      clock's y is 3, only the home.grid object changed (the `// note`
#      comment in icons survives byte for byte), the report says self-write
#   4. idempotence: reloading the pulled file applies nothing
#   5. push a file that drops a 2x2 item onto the clock's cell: the clock's
#      bounds moved down one row, diagnostics clean
#   6. push w: 1 for the digital clock (declared minimum two cells wide):
#      diagnostic widget-too-small, bounds show two columns
#   6b. push the dock as a 1x7 column on the six-row grid: diagnostic
#      widget-too-large naming the grid, read-back h 6, the device file keeps
#      h 7, the dock drawn six rows tall (#140)
#   7. locked: push locked: true, a long press shows no edit bar, the file's
#      hash on the device is unchanged
#   8. malformed push: last good state kept, bounds unchanged
#   9. profile isolation: a second user gets its own grid through --user,
#      user 0's file and report are untouched
#
# FOLD=1 runs on a foldable instance (the GrapheneOS one is SERIAL=emulator-5560,
# OVERLAY_DIR=.../instances/test-fold; posture ids are resolved by name, since
# they differ between foldables): the fixtures carry a `fold` layout
# eight columns wide with an item in the left half, step 2 folds and
# unfolds between its checks (D7, #93: the cover shows columns 4-7, the left
# item is absent, the dock is clipped to four columns), and the phone-only
# steps 5 to 7 are skipped. The fold's default dock is the right edge column.
#
# Cells are found by their test tag "grid-item:<id>", which the grid root
# exposes as a resource id (#117). The favorites row anchors the geometry: it
# sits at x = 0 in the bottom row, so its bounds give the grid's left edge
# and the cell pitch, and every other cell is checked relative to it.
#
# Fixtures assume the AOSP DeskClock is installed (it is on the GrapheneOS
# image): the digital widget (3x1, resizable to one row) and the analog
# widget (2x2, minimum 110 dp). If the package is missing the AppWidget
# steps are skipped with a warning, the favorites-only steps still run.
#
# Everything runs as the unrooted shell, so the result holds for release
# GrapheneOS.
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
LOCK_OWNER="l4-grid@$SERIAL#$$"
SNAPSHOT="${SNAPSHOT:-clean}"
APK="${1:-$(dirname "$0")/../app/app/build/outputs/apk/default/debug/app-default-debug.apk}"
PKG="${PKG:-org.andashi.home.debug}"
FOLD="${FOLD:-0}"
if [ "$FOLD" = 1 ]; then LAYOUT=fold; DOCK_W=8; else LAYOUT=phone; DOCK_W=4; fi
CLOCK_PKG="com.android.deskclock"
DIGITAL_CLOCK="$CLOCK_PKG/com.android.alarmclock.DigitalAppWidgetProvider"
ANALOG_CLOCK="$CLOCK_PKG/com.android.alarmclock.AnalogAppWidgetProvider"

c(){ [ -t 1 ] && printf '\033[%sm%s\033[0m\n' "$1" "$2" || printf '%s\n' "$2"; }
log(){ c '1;34' ":: $*"; }; ok(){ c '1;32' " + $*"; }; warn(){ c '1;33' " ! $*"; }
die(){ c '1;31' " x $*" >&2; exit 1; }

[ "$INSTANCE_OVERRIDE" = "" ] || [ "$INSTANCE_OVERRIDE" = "so" ] \
  || die "SERIAL and OVERLAY_DIR name ONE instance - set both or neither"
[ -d "$GOS_REPO/emulator" ] || die "provisioning repo not found at $GOS_REPO (set GOS_REPO)"
[ -f "$APK" ] || die "APK not found: $APK (build it or pass a path)"
command -v jq >/dev/null || die "jq not found"
command -v python3 >/dev/null || die "python3 not found (parses the uiautomator dump)"

WORK="$(mktemp -d)"
HAVE_LOCK=0
cleanup() {
  local rc=$?
  if [ "$HAVE_LOCK" = 1 ]; then
    if [ "$rc" -ne 0 ]; then
      printf '\n--- launcher logcat (grid + config tags, last 80 lines) ---\n' >&2
      adb -s "$SERIAL" logcat -d -s HomeGridVM:* ConfigReloader:* ConfigWatcher:* AndroidRuntime:E 2>/dev/null \
        | tr -d '\r' | tail -n 80 >&2 || true
    fi
    (cd "$GOS_REPO" && SERIAL="$SERIAL" emulator/run.sh stop) >/dev/null 2>&1 || true
    (cd "$GOS_REPO" && emulator/device-lock.sh release "$LOCK_OWNER" "$SERIAL") >/dev/null 2>&1 || true
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

# The device helpers, their constants (STATE_URI, DEVICE_CONFIG, ...) and
# LAST_REPORT live in the shared library (#126).
# shellcheck source=lib/grid-device.sh
. "$(dirname "$0")/lib/grid-device.sh"

settle_then_broadcast() { # $1 = local config file, $2 = sha256, $3 = stage name
  write_config "$1"
  log "$3: waiting for file-watcher reload (hash ${2:0:12}...)"
  wait_report ".configSha256 == \"$2\" and .trigger == \"file-watcher\"" 30 "$3: file-watcher report"
  log "$3: broadcasting explicit reload"
  reload_broadcast
  wait_report ".configSha256 == \"$2\" and .trigger == \"broadcast\"" 30 "$3: broadcast report"
}

# --- screen helpers ----------------------------------------------------

device_config_sha() {
  adb -s "$SERIAL" shell sha256sum "$DEVICE_CONFIG" 2>/dev/null | tr -d '\r' | cut -d' ' -f1
}

# Polls the /config read-back until the jq filter holds, against a
# wall-clock deadline (#164): a read-back is a content query, well under a
# second, so the timeout is about how long the launcher takes to settle.
# Sets LAST_CONFIG.
LAST_CONFIG=""
SEEN_CONFIG=""
config_matches() { # $1 = jq filter
  # A failed query keeps the last read-back seen, for the timeout message.
  local got
  got="$(query_json config 2>/dev/null)" && [ -n "$got" ] || return 1
  SEEN_CONFIG="$got"
  jq -e "$1" >/dev/null 2>&1 <<<"$got" && LAST_CONFIG="$got"
}
wait_until() { # $1 = jq filter over /config, $2 = timeout (s), $3 = description
  SEEN_CONFIG=""
  retry_for "$2" config_matches "$1" && return 0
  printf 'last /config read-back:\n%s\n' "$SEEN_CONFIG" >&2
  die "timed out (${2}s) waiting for read-back: $3"
}

# Checks every cell of the dumped screen against the expected geometry:
#   $1 = expected "id x y w h" lines
# The favorites row ("dock") anchors the grid: x = 0, bottom row, so its left
# edge is the grid's left edge and its width gives the cell pitch.
#
# Polls: a reload is applied before its report is written, but the screen
# recomposes asynchronously, so the dump is repeated until the expected
# cells are there and the last mismatch is what a timeout reports.
# CELLS_TIMEOUT is wall-clock time (#164). 60 s leaves room for 10 or more
# dumps on an unloaded host (about 4 s each) and still 4 under load (about
# 15 s each). The loop it replaces counted 60 rounds, up to several minutes.
CELLS_TIMEOUT="${CELLS_TIMEOUT:-60}"
LAST_EXPECTED_CELLS=""
CELLS_RESULT=""
cells_match() { # $1 = expected lines, $2 = description
  CELLS_RESULT="$(check_cells "$1" "$2" 2>&1)"
}
assert_cells() { # $1 = expected lines, $2 = description
  LAST_EXPECTED_CELLS="$1"
  CELLS_RESULT=""
  if retry_for "$CELLS_TIMEOUT" cells_match "$1" "$2"; then
    printf '%s\n' "$CELLS_RESULT"
    return 0
  fi
  printf '%s\n' "$CELLS_RESULT" >&2
  die "timed out (${CELLS_TIMEOUT}s) waiting for cells: $2"
}

check_cells() { # $1 = expected lines, $2 = description
  local expected="$1" what="$2" cells gap scale
  cells="$(dump_cells)"
  [ -n "$cells" ] || { printf '%s: no grid-item cells on screen\n' "$what" >&2; return 1; }
  scale="$(density_scale)"
  gap="$(awk -v s="$scale" 'BEGIN { print 8 * s }')"
  python3 - "$cells" "$expected" "$gap" "$what" <<'PY'
import sys
cells = {l.split()[0]: list(map(int, l.split()[1:])) for l in sys.argv[1].splitlines() if l.strip()}
expected = {l.split()[0]: list(map(int, l.split()[1:])) for l in sys.argv[2].splitlines() if l.strip()}
gap = float(sys.argv[3]); what = sys.argv[4]
dock = cells.get("dock"); dock_exp = expected.get("dock")
if dock is None or dock_exp is None:
    sys.exit(f"{what}: the favorites row must be on screen and expected")
pitch = (dock[2] - dock[0] + gap) / dock_exp[2]
# The dock need not start at column 0 (the fold's default is the right edge).
left, dock_top = dock[0] - dock_exp[0] * pitch, dock[1]
tol = gap  # one gap of slack for rounding and the card's shadow
bad = []
for cid, (x, y, w, h) in expected.items():
    got = cells.get(cid)
    if got is None:
        bad.append(f"{cid}: expected at {x},{y} {w}x{h} but not on screen"); continue
    el = left + x * pitch
    et = dock_top - (dock_exp[1] - y) * pitch
    ew = w * pitch - gap
    eh = h * pitch - gap
    gl, gt, gr, gb = got
    if abs(gl - el) > tol or abs(gt - et) > tol or abs((gr - gl) - ew) > tol or abs((gb - gt) - eh) > tol:
        bad.append(f"{cid}: expected [{el:.0f},{et:.0f}] {ew:.0f}x{eh:.0f}, got [{gl},{gt}] {gr-gl}x{gb-gt}")
for cid in cells:
    if cid not in expected:
        bad.append(f"{cid}: on screen but not expected")
if bad:
    sys.exit(f"{what}:\n  " + "\n  ".join(bad))
print(f"pitch {pitch:.1f} px, {len(expected)} cells match")
PY
}

# A cell with a banner instead of a widget still has its content
# description, so the screen alone cannot tell a bound widget from a refused
# bind. The widget service can: every bound instance is listed under
# "Widgets:" with its host and provider.
assert_bound() { # $@ = provider components that must be bound to our host
  local widgets provider
  widgets="$(adb -s "$SERIAL" shell dumpsys appwidget 2>/dev/null | tr -d '\r' | sed -n '/^Widgets:/,/^Hosts:/p')"
  for provider in "$@"; do
    case "$widgets" in
      *"hostId:$HOST_ID"*"${provider#*/}"*|*"${provider#*/}"*"hostId:$HOST_ID"*) ;;
      *) printf '%s\n' "$widgets" >&2; die "no widget of $provider is bound to host $HOST_ID" ;;
    esac
  done
}
HOST_ID=44203

# --- fixtures ----------------------------------------------------------

# The v1 shape every zone pushed before #23: the launcher migrates it.
LEGACY_CONFIG="$WORK/legacy-v1.jsonc"
cat > "$LEGACY_CONFIG" <<'EOF'
{
  "schemaVersion": 1,
  "icons": { "themed": true, "enforceThemed": true },
  "appearance": { "transparency": { "background": 0.5, "surface": 0.7, "elevatedSurface": 0.9 } },
  "home": {
    "searchBar": { "position": "bottom" },
    "dock": { "enabled": true, "favorites": [] },
    "widgets": { "enabled": true, "widgets": ["apps"] },
  },
}
EOF

# Two AOSP clock widgets and the dock; the note comment is what PR 6's
# write-back must preserve byte for byte.
GRID_CONFIG="$WORK/grid.jsonc"
cat > "$GRID_CONFIG" <<EOF
{
  "schemaVersion": 2,
  "icons": {
    // note: this comment must survive a write-back (PR 6)
    "themed": true, "enforceThemed": true,
  },
  "appearance": { "transparency": { "background": 0.5, "surface": 0.7, "elevatedSurface": 0.9 } },
  "home": {
    "searchBar": { "position": "bottom" },
    "favorites": [],
    "widgets": { "enabled": true },
    "grid": {
      "columns": 4,
      "locked": false,
      "layouts": {
        "phone": { "items": [
          { "id": "digital", "widget": "$DIGITAL_CLOCK", "x": 0, "y": 0, "w": 3, "h": 1 },
          { "id": "analog", "widget": "$ANALOG_CLOCK", "x": 0, "y": 1, "w": 2, "h": 2 },
          { "id": "dock", "widget": "favorites", "x": 0, "y": 5, "w": 4, "h": 1 },
        ] },
        // Eight columns wide (D7); the cover is the right half (#93), so the
        // clocks sit there and "left" lives in the half only the inner display shows.
        "fold": { "items": [
          { "id": "digital", "widget": "$DIGITAL_CLOCK", "x": 5, "y": 0, "w": 3, "h": 1 },
          { "id": "analog", "widget": "$ANALOG_CLOCK", "x": 6, "y": 1, "w": 2, "h": 2 },
          { "id": "left", "widget": "$DIGITAL_CLOCK", "x": 0, "y": 0, "w": 3, "h": 1 },
          { "id": "dock", "widget": "favorites", "x": 0, "y": 5, "w": 8, "h": 1 },
        ] },
      },
    },
  },
}
EOF

# A 2x2 item dropped onto the digital clock's cell: the clock must move down.
PUSHDOWN_CONFIG="$WORK/pushdown.jsonc"
sed 's|{ "id": "digital", "widget": "'"$DIGITAL_CLOCK"'", "x": 0, "y": 0, "w": 3, "h": 1 },|{ "id": "analog2", "widget": "'"$ANALOG_CLOCK"'", "x": 0, "y": 0, "w": 2, "h": 2 },\n          { "id": "digital", "widget": "'"$DIGITAL_CLOCK"'", "x": 0, "y": 0, "w": 3, "h": 1 },|' \
  "$GRID_CONFIG" > "$PUSHDOWN_CONFIG"

# The analog clock at one row: below its declared minimum.
TOO_SMALL_CONFIG="$WORK/too-small.jsonc"
# The digital clock declares minResizeWidth 136 dp (dumpsys appwidget on the
# GrapheneOS image: minResize=(34817x15105), TypedValue-encoded dp), which is
# two cells on a phone grid; asking for one is below the minimum. The analog
# clock resizes down to 55x55 dp, so a one-row analog clock is NOT too small.
sed 's|"id": "digital", "widget": "'"$DIGITAL_CLOCK"'", "x": 0, "y": 0, "w": 3, "h": 1|"id": "digital", "widget": "'"$DIGITAL_CLOCK"'", "x": 0, "y": 0, "w": 1, "h": 1|' \
  "$GRID_CONFIG" > "$TOO_SMALL_CONFIG"

# The dock as a right-edge column seven rows tall, on a six-row phone grid:
# above what fits (#140). Alone, so the shrunk dock collides with nothing.
TOO_LARGE_CONFIG="$WORK/too-large.jsonc"
cat > "$TOO_LARGE_CONFIG" <<'EOF'
{
  "schemaVersion": 2,
  "home": {
    "widgets": { "enabled": true },
    "grid": { "columns": 4, "locked": false, "layouts": {
      "phone": { "items": [ { "id": "dock", "widget": "favorites", "x": 3, "y": 0, "w": 1, "h": 7 } ] }
    } }
  }
}
EOF

LOCKED_CONFIG="$WORK/locked.jsonc"
sed 's|"locked": false|"locked": true|' "$GRID_CONFIG" > "$LOCKED_CONFIG"

MALFORMED_CONFIG="$WORK/malformed.jsonc"
printf '{ "schemaVersion": 2, "home": { not json at all\n' > "$MALFORMED_CONFIG"

H_LEGACY="$(sha256sum "$LEGACY_CONFIG" | cut -d' ' -f1)"
H_GRID="$(sha256sum "$GRID_CONFIG" | cut -d' ' -f1)"
H_PUSHDOWN="$(sha256sum "$PUSHDOWN_CONFIG" | cut -d' ' -f1)"
H_TOO_SMALL="$(sha256sum "$TOO_SMALL_CONFIG" | cut -d' ' -f1)"
H_TOO_LARGE="$(sha256sum "$TOO_LARGE_CONFIG" | cut -d' ' -f1)"
H_MALFORMED="$(sha256sum "$MALFORMED_CONFIG" | cut -d' ' -f1)"
H_LOCKED="$(sha256sum "$LOCKED_CONFIG" | cut -d' ' -f1)"

# --- boot + install ----------------------------------------------------

(cd "$GOS_REPO" && emulator/device-lock.sh acquire "$LOCK_OWNER" "$SERIAL")
HAVE_LOCK=1

log "booting $SERIAL from snapshot '$SNAPSHOT' (overlays: $OVERLAY_DIR)"
(cd "$GOS_REPO" && SNAPSHOT="$SNAPSHOT" emulator/run.sh start)
unrooted_shell

log "installing $(basename "$APK")"
install_out="$(adb -s "$SERIAL" install -r "$APK" 2>&1)" || { printf '%s\n' "$install_out" >&2; die "adb install failed"; }
case "$install_out" in *Success*) ;; *) printf '%s\n' "$install_out" >&2; die "adb install failed" ;; esac
ok "package installed: $PKG"

# No animations on the test instance: `uiautomator dump` waits for the
# window to go idle and gives up while edit mode's wiggle runs, so it
# would never see the edit bar. The launcher reads the animator scale as
# its reduced-motion signal (rememberReducedMotion), which is also what a
# user who turned animations off gets; the wiggle itself is covered by the
# L2 suites.
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb -s "$SERIAL" shell settings put global "$scale" 0 >/dev/null 2>&1 || die "could not set $scale"
done

# Binding an AppWidget without a dialog needs the HOME role (the widget
# service whitelists the role holder); the fresh snapshot holds it for
# launcher3. Shell may hand it over on this build.
adb -s "$SERIAL" shell cmd role add-role-holder android.app.role.HOME "$PKG" >/dev/null 2>&1 \
  || die "could not grant the HOME role to $PKG"
adb -s "$SERIAL" shell dumpsys role 2>/dev/null | tr -d '\r' | grep -A2 'android.app.role.HOME' | grep -q "holders=$PKG" \
  || die "$PKG does not hold the HOME role after add-role-holder"
ok "HOME role granted to $PKG"

# The HOME role does not carry the bind-widget grant (measured 2026-09-22 on
# the foldable instance: role held, dumpsys appwidget "Grants:" empty, every
# bind refused). A real user gives it once through the system's bind dialog
# ("always allow", the cell's Allow action); here the shell tool stands in
# for that tap.
adb -s "$SERIAL" shell appwidget grantbind --package "$PKG" --user 0 >/dev/null 2>&1 \
  || die "appwidget grantbind failed for $PKG"
adb -s "$SERIAL" shell dumpsys appwidget 2>/dev/null | tr -d '\r' | sed -n '/^Grants:/,$p' | grep -q "package=$PKG" \
  || die "$PKG has no bind-widget grant after grantbind"
ok "bind-widget grant given to $PKG (stands in for the always-allow dialog)"

HAVE_CLOCK=1
adb -s "$SERIAL" shell pm list packages | tr -d '\r' | grep -x "package:$CLOCK_PKG" >/dev/null \
  || { HAVE_CLOCK=0; warn "$CLOCK_PKG not installed: AppWidget steps are skipped"; }
# The fold steps (postures, the cover, no relaunch on fold, #120) live in the
# AppWidget branch; in fold mode a missing clock must not pass silently
# (review on #121).
[ "$FOLD" != 1 ] || [ "$HAVE_CLOCK" = 1 ] || die "FOLD=1 needs $CLOCK_PKG: without it the fold and no-relaunch checks would be skipped"

# --- 1. schemaVersion 1 file + the default favorites row ----------------

write_config "$LEGACY_CONFIG"
wait_report ".success == true and .configSha256 == \"$H_LEGACY\"" 90 "first reload of the v1 file"
show_home
# The default row is written on the launcher's first render; on a fresh
# install that is a cold start plus the DataStore flag, so poll the read-back.
wait_until '(.home.grid.layouts.'"$LAYOUT"'.items | length) > 0' 60 "the default $LAYOUT layout"
effective="$LAST_CONFIG"
assert_jq "$effective" '.schemaVersion == 2 and .home.favorites == [] and (.home | has("dock") | not)' \
  "v1 file migrated to the v2 shape"
if [ "$FOLD" = 1 ]; then
  # #93: the fold's default dock is the right edge column, full height.
  assert_jq "$effective" \
    '[.home.grid.layouts.fold.items[] | select(.widget == "favorites" and .x == 7 and .y == 0 and .w == 1 and .h >= 6)] | length == 1' \
    "the default favorites dock is the right edge column"
else
  assert_jq "$effective" \
    '[.home.grid.layouts.'"$LAYOUT"'.items[] | select(.widget == "favorites" and .x == 0 and .w == '"$DOCK_W"' and .h == 1 and .y >= 4)] | length == 1' \
    "the default favorites row sits full width in the bottom row"
fi
ok "v1 file migrated, default favorites row written"
wake_screen
assert_cells "dock $(jq -r '.home.grid.layouts.'"$LAYOUT"'.items[] | select(.widget == "favorites") | "\(.x) \(.y) \(.w) \(.h)"' <<<"$effective")" \
  "default grid on screen"
ok "default favorites row measured on screen"

if [ "$HAVE_CLOCK" = 1 ]; then
  # --- 2. a configured grid, on screen where the file says -------------
  settle_then_broadcast "$GRID_CONFIG" "$H_GRID" "grid"
  assert_jq "$LAST_REPORT" '.success == true and (((.diagnostics // []) | map(select(.severity == "error")) | length) == 0)' \
    "grid config applied without errors"
  show_home
  effective="$(query_json config)" || die "could not query /config"
  if [ "$FOLD" = 1 ]; then
    assert_jq "$effective" \
      '(.home.grid.layouts.fold.items | map({id, x, y, w, h})) ==
       [{"id":"digital","x":5,"y":0,"w":3,"h":1},{"id":"analog","x":6,"y":1,"w":2,"h":2},
        {"id":"left","x":0,"y":0,"w":3,"h":1},{"id":"dock","x":0,"y":5,"w":8,"h":1}]' \
      "read-back fold grid equals the pushed file"
    resolve_postures
    posture opened
    assert_cells $'digital 5 0 3 1\nanalog 6 1 2 2\nleft 0 0 3 1\ndock 0 5 8 1' "configured grid on the inner display"
    assert_bound "$DIGITAL_CLOCK" "$ANALOG_CLOCK"
    ok "fold, opened: eight columns, the left-half item on screen, both widgets bound"
    # From here on every fold and unfold must keep the same launcher activity
    # (#120: configChanges); the events log says whether it was relaunched.
    adb -s "$SERIAL" logcat -b events -c
    # Closed: the cover renders columns 4..7 of the same layout (D7, #93),
    # so on screen layout column 5 is the cover's second. The dock line says
    # 4 wide, so the pitch is measured from four columns.
    posture closed
    DOCK_W=4
    assert_cells $'digital 1 0 3 1\nanalog 2 1 2 2\ndock 0 5 4 1' "the cover shows the right half"
    [ -z "$(dump_cells | awk '$1 == "left"')" ] || die "the left-half item is on the cover"
    ok "fold, closed: four columns, the right half, the left-half item absent, the dock clipped"
    posture half
    DOCK_W=8
    assert_cells $'digital 5 0 3 1\nanalog 6 1 2 2\nleft 0 0 3 1\ndock 0 5 8 1' "half-opened renders as opened"
    posture opened
    assert_cells $'digital 5 0 3 1\nanalog 6 1 2 2\nleft 0 0 3 1\ndock 0 5 8 1' "opened again"
    ok "fold, half-opened and opened again: the inner layout is back"
    events="$(adb -s "$SERIAL" logcat -b events -d | tr -d '\r')"
    relaunched="$(grep -E 'wm_relaunch_(resume_)?activity|wm_on_create_called' <<<"$events" | grep -F 'LauncherActivity' || true)"
    [ -z "$relaunched" ] || { printf '%s\n' "$relaunched" >&2; die "the launcher activity was recreated by a fold or unfold"; }
    ok "fold, closed, half-opened, opened: the same launcher activity throughout, no relaunch (#120)"
  else
    assert_jq "$effective" \
      '(.home.grid.layouts.phone.items | map({id, widget, x, y, w, h})) ==
       [{"id":"digital","widget":"'"$DIGITAL_CLOCK"'","x":0,"y":0,"w":3,"h":1},
        {"id":"analog","widget":"'"$ANALOG_CLOCK"'","x":0,"y":1,"w":2,"h":2},
        {"id":"dock","widget":"favorites","x":0,"y":5,"w":4,"h":1}]' \
      "read-back grid equals the pushed file"
    assert_cells $'digital 0 0 3 1\nanalog 0 1 2 2\ndock 0 5 4 1' "configured grid on screen"
    assert_bound "$DIGITAL_CLOCK" "$ANALOG_CLOCK"
    ok "configured grid: read-back equals the file, cells measured where configured, both widgets bound"
  fi

  # --- 3. edit by hand, then the file follows ----------------------------
  wake_screen
  enter_edit_mode "$DOCK_W"
  ok "edit mode entered by long press"
  drag_cell digital 0 3 "$DOCK_W"
  sleep 1
  tap_id grid-edit-done
  wait_report '.trigger == "self-write" and .success == true' 30 "the launcher's own write-back report"
  H_WRITTEN="$(jq -r '.configSha256' <<<"$LAST_REPORT")"
  [ "$(device_config_sha)" = "$H_WRITTEN" ] || die "the file on the device does not carry the self-write hash"
  PULLED="$WORK/pulled.jsonc"
  adb -s "$SERIAL" pull "$DEVICE_CONFIG" "$PULLED" >/dev/null 2>&1 || die "adb pull of $DEVICE_CONFIG failed"
  # The pulled file is JSONC (comments, trailing commas), which jq refuses:
  # python strips both before it reads the digital clock's row.
  pulled_y="$(python3 - "$PULLED" "$LAYOUT" <<'PY'
import json, re, sys
text = open(sys.argv[1]).read()
text = re.sub(r"//[^\n]*", "", text)
text = re.sub(r",(\s*[}\]])", r"\1", text)
doc = json.loads(text)
print(next(i["y"] for i in doc["home"]["grid"]["layouts"][sys.argv[2]]["items"] if i["id"] == "digital"))
PY
)"
  [ "$pulled_y" = "3" ] || { cat "$PULLED" >&2; die "the pulled file does not hold the digital clock at y 3 (got '$pulled_y')"; }
  grep -qF '// note: this comment must survive a write-back' "$PULLED" \
    || die "the note comment did not survive the write-back"
  python3 - "$GRID_CONFIG" "$PULLED" <<'PY' || die "something outside home.grid changed in the write-back"
import sys
before = open(sys.argv[1]).read(); after = open(sys.argv[2]).read()
cut = '"grid":'
b, a = before.index(cut), after.index(cut)
assert before[:b] == after[:a], "the bytes before home.grid differ"
assert before.rstrip().endswith('}') and after.rstrip().endswith('}')
PY
  effective="$(query_json config)" || die "could not query /config"
  assert_jq "$effective" '(.home.grid.layouts.'"$LAYOUT"'.items[] | select(.id == "digital") | .y) == 3' \
    "the read-back holds the hand-moved clock"
  wake_screen
  if [ "$FOLD" = 1 ]; then
    assert_cells $'digital 5 3 3 1\nanalog 6 1 2 2\nleft 0 0 3 1\ndock 0 5 8 1' "the moved clock on screen"
  else
    assert_cells $'digital 0 3 3 1\nanalog 0 1 2 2\ndock 0 5 4 1' "the moved clock on screen"
  fi
  ok "hand edit: the clock moved, only home.grid changed in the file, the report says self-write"

  # --- 4. the pulled file is a no-op when reloaded ------------------------
  # The bytes are the launcher's own, so the watcher would skip them; an
  # explicit reload always runs and must find nothing to apply.
  reload_broadcast
  wait_report ".configSha256 == \"$H_WRITTEN\" and .trigger == \"broadcast\"" 30 "reload of the pulled file"
  assert_jq "$LAST_REPORT" '.success == true and ((.appliedMutations // []) == [])' "the pulled file applies nothing"
  ok "idempotence: the pulled file reloads as a no-op"

  if [ "$FOLD" = 1 ]; then
    warn "steps 5 to 7 (push-down, minimum, locked) are phone-layout fixtures; skipped in FOLD mode"
  else
  # --- 5. push-down ----------------------------------------------------
  settle_then_broadcast "$PUSHDOWN_CONFIG" "$H_PUSHDOWN" "push-down"
  assert_jq "$LAST_REPORT" '.success == true' "push-down config applied"
  effective="$(query_json config)" || die "could not query /config"
  assert_jq "$effective" \
    '(.home.grid.layouts.phone.items[] | select(.id == "digital") | .y) == 2' \
    "the digital clock moved below the item dropped on it"
  show_home
  assert_cells $'analog2 0 0 2 2\ndigital 0 2 3 1\nanalog 0 3 2 2\ndock 0 5 4 1' "push-down on screen"
  ok "push-down: the clock moved down, the rest followed"

  # --- 6. below the declared minimum -----------------------------------
  settle_then_broadcast "$TOO_SMALL_CONFIG" "$H_TOO_SMALL" "too-small"
  assert_jq "$LAST_REPORT" \
    '.success == true and (((.diagnostics // []) | map(select(.code == "widget-too-small")) | length) > 0)' \
    "an item below its provider minimum is reported"
  effective="$(query_json config)" || die "could not query /config"
  assert_jq "$effective" '(.home.grid.layouts.phone.items[] | select(.id == "digital") | .w) == 2' \
    "the digital clock keeps its two-column minimum"
  show_home
  assert_cells $'digital 0 0 2 1\nanalog 0 1 2 2\ndock 0 5 4 1' "minimum enforced on screen"
  ok "below minimum: widget-too-small reported, two columns drawn"

  # --- 6b. above what fits (#140) ------------------------------------------
  # Read-back serves what is in effect, the file keeps what it asked for, and
  # the report says why the two differ.
  settle_then_broadcast "$TOO_LARGE_CONFIG" "$H_TOO_LARGE" "too-large"
  assert_jq "$LAST_REPORT" \
    '.success == true and ([.diagnostics[]? | select(.code == "widget-too-large" and .path == "home.grid.layouts.phone.items[0]" and (.message | contains("asks for 1x7 cells, more than the grid")))] | length) == 1' \
    "the dock above the grid's rows is reported, with the grid as what set the limit"
  effective="$(query_json config)" || die "could not query /config"
  assert_jq "$effective" '(.home.grid.layouts.phone.items[] | select(.id == "dock") | .h) == 6' \
    "the read-back serves the six rows in effect"
  [ "$(device_config_sha)" = "$H_TOO_LARGE" ] || die "the file on the device changed: it must keep h 7"
  show_home
  assert_cells 'dock 3 0 1 6' "the dock shrunk to the grid on screen"
  ok "above what fits: widget-too-large reported, read-back 6, the file keeps 7"

  # --- 7. a locked layout refuses edit mode ------------------------------
  settle_then_broadcast "$LOCKED_CONFIG" "$H_LOCKED" "locked"
  show_home
  wake_screen
  assert_cells $'digital 0 0 3 1\nanalog 0 1 2 2\ndock 0 5 4 1' "locked grid on screen"
  point="$(free_cell_point "$DOCK_W")" || die "no dock on screen"
  long_press "$point"
  sleep 3
  [ -z "$(id_bounds grid-edit-done)" ] || die "a locked layout entered edit mode"
  [ "$(device_config_sha)" = "$H_LOCKED" ] || die "the locked file changed on the device"
  ok "locked: the long press shows no edit bar, the file is unchanged"
  fi

else
  warn "steps 2, 5 and 6 need $CLOCK_PKG; skipped"
fi

# --- 8. malformed push keeps the last good state -----------------------
# Against whatever the last good state is: the configured grid when the
# clock is installed, the default one otherwise.

good_grid="$(query_json config | jq -c '.home.grid')" || die "could not query /config"
settle_then_broadcast "$MALFORMED_CONFIG" "$H_MALFORMED" "malformed"
assert_jq "$LAST_REPORT" \
  '.success == false and ([.diagnostics[] | select(.severity == "error" and .code == "malformed-json")] | length > 0)' \
  "malformed config yields a failed report"
effective="$(query_json config)" || die "could not query /config"
[ "$(jq -c '.home.grid' <<<"$effective")" = "$good_grid" ] || die "the effective grid changed after a malformed push"
show_home
wake_screen
assert_cells "$LAST_EXPECTED_CELLS" "last good state on screen"
ok "malformed push: last good state kept, cells unchanged"

# --- 9. profile isolation: a second user has its own grid ----------------
# The same push through --user reaches the launcher instance of that user
# and nothing else: user 0's file hash and last report stay as they were.
H_USER0="$(device_config_sha)"
REPORT_USER0="$(query_json diagnostics)"
uid="$(adb -s "$SERIAL" shell pm create-user l4grid 2>&1 | tr -d '\r' | grep -o 'id [0-9]*' | cut -d' ' -f2)"
[ -n "$uid" ] || die "pm create-user failed"
log "user $uid created, starting it"
adb -s "$SERIAL" shell am start-user -w "$uid" </dev/null >/dev/null || die "user $uid could not be started"
adb -s "$SERIAL" shell pm install-existing --user "$uid" "$PKG" >/dev/null 2>&1 || die "install-existing for user $uid failed"
adb -s "$SERIAL" shell appwidget grantbind --package "$PKG" --user "$uid" >/dev/null 2>&1 || true
# A new user's launcher process and its ingest come up on demand, so the
# write is retried; both waits are wall-clock time (#164).
out=""
user_write_lands() { out="$(adb_t shell content write --user "$uid" --uri "$INGEST_URI" < "$GRID_CONFIG" 2>&1 | tr -d '\r')" && [ -z "$out" ]; }
retry_for 60 user_write_lands || { printf '%s\n' "$out" >&2; die "content write --user $uid kept failing for 60 s"; }
adb -s "$SERIAL" shell am broadcast -n "$RECEIVER" -a "$ACTION" --user "$uid" >/dev/null 2>&1 || die "broadcast --user $uid failed"
report=""
user_reports_the_grid() {
  report="$(adb_t shell content query --uri "$STATE_URI/diagnostics" --user "$uid" 2>/dev/null | tr -d '\r')" \
    && jq -e ".configSha256 == \"$H_GRID\" and .success == true" >/dev/null 2>&1 <<<"${report#Row: 0 json=}"
}
retry_for 60 user_reports_the_grid || { printf '%s\n' "$report" >&2; die "user $uid never reported the grid config within 60 s"; }
user_config="$(adb -s "$SERIAL" shell content query --uri "$STATE_URI/config" --user "$uid" 2>/dev/null | tr -d '\r')"
assert_jq "${user_config#Row: 0 json=}" '(.home.grid.layouts.'"$LAYOUT"'.items | map(.id)) | index("dock") != null' \
  "user $uid holds the pushed grid"
[ "$(device_config_sha)" = "$H_USER0" ] || die "user 0's file changed when user $uid was provisioned"
[ "$(query_json diagnostics)" = "$REPORT_USER0" ] || die "user 0's last report changed when user $uid was provisioned"
adb -s "$SERIAL" shell pm remove-user "$uid" >/dev/null 2>&1 || warn "could not remove user $uid"
ok "profile isolation: user $uid got the grid, user 0 was untouched"

ok "L4 grid: all implemented steps passed"
