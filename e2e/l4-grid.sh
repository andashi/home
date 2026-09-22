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
#      holds the seeded favorites row in the bottom row (migration + seeder)
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
#   7. locked: push locked: true, a long press shows no edit bar, the file's
#      hash on the device is unchanged
#   8. malformed push: last good state kept, bounds unchanged
#   9. profile isolation: a second user gets its own grid through --user,
#      user 0's file and report are untouched
#
# FOLD=1 runs on the foldable GrapheneOS instance (SERIAL=emulator-5560,
# OVERLAY_DIR=.../instances/test-fold): the fixtures carry a `fold` layout
# eight columns wide with an item in the right half, step 2 folds and
# unfolds between its checks (D7: the cover shows columns 0-3, the right
# item is absent, the dock is clipped to four columns), and the phone-only
# steps 5 to 7 are skipped.
#
# Cells are found by their content description "grid-item:<id>", which the
# renderer sets on every cell. The favorites row anchors the geometry: it
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
RECEIVER="$PKG/de.mm20.launcher2.config.service.ReloadConfigReceiver"
ACTION="$PKG.action.RELOAD_CONFIG"
STATE_URI="content://$PKG.state"
INGEST_URI="content://$PKG.config-ingest/launcher.json"
LAUNCHER_ACTIVITY="$PKG/de.mm20.launcher2.ui.launcher.LauncherActivity"
DEVICE_CONFIG="/storage/emulated/0/Android/data/$PKG/files/config/launcher.json"
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

# --- adb helpers (as in l4-config.sh) ------------------------------------

query_json() { # $1 = provider path (config|diagnostics)
  local out
  out="$(adb -s "$SERIAL" shell content query --uri "$STATE_URI/$1" 2>&1 | tr -d '\r')" \
    || { printf 'content query failed: %s\n' "$out" >&2; return 1; }
  case "$out" in
    "Row: 0 json="*) printf '%s' "${out#Row: 0 json=}" ;;
    *) printf 'unexpected provider output: %s\n' "$out" >&2; return 1 ;;
  esac
}

LAST_REPORT=""
wait_report() { # $1 = jq filter, $2 = timeout (s), $3 = description
  local filter="$1" timeout="$2" what="$3" elapsed=0 report=""
  while [ "$elapsed" -lt "$timeout" ]; do
    if report="$(query_json diagnostics 2>/dev/null)" && [ -n "$report" ]; then
      if jq -e "$filter" >/dev/null 2>&1 <<<"$report"; then
        LAST_REPORT="$report"
        return 0
      fi
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  printf 'last /diagnostics report:\n%s\n' "$report" >&2
  die "timed out (${timeout}s) waiting for report: $what"
}

assert_jq() { # $1 = json, $2 = jq filter, $3 = description
  if ! jq -e "$2" >/dev/null 2>&1 <<<"$1"; then
    printf 'offending json:\n%s\n' "$1" >&2
    die "assertion failed: $3"
  fi
}

write_config() { # $1 = local file
  local out
  out="$(adb -s "$SERIAL" shell content write --uri "$INGEST_URI" < "$1" 2>&1 | tr -d '\r')" \
    || { printf '%s\n' "$out" >&2; die "content write failed"; }
  [ -z "$out" ] || { printf '%s\n' "$out" >&2; die "content write reported an error"; }
}

reload_broadcast() {
  local out
  out="$(adb -s "$SERIAL" shell am broadcast -n "$RECEIVER" -a "$ACTION" 2>&1 | tr -d '\r')" \
    || { printf '%s\n' "$out" >&2; die "am broadcast failed"; }
  case "$out" in
    *"Broadcast completed"*) ;;
    *) printf '%s\n' "$out" >&2; die "am broadcast did not complete" ;;
  esac
}

settle_then_broadcast() { # $1 = local config file, $2 = sha256, $3 = stage name
  write_config "$1"
  log "$3: waiting for file-watcher reload (hash ${2:0:12}...)"
  wait_report ".configSha256 == \"$2\" and .trigger == \"file-watcher\"" 30 "$3: file-watcher report"
  log "$3: broadcasting explicit reload"
  reload_broadcast
  wait_report ".configSha256 == \"$2\" and .trigger == \"broadcast\"" 30 "$3: broadcast report"
}

# --- screen helpers ----------------------------------------------------

