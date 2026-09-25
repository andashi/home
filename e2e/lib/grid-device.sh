#!/usr/bin/env bash
# Device helpers shared by the e2e scripts. Everything talks to the launcher
# through its public surface: the ingest provider, the reload broadcast, the
# read-back provider, `input` and `uiautomator dump`.
#
# One definition per helper, here (#126): a script that needs different
# behaviour gets a parameter, or a function under its own name.
# e2e/check-helpers.sh fails CI when a script redefines one of these.
#
# Expects: SERIAL, PKG, WORK (a scratch dir), and the log/die functions.

RECEIVER="$PKG/de.mm20.launcher2.config.service.ReloadConfigReceiver"
ACTION="$PKG.action.RELOAD_CONFIG"
STATE_URI="content://$PKG.state"
INGEST_URI="content://$PKG.config-ingest/launcher.json"
LAUNCHER_ACTIVITY="$PKG/de.mm20.launcher2.ui.launcher.LauncherActivity"
DEVICE_CONFIG="/storage/emulated/0/Android/data/$PKG/files/config/launcher.json"

# One adb call, cut off at ADB_DEADLINE (a $SECONDS value) when a caller has
# set one, 60 s from now otherwise. A wedged connection hangs adb for good;
# every helper a wait loop calls goes through this, so the loop's timeout is
# wall-clock time and not a count of attempts (#126, #147).
adb_t() {
  local left=$(( ${ADB_DEADLINE:-$((SECONDS + 60))} - SECONDS ))
  [ "$left" -gt 0 ] || return 124
  timeout "$left" adb -s "$SERIAL" "$@"
}

# Runs "$@" until it succeeds or $1 seconds have passed, and fails then. The
# adb calls of every attempt share that deadline, so one slow call cannot
# carry the loop past it: a uiautomator dump alone took 3.84 s on the
# emulator (#127).
retry_for() { # $1 = timeout (s), $2... = command
  local ADB_DEADLINE=$((SECONDS + $1))
  shift
  while [ "$ADB_DEADLINE" -gt "$SECONDS" ]; do
    "$@" && return 0
    [ $((ADB_DEADLINE - SECONDS)) -gt 1 ] || break
    sleep 1
  done
  return 1
}

# Succeeds when "$@" prints anything.
shows() { [ -n "$("$@" 2>/dev/null)" ]; }

# adb as the unrooted shell (uid 2000), which is what a release build
# offers. After `run.sh start` loads a snapshot, adbd answers a moment
# later than the emulator reports up, and `adb unroot` restarts it again;
# asserting once failed right there (emulator-5560, 2026-09-25 22:11). So
# it is polled against a real deadline, reconnecting an offline transport
# in between.
is_unrooted_shell() {
  [ "$(adb_t shell id -u 2>/dev/null | tr -d '\r')" = 2000 ] && return 0
  adb reconnect offline >/dev/null 2>&1 || true
  return 1
}
unrooted_shell() { # [$1 = timeout (s), default 60]
  adb_t unroot >/dev/null 2>&1 || true
  retry_for "${1:-60}" is_unrooted_shell \
    || die "adb is not the unrooted shell (uid 2000) after ${1:-60}s"
  log "adb as unrooted shell (uid 2000)"
}

query_json() { # $1 = provider path (config|diagnostics)
  # The provider answers one row whose json value spans many lines. Anything
  # else is an error, never an empty answer: a caller reading "" as "no
  # config" would pass for the wrong reason.
  local out
  out="$(adb_t shell content query --uri "$STATE_URI/$1" 2>&1 | tr -d '\r')" \
    || { printf 'content query failed: %s\n' "$out" >&2; return 1; }
  case "$out" in
    "Row: 0 json="*) printf '%s' "${out#Row: 0 json=}" ;;
    *) printf 'unexpected provider output: %s\n' "$out" >&2; return 1 ;;
  esac
}