# Brings the home screen to the front: the grid renders (and on first start
# seeds the widget column) only while the launcher is in the foreground. No
# sleep here: whatever follows polls for what it expects (wait_until,
# assert_cells), because a first cold start plus seeding plus the DataStore
# flag takes longer than any fixed pause on a fresh install.
show_home() {
  adb -s "$SERIAL" shell am start -n "$LAUNCHER_ACTIVITY" >/dev/null 2>&1 || true
}

# The foldable instance sleeps and locks between steps; a dump then shows
# only the keyguard. Harmless on the phone instance.
wake_screen() {
  adb -s "$SERIAL" shell svc power stayon true >/dev/null 2>&1 || true
  adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  adb -s "$SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1 || true
  adb -s "$SERIAL" shell cmd statusbar collapse >/dev/null 2>&1 || true
}

# Prints "left top right bottom" of the first node with the content
# description, or nothing.
desc_bounds() { # $1 = content-desc
  adb -s "$SERIAL" shell rm -f /sdcard/l4-grid.xml >/dev/null 2>&1 || true
  adb -s "$SERIAL" shell uiautomator dump /sdcard/l4-grid.xml >/dev/null 2>&1 || return 1
  adb -s "$SERIAL" shell cat /sdcard/l4-grid.xml | tr -d '\r' > "$WORK/dump.xml"
  python3 - "$WORK/dump.xml" "$1" <<'PY'
import re, sys
try:
    import defusedxml.ElementTree as ET
except ImportError:
    import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for node in root.iter("node"):
    if node.get("content-desc", "") == sys.argv[2]:
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if m:
            print(*m.groups()); break
PY
}

wait_desc() { # $1 = content-desc, $2 = timeout (s), $3 = description
  local elapsed=0
  while [ "$elapsed" -lt "$2" ]; do
    [ -n "$(desc_bounds "$1")" ] && return 0
    sleep 1; elapsed=$((elapsed + 1))
  done
  die "timed out (${2}s) waiting for '$1' on screen: $3"
}

tap_desc() { # $1 = content-desc
  local b
  b="$(desc_bounds "$1")"
  [ -n "$b" ] || die "'$1' is not on screen"
  set -- $b
  adb -s "$SERIAL" shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

# Centre of a grid cell on screen, "x y".
cell_center() { # $1 = id
  local line
  line="$(dump_cells | awk -v id="$1" '$1 == id')"
  [ -n "$line" ] || return 1
  set -- $line
  printf '%s %s\n' $(( ($2 + $4) / 2 )) $(( ($3 + $5) / 2 ))
}

# The centre of a free cell: [DOCK_W - 1] columns right of the dock's left
# edge, two rows above the dock, which every fixture leaves empty.
free_cell_point() {
  local dock gap scale
  dock="$(dump_cells | awk '$1 == "dock"')"
  [ -n "$dock" ] || return 1
  scale="$(density_scale)"
  gap="$(awk -v s="$scale" 'BEGIN { print 8 * s }')"
  python3 - "$dock" "$gap" "$DOCK_W" <<'PY'
import sys
_, l, t, r, b = sys.argv[1].split(); l, t, r, b = map(int, (l, t, r, b))
gap = float(sys.argv[2]); w = int(sys.argv[3])
pitch = (r - l + gap) / w
print(int(l + (w - 0.5) * pitch), int(t - 1.5 * pitch))
PY
}

# Long-presses a free cell (input swipe of zero length) and waits for the
# edit bar. The launcher's own long-press detector on the grid is what
# consumes it; the scaffold's gesture never fires.
enter_edit_mode() {
  local point
  point="$(free_cell_point)" || die "no dock on screen to locate a free cell from"
  set -- $point
  adb -s "$SERIAL" shell input swipe "$1" "$2" "$1" "$2" 900
  wait_desc grid-edit-done 15 "edit bar after the long press"
}

# Drags a cell by whole cells: a press that moves within a second is a
# drag for the cell's gesture detector, so no long press is needed here.
drag_cell() { # $1 = id, $2 = dx cells, $3 = dy cells
  local from pitch scale gap dock
  from="$(cell_center "$1")" || die "cell $1 not on screen"
  dock="$(dump_cells | awk '$1 == "dock"')"
  scale="$(density_scale)"
  gap="$(awk -v s="$scale" 'BEGIN { print 8 * s }')"
  pitch="$(python3 - "$dock" "$gap" "$DOCK_W" <<'PY'
import sys
_, l, t, r, b = sys.argv[1].split(); l, r = int(l), int(r)
print(int((r - l + float(sys.argv[2])) / int(sys.argv[3])))
PY
)"
  set -- $from $2 $3
  adb -s "$SERIAL" shell input swipe "$1" "$2" $(( $1 + $3 * pitch )) $(( $2 + $4 * pitch )) 700
}

device_config_sha() {
  adb -s "$SERIAL" shell sha256sum "$DEVICE_CONFIG" 2>/dev/null | tr -d '\r' | cut -d' ' -f1
}

posture() { # $1 = device state id (0 closed, 1 half, 2 opened)
  adb -s "$SERIAL" shell cmd device_state state "$1" >/dev/null 2>&1 || die "cmd device_state state $1 failed"
  sleep 4
  wake_screen
  show_home
}

# Polls the /config read-back until the jq filter holds. Sets LAST_CONFIG.
LAST_CONFIG=""
wait_until() { # $1 = jq filter over /config, $2 = timeout (s), $3 = description
  local filter="$1" timeout="$2" what="$3" elapsed=0 config=""
  while [ "$elapsed" -lt "$timeout" ]; do
    if config="$(query_json config 2>/dev/null)" && [ -n "$config" ]; then
      if jq -e "$filter" >/dev/null 2>&1 <<<"$config"; then
        LAST_CONFIG="$config"
        return 0
      fi
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  printf 'last /config read-back:\n%s\n' "$config" >&2
  die "timed out (${timeout}s) waiting for read-back: $what"
}

# Prints "id left top right bottom" for every grid cell on screen, in px.
dump_cells() {
  adb -s "$SERIAL" shell rm -f /sdcard/l4-grid.xml >/dev/null 2>&1 || true
  adb -s "$SERIAL" shell uiautomator dump /sdcard/l4-grid.xml >/dev/null 2>&1 || { printf "uiautomator dump failed\n" >&2; return 1; }
  adb -s "$SERIAL" shell cat /sdcard/l4-grid.xml | tr -d '\r' > "$WORK/dump.xml"
  python3 - "$WORK/dump.xml" <<'PY'
import re, sys
try:
    import defusedxml.ElementTree as ET  # the dump comes from the device: parse it defensively
except ImportError:
    import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for node in root.iter("node"):
    desc = node.get("content-desc", "")
    if not desc.startswith("grid-item:"):
        continue
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if m:
        print(desc[len("grid-item:"):], *m.groups())
PY
}

# The device's density scale (dp -> px), from `wm density`.
density_scale() {
  adb -s "$SERIAL" shell wm density | tr -d '\r' | awk '/density/ { print $NF / 160; exit }'
}