# Waits for a /diagnostics report matching the jq filter; the match is left in
# LAST_REPORT for the caller to assert on.
LAST_REPORT=""
# Waits for a /diagnostics report matching the jq filter; the match is left in
# LAST_REPORT for the caller to assert on.
LAST_REPORT=""
LAST_SEEN_REPORT=""
report_matches() { # $1 = jq filter
  LAST_SEEN_REPORT="$(query_json diagnostics 2>/dev/null)" && [ -n "$LAST_SEEN_REPORT" ] \
    && jq -e "$1" <<<"$LAST_SEEN_REPORT" >/dev/null 2>&1 && LAST_REPORT="$LAST_SEEN_REPORT"
}
wait_report() { # $1 = jq filter, $2 = timeout (s), $3 = description
  LAST_SEEN_REPORT=""
  retry_for "$2" report_matches "$1" && return 0
  printf 'last /diagnostics report:\n%s\n' "$LAST_SEEN_REPORT" >&2
  die "timed out (${2}s) waiting for report: $3"
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

# Push a file and wait until the launcher reports it applied (by hash).
push_config() { # $1 = local file, $2 = stage name
  local h
  h="$(sha256sum "$1" | cut -d' ' -f1)"
  write_config "$1"
  wait_report ".configSha256 == \"$h\"" 60 "$2: reload of the pushed file"
  reload_broadcast
  wait_report ".configSha256 == \"$h\" and .trigger == \"broadcast\"" 30 "$2: broadcast report"
}

wake_screen() {
  adb_t shell svc power stayon true >/dev/null 2>&1 || true
  adb_t shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  adb_t shell wm dismiss-keyguard >/dev/null 2>&1 || true
  adb_t shell cmd statusbar collapse >/dev/null 2>&1 || true
}

show_home() {
  wake_screen
  adb_t shell am start -n "$LAUNCHER_ACTIVITY" >/dev/null 2>&1 || true
}

# Prints "left top right bottom" of the first node matching the attribute.
# Dumps the screen into $WORK/dump.xml. The dump and its read-back are both
# adb calls under the caller's deadline. A dump taken while the device is
# still busy can come back empty; that is "not on screen yet", for the
# caller to retry, not a parse error.
dump_screen() {
  adb_t shell rm -f /sdcard/grid-dump.xml >/dev/null 2>&1 || true
  adb_t shell uiautomator dump /sdcard/grid-dump.xml >/dev/null 2>&1 || return 1
  adb_t shell cat /sdcard/grid-dump.xml > "$WORK/dump.raw" 2>/dev/null || return 1
  tr -d '\r' < "$WORK/dump.raw" > "$WORK/dump.xml"
  [ -s "$WORK/dump.xml" ]
}

node_bounds() { # $1 = attribute (resource-id|content-desc|text), $2 = value
  dump_screen || return 1
  python3 - "$WORK/dump.xml" "$1" "$2" <<'PY'
import re, sys
try:
    import defusedxml.ElementTree as ET
except ImportError:
    import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for node in root.iter("node"):
    if node.get(sys.argv[2], "") == sys.argv[3]:
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if m:
            print(*m.groups()); break
PY
}

# Content descriptions are what TalkBack reads: use them only for real
# labels ("Search"). The grid's automation hooks are test tags, which its
# root exposes as resource ids (#117).
desc_bounds() { node_bounds content-desc "$1"; }
id_bounds() { node_bounds resource-id "$1"; }

wait_desc() { # $1 = content-desc, $2 = timeout (s), $3 = description
  retry_for "$2" shows desc_bounds "$1" || die "timed out (${2}s) waiting for '$1' on screen: $3"
}

wait_id() { # $1 = resource-id (test tag), $2 = timeout (s), $3 = description
  retry_for "$2" shows id_bounds "$1" || die "timed out (${2}s) waiting for '$1' on screen: $3"
}

tap_bounds() { # $1 = "l t r b"
  set -- $1
  adb -s "$SERIAL" shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

tap_desc() { # $1 = content-desc
  local b
  b="$(desc_bounds "$1")" || die "uiautomator dump failed while looking for '$1'"
  [ -n "$b" ] || die "'$1' is not on screen"
  tap_bounds "$b"
}

tap_id() { # $1 = resource-id (test tag)
  local b
  b="$(id_bounds "$1")" || die "uiautomator dump failed while looking for '$1'"
  [ -n "$b" ] || die "'$1' is not on screen"
  tap_bounds "$b"
}

# "top bottom" in px: the lower edge of the status bar and the upper edge of
# the navigation bar, from the frames `dumpsys window` reports; 0 and a
# bound past any screen for a bar that is hidden or absent. With several
# displays (the Fold) the union, which refuses more rather than less.
system_bars() {
  adb -s "$SERIAL" shell dumpsys window | tr -d '\r' | awk '
    / InsetsSource id=/ && /visible=true/ && match($0, /frame=\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]/) {
      split(substr($0, RSTART + 6, RLENGTH - 6), f, /[^0-9]+/)
      if (/ type=statusBars / && f[5] > top) top = f[5]
      if (/ type=navigationBars / && /sideHint=BOTTOM/ && (bottom == "" || f[3] < bottom)) bottom = f[3]
    }
    END { print top + 0, (bottom == "" ? 1000000 : bottom) }'
}

# tap_text taps a node only where a tap reaches it (#155). A settings row
# scrolled under the top app bar is still in the dump, and a tap at its
# centre lands on the bar; l4-write-back failed on exactly that. What covers
# the centre is measured, never assumed as a height:
# - what the app draws over its own scrolling content: a node that comes
#   after the target's scrollable ancestor in the dump and lies outside it,
#   such as a top app bar of any height ([0,0][1080,264] on "Grid and icons"
#   at 480 dpi) or a floating button;
# - the status and navigation bars, from system_bars.
# A node that does not scroll is where it is drawn: a chip right under a top
# search bar, or a label its own button is drawn over, is tapped.
# Returning 1 then is the same contract as "absent": a caller that scrolls
# and retries keeps working, and one that took 1 for "absent" now fails
# loudly instead of tapping the wrong thing.
tap_text() { # $1 = visible text; returns 1 (no exit) when absent or not where a tap reaches it
  local point
  [ -n "$(node_bounds text "$1")" ] || return 1
  point="$(python3 - "$WORK/dump.xml" "$1" $(system_bars) <<'PY'
import re, sys
try:
    import defusedxml.ElementTree as ET
except ImportError:
    import xml.etree.ElementTree as ET
dump, text, top, bottom = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
root = ET.parse(dump).getroot()
def rect(node):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    return tuple(map(int, m.groups())) if m else None
order = list(root.iter("node"))
target = next(n for n in order if n.get("text", "") == text and rect(n))
l, t, r, b = rect(target)
x, y = (l + r) // 2, (t + b) // 2
if not top <= y < bottom:
    sys.exit()
parent = {c: p for p in root.iter() for c in p}
scroller = target
while scroller is not None and scroller.get("scrollable") != "true":
    scroller = parent.get(scroller)
if scroller is not None:
    inside = set(scroller.iter())
    for node in order[order.index(scroller) + 1:]:
        c = None if node in inside else rect(node)
        if c and c[0] <= x < c[2] and c[1] <= y < c[3]:
            sys.exit()
print(x, y)
PY
)"
  [ -n "$point" ] || return 1
  adb -s "$SERIAL" shell input tap $point
}