# Checks every cell of the dumped screen against the expected geometry:
#   $1 = expected "id x y w h" lines
# The favorites row ("dock") anchors the grid: x = 0, bottom row, so its left
# edge is the grid's left edge and its width gives the cell pitch.
#
# Polls: a reload is applied before its report is written, but the screen
# recomposes asynchronously, so the dump is repeated until the expected
# cells are there (up to CELLS_TIMEOUT seconds) and the last mismatch is
# what a timeout reports.
CELLS_TIMEOUT="${CELLS_TIMEOUT:-60}"
LAST_EXPECTED_CELLS=""
assert_cells() { # $1 = expected lines, $2 = description
  local expected="$1" what="$2" elapsed=0 result=""
  LAST_EXPECTED_CELLS="$expected"
  while [ "$elapsed" -lt "$CELLS_TIMEOUT" ]; do
    if result="$(check_cells "$expected" "$what" 2>&1)"; then
      printf '%s\n' "$result"
      return 0
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  printf '%s\n' "$result" >&2
  die "timed out (${CELLS_TIMEOUT}s) waiting for cells: $what"
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
left, dock_top = dock[0], dock[1]
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
        // Eight columns wide (D7); "right" lives in the half only the inner display shows.
        "fold": { "items": [
          { "id": "digital", "widget": "$DIGITAL_CLOCK", "x": 0, "y": 0, "w": 3, "h": 1 },
          { "id": "analog", "widget": "$ANALOG_CLOCK", "x": 0, "y": 1, "w": 2, "h": 2 },
          { "id": "right", "widget": "$DIGITAL_CLOCK", "x": 5, "y": 0, "w": 3, "h": 1 },
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

LOCKED_CONFIG="$WORK/locked.jsonc"
sed 's|"locked": false|"locked": true|' "$GRID_CONFIG" > "$LOCKED_CONFIG"

MALFORMED_CONFIG="$WORK/malformed.jsonc"
printf '{ "schemaVersion": 2, "home": { not json at all\n' > "$MALFORMED_CONFIG"

H_LEGACY="$(sha256sum "$LEGACY_CONFIG" | cut -d' ' -f1)"
H_GRID="$(sha256sum "$GRID_CONFIG" | cut -d' ' -f1)"
H_PUSHDOWN="$(sha256sum "$PUSHDOWN_CONFIG" | cut -d' ' -f1)"
H_TOO_SMALL="$(sha256sum "$TOO_SMALL_CONFIG" | cut -d' ' -f1)"
H_MALFORMED="$(sha256sum "$MALFORMED_CONFIG" | cut -d' ' -f1)"
H_LOCKED="$(sha256sum "$LOCKED_CONFIG" | cut -d' ' -f1)"

# --- boot + install ----------------------------------------------------

(cd "$GOS_REPO" && emulator/device-lock.sh acquire "$LOCK_OWNER" "$SERIAL")
HAVE_LOCK=1

log "booting $SERIAL from snapshot '$SNAPSHOT' (overlays: $OVERLAY_DIR)"
(cd "$GOS_REPO" && SNAPSHOT="$SNAPSHOT" emulator/run.sh start)
adb -s "$SERIAL" unroot >/dev/null 2>&1 || true
adb -s "$SERIAL" wait-for-device
[ "$(adb -s "$SERIAL" shell id -u | tr -d '\r')" = "2000" ] || die "adb is not running as shell after unroot"

log "installing $(basename "$APK")"
install_out="$(adb -s "$SERIAL" install -r "$APK" 2>&1)" || { printf '%s\n' "$install_out" >&2; die "adb install failed"; }
case "$install_out" in *Success*) ;; *) printf '%s\n' "$install_out" >&2; die "adb install failed" ;; esac
ok "package installed: $PKG"

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

# --- 1. schemaVersion 1 file + seeding ---------------------------------

write_config "$LEGACY_CONFIG"
wait_report ".success == true and .configSha256 == \"$H_LEGACY\"" 90 "first reload of the v1 file"
show_home
# The seeder runs on the launcher's first render; on a fresh install that is
# a cold start plus the DataStore flag, so poll the read-back for its result.
wait_until '(.home.grid.layouts.'"$LAYOUT"'.items | length) > 0' 60 "the seeded $LAYOUT layout"
effective="$LAST_CONFIG"
assert_jq "$effective" '.schemaVersion == 2 and .home.favorites == [] and (.home | has("dock") | not)' \
  "v1 file migrated to the v2 shape"
assert_jq "$effective" \
  '[.home.grid.layouts.'"$LAYOUT"'.items[] | select(.widget == "favorites" and .x == 0 and .w == '"$DOCK_W"' and .h == 1 and .y >= 4)] | length == 1' \
  "the seeded favorites row sits full width in the bottom row"
ok "v1 file migrated, favorites row seeded"
wake_screen
assert_cells "dock 0 $(jq -r '.home.grid.layouts.'"$LAYOUT"'.items[] | select(.widget == "favorites") | .y' <<<"$effective") $DOCK_W 1" \
  "seeded grid on screen"
ok "seeded favorites row measured on screen"

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
       [{"id":"digital","x":0,"y":0,"w":3,"h":1},{"id":"analog","x":0,"y":1,"w":2,"h":2},
        {"id":"right","x":5,"y":0,"w":3,"h":1},{"id":"dock","x":0,"y":5,"w":8,"h":1}]' \
      "read-back fold grid equals the pushed file"
    posture 2
    assert_cells $'digital 0 0 3 1\nanalog 0 1 2 2\nright 5 0 3 1\ndock 0 5 8 1' "configured grid on the inner display"
    assert_bound "$DIGITAL_CLOCK" "$ANALOG_CLOCK"
    ok "fold, opened: eight columns, the right-half item on screen, both widgets bound"
    # Closed: the cover renders columns 0..3 of the same layout (D7). The
    # dock line says 4 wide, so the pitch is measured from four columns.
    posture 0
    DOCK_W=4
    assert_cells $'digital 0 0 3 1\nanalog 0 1 2 2\ndock 0 5 4 1' "the cover clips the fold layout"
    [ -z "$(dump_cells | awk '$1 == "right"')" ] || die "the right-half item is on the cover"
    ok "fold, closed: four columns, the right-half item absent, the dock clipped"
    posture 1
    DOCK_W=8
    assert_cells $'digital 0 0 3 1\nanalog 0 1 2 2\nright 5 0 3 1\ndock 0 5 8 1' "half-opened renders as opened"
    posture 2
    assert_cells $'digital 0 0 3 1\nanalog 0 1 2 2\nright 5 0 3 1\ndock 0 5 8 1' "opened again"
    ok "fold, half-opened and opened again: the inner layout is back"
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
  enter_edit_mode
  ok "edit mode entered by long press"
  drag_cell digital 0 3
  sleep 1
  tap_desc grid-edit-done
  wait_report '.trigger == "self-write" and .success == true' 30 "the launcher's own write-back report"
  H_WRITTEN="$(jq -r '.configSha256' <<<"$LAST_REPORT")"
  [ "$(device_config_sha)" = "$H_WRITTEN" ] || die "the file on the device does not carry the self-write hash"
  PULLED="$WORK/pulled.jsonc"
  adb -s "$SERIAL" pull "$DEVICE_CONFIG" "$PULLED" >/dev/null 2>&1 || die "adb pull of $DEVICE_CONFIG failed"
  [ "$(jq -r '.home.grid.layouts.'"$LAYOUT"'.items[] | select(.id == "digital") | .y' <<<"$(sed 's|//.*$||' "$PULLED")")" = "3" ] \
    || { cat "$PULLED" >&2; die "the pulled file does not hold the digital clock at y 3"; }
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
    assert_cells $'digital 0 3 3 1\nanalog 0 1 2 2\nright 5 0 3 1\ndock 0 5 8 1' "the moved clock on screen"
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

  # --- 7. a locked layout refuses edit mode ------------------------------
  settle_then_broadcast "$LOCKED_CONFIG" "$H_LOCKED" "locked"
  show_home
  wake_screen
  assert_cells $'digital 0 0 3 1\nanalog 0 1 2 2\ndock 0 5 4 1' "locked grid on screen"
  point="$(free_cell_point)" || die "no dock on screen"
  set -- $point
  adb -s "$SERIAL" shell input swipe "$1" "$2" "$1" "$2" 900
  sleep 3
  [ -z "$(desc_bounds grid-edit-done)" ] || die "a locked layout entered edit mode"
  [ "$(device_config_sha)" = "$H_LOCKED" ] || die "the locked file changed on the device"
  ok "locked: the long press shows no edit bar, the file is unchanged"
  fi

else
  warn "steps 2, 5 and 6 need $CLOCK_PKG; skipped"
fi

# --- 8. malformed push keeps the last good state -----------------------
# Against whatever the last good state is: the configured grid when the
# clock is installed, the seeded one otherwise.

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
elapsed=0
until out="$(adb -s "$SERIAL" shell content write --user "$uid" --uri "$INGEST_URI" < "$GRID_CONFIG" 2>&1 | tr -d '\r')" && [ -z "$out" ]; do
  sleep 2; elapsed=$((elapsed + 2))
  [ "$elapsed" -lt 60 ] || { printf '%s\n' "$out" >&2; die "content write --user $uid kept failing"; }
done
adb -s "$SERIAL" shell am broadcast -n "$RECEIVER" -a "$ACTION" --user "$uid" >/dev/null 2>&1 || die "broadcast --user $uid failed"
elapsed=0
until report="$(adb -s "$SERIAL" shell content query --uri "$STATE_URI/diagnostics" --user "$uid" 2>/dev/null | tr -d '\r')" \
    && jq -e ".configSha256 == \"$H_GRID\" and .success == true" >/dev/null 2>&1 <<<"${report#Row: 0 json=}"; do
  sleep 1; elapsed=$((elapsed + 1))
  [ "$elapsed" -lt 60 ] || { printf '%s\n' "$report" >&2; die "user $uid never reported the grid config"; }
done
user_config="$(adb -s "$SERIAL" shell content query --uri "$STATE_URI/config" --user "$uid" 2>/dev/null | tr -d '\r')"
assert_jq "${user_config#Row: 0 json=}" '(.home.grid.layouts.'"$LAYOUT"'.items | map(.id)) | index("dock") != null' \
  "user $uid holds the pushed grid"
[ "$(device_config_sha)" = "$H_USER0" ] || die "user 0's file changed when user $uid was provisioned"
[ "$(query_json diagnostics)" = "$REPORT_USER0" ] || die "user 0's last report changed when user $uid was provisioned"
adb -s "$SERIAL" shell pm remove-user "$uid" >/dev/null 2>&1 || warn "could not remove user $uid"
ok "profile isolation: user $uid got the grid, user 0 was untouched"

ok "L4 grid: all implemented steps passed"