# "id left top right bottom" for every grid cell on screen.
dump_cells() {
  dump_screen || { printf "uiautomator dump failed\n" >&2; return 1; }
  python3 - "$WORK/dump.xml" <<'PY'
import re, sys
try:
    import defusedxml.ElementTree as ET
except ImportError:
    import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for node in root.iter("node"):
    tag = node.get("resource-id", "")
    if not tag.startswith("grid-item:"):
        continue
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if m:
        print(tag[len("grid-item:"):], *m.groups())
PY
}

has_cells() { [ "$(dump_cells 2>/dev/null | wc -l)" -ge "$1" ]; }
wait_cells() { # $1 = expected count, $2 = timeout (s)
  retry_for "$2" has_cells "$1" || die "timed out (${2}s) waiting for $1 cells on screen"
}

density_scale() {
  adb -s "$SERIAL" shell wm density | tr -d '\r' | awk '/density/ { print $NF / 160; exit }'
}

cell_center() { # $1 = id
  local line
  line="$(dump_cells | awk -v id="$1" '$1 == id')"
  [ -n "$line" ] || return 1
  set -- $line
  printf '%s %s\n' $(( ($2 + $4) / 2 )) $(( ($3 + $5) / 2 ))
}

wait_cell() { # $1 = id, $2 = timeout (s), $3 = message
  retry_for "$2" shows cell_center "$1" || die "timed out (${2}s) waiting for cell '$1': $3"
}

# Cell pitch in px from the dock's width and its span in cells.
cell_pitch() { # $1 = dock width in cells
  local dock gap
  dock="$(dump_cells | awk '$1 == "dock"')"
  [ -n "$dock" ] || return 1
  gap="$(awk -v s="$(density_scale)" 'BEGIN { print 8 * s }')"
  python3 - "$dock" "$gap" "$1" <<'PY'
import sys
_, l, t, r, b = sys.argv[1].split(); l, r = int(l), int(r)
print(int((r - l + float(sys.argv[2])) / int(sys.argv[3])))
PY
}

# A point in a free cell: DOCK_W - 1 columns right of the dock's left edge,
# two rows above it (the fixtures leave it empty).
free_cell_point() { # $1 = dock width in cells
  local dock gap
  dock="$(dump_cells | awk '$1 == "dock"')"
  [ -n "$dock" ] || return 1
  gap="$(awk -v s="$(density_scale)" 'BEGIN { print 8 * s }')"
  python3 - "$dock" "$gap" "$1" <<'PY'
import sys
_, l, t, r, b = sys.argv[1].split(); l, t, r, b = map(int, (l, t, r, b))
gap = float(sys.argv[2]); w = int(sys.argv[3])
pitch = (r - l + gap) / w
print(int(l + (w - 0.5) * pitch), int(t - 1.5 * pitch))
PY
}

long_press() { # $1 = "x y"
  set -- $1
  adb -s "$SERIAL" shell input swipe "$1" "$2" "$1" "$2" 900
}

enter_edit_mode() { # $1 = dock width in cells
  local point
  point="$(free_cell_point "$1")" || die "no dock on screen to locate a free cell from"
  long_press "$point"
  wait_id grid-edit-done 15 "edit bar after the long press"
}

# Drag with explicit motion events (input swipe lands short of the target).
# With $4 = hold, the pointer stays down after the moves so the drag can be
# photographed; call drag_release to finish it.
DRAG_END=""
drag_cell() { # $1 = id, $2 = dx cells, $3 = dy cells, $4 = dock width, [$5 = hold]
  local from pitch x y tx ty i
  from="$(cell_center "$1")" || die "cell $1 not on screen"
  pitch="$(cell_pitch "$4")"
  x="${from%% *}"; y="${from##* }"
  tx=$(( x + $2 * pitch )); ty=$(( y + $3 * pitch ))
  adb -s "$SERIAL" shell input motionevent DOWN "$x" "$y"
  sleep 1
  for i in 1 2 3 4 5 6 7 8; do
    adb -s "$SERIAL" shell input motionevent MOVE $(( x + ($2 * pitch * i) / 8 )) $(( y + ($3 * pitch * i) / 8 ))
  done
  DRAG_END="$tx $ty"
  [ "${5:-}" = hold ] || drag_release
}

drag_release() {
  set -- $DRAG_END
  adb -s "$SERIAL" shell input motionevent UP "$1" "$2"
  DRAG_END=""
}

# Posture ids by name (the GrapheneOS fold instance counts from 0, the SDK
# foldable from 1).
resolve_postures() {
  local states
  states="$(adb -s "$SERIAL" shell cmd device_state print-states 2>/dev/null | tr -d '\r')"
  POSTURE_CLOSED="$(sed -n "s/.*identifier=\([0-9]*\), name='CLOSED'.*/\1/p" <<<"$states" | sed -n 1p)"
  POSTURE_HALF="$(sed -n "s/.*identifier=\([0-9]*\), name='HALF_OPENED'.*/\1/p" <<<"$states" | sed -n 1p)"
  POSTURE_OPENED="$(sed -n "s/.*identifier=\([0-9]*\), name='OPENED'.*/\1/p" <<<"$states" | sed -n 1p)"
  [ -n "$POSTURE_CLOSED" ] && [ -n "$POSTURE_HALF" ] && [ -n "$POSTURE_OPENED" ] \
    || die "$SERIAL has no CLOSED/HALF_OPENED/OPENED postures (not a foldable?): $states"
  log "postures: closed=$POSTURE_CLOSED half=$POSTURE_HALF opened=$POSTURE_OPENED"
}

# Waits until the node with resource id $1 is on the home screen, waking and
# re-showing home in between: after a posture change the activity comes back
# on the other display, and a check made before that sees the blank in
# between (it did: 2 frames on the cover).
#
# 30 s by default, derived from a clean measurement on emulator-5560
# (2026-09-25, 8 posture changes): the dock was back on the first dump after
# unfolding (2-3 s), and on the first or second after folding (4-5 s or
# 11 s; the launcher had focus at each miss). One round costs at most about
# 8 s (dump, wake, home, pause), so 30 s allows three rounds, about 2.7
# times the worst case seen. A timeout names the rounds and what had focus.
HOME_ROUNDS=0
home_shows() {
  HOME_ROUNDS=$((HOME_ROUNDS + 1))
  shows id_bounds "$1" && return 0
  wake_screen; show_home; return 1
}
wait_on_home() { # $1 = resource id, $2 = timeout (s)
  local focus shot="${MISS_DIR:-${TMPDIR:-/tmp}}/wait_on_home-$SERIAL-$(date +%s).png"
  HOME_ROUNDS=0
  retry_for "${2:-30}" home_shows "$1" && return 0
  # The diagnosis shares 2 s of its own: on a wedged connection it must not
  # carry the timeout further than the wait did. The picture goes through
  # `screenshot`, which picks the display on a foldable, and outside $WORK,
  # which is gone at exit. A failed picture never masks the timeout.
  local ADB_DEADLINE=$((SECONDS + 2))
  focus="$(adb_t shell dumpsys window 2>/dev/null | tr -d '\r' | awk '/mCurrentFocus/ { print $NF; exit }')"
  ( screenshot "$shot" ) >/dev/null 2>&1 || shot="none"
  die "'$1' did not come back on the home screen within ${2:-30}s ($HOME_ROUNDS rounds; focus: ${focus:-unknown}; screen: $shot)"
}

posture() { # $1 = closed | half | opened, [$2 = resource id to wait for on home]
  local id
  case "$1" in
    closed) id="$POSTURE_CLOSED" ;;
    half) id="$POSTURE_HALF" ;;
    opened) id="$POSTURE_OPENED" ;;
    *) die "unknown posture $1" ;;
  esac
  adb -s "$SERIAL" shell cmd device_state state "$id" >/dev/null 2>&1 || die "cmd device_state state $id ($1) failed"
  sleep 4
  show_home
  [ -z "${2:-}" ] || wait_on_home "$2"
  sleep 3
}

# The physical id of the display that is ON (a foldable exposes two).
active_display() {
  # One DisplayDeviceInfo line per panel; nested braces inside, so match the
  # whole line rather than a brace-delimited run.
  adb_t shell dumpsys display | tr -d '\r' \
    | grep 'DisplayDeviceInfo{' | grep 'state ON' \
    | sed -n 's/.*uniqueId="local:\([0-9]*\)".*/\1/p' | sed -n 1p
}

# Screenshot to $1 (png). Multi-display devices need the physical id.
screenshot() { # $1 = output file
  local id
  id="$(active_display 2>/dev/null || true)"
  if [ -n "$id" ] && adb_t exec-out screencap -d "$id" -p > "$1" 2>/dev/null \
    && file "$1" | grep -q 'PNG image'; then
    return 0
  fi
  adb_t exec-out screencap -p > "$1" 2>/dev/null
  file "$1" | grep -q 'PNG image' || die "screencap did not produce a PNG for $1"
}

assert_jq() { # $1 = json, $2 = jq filter, $3 = description
  if ! jq -e "$2" >/dev/null 2>&1 <<<"$1"; then
    printf 'offending json:\n%s\n' "$1" >&2
    die "assertion failed: $3"
  fi
}

pull_config() { # $1 = local file
  adb -s "$SERIAL" pull "$DEVICE_CONFIG" "$1" >/dev/null 2>&1 || die "adb pull of $DEVICE_CONFIG failed"
}

wait_text() { # $1 = visible text, $2 = timeout (s)
  local elapsed=0
  until [ -n "$(node_bounds text "$1")" ]; do
    [ "$elapsed" -lt "$2" ] || die "timed out (${2}s) waiting for '$1' on screen"
    sleep 1; elapsed=$((elapsed + 1))
  done
}
