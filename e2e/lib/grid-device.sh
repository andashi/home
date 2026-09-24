#!/usr/bin/env bash
# Device helpers shared by the grid scripts (e2e/l4-grid.sh carries its own
# copy today; e2e/screenshots.sh sources this file). Everything talks to the
# launcher through its public surface: the ingest provider, the reload
# broadcast, the read-back provider, `input` and `uiautomator dump`.
#
# Expects: SERIAL, PKG, WORK (a scratch dir), and the log/die functions.

RECEIVER="$PKG/de.mm20.launcher2.config.service.ReloadConfigReceiver"
ACTION="$PKG.action.RELOAD_CONFIG"
STATE_URI="content://$PKG.state"
INGEST_URI="content://$PKG.config-ingest/launcher.json"
LAUNCHER_ACTIVITY="$PKG/de.mm20.launcher2.ui.launcher.LauncherActivity"
DEVICE_CONFIG="/storage/emulated/0/Android/data/$PKG/files/config/launcher.json"

query_json() { # $1 = provider path (config|diagnostics)
  # The provider answers one row whose json value spans many lines.
  adb -s "$SERIAL" shell content query --uri "$STATE_URI/$1" 2>/dev/null | tr -d '\r' \
    | sed '1s/^Row: 0 json=//'
}

wait_report() { # $1 = jq filter, $2 = timeout (s), $3 = description
  local elapsed=0 report
  while [ "$elapsed" -lt "$2" ]; do
    if report="$(query_json diagnostics 2>/dev/null)" && [ -n "$report" ] \
      && jq -e "$1" <<<"$report" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1; elapsed=$((elapsed + 1))
  done
  die "timed out (${2}s) waiting for a report: $3"
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
  adb -s "$SERIAL" shell svc power stayon true >/dev/null 2>&1 || true
  adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  adb -s "$SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1 || true
  adb -s "$SERIAL" shell cmd statusbar collapse >/dev/null 2>&1 || true
}

show_home() {
  wake_screen
  adb -s "$SERIAL" shell am start -n "$LAUNCHER_ACTIVITY" >/dev/null 2>&1 || true
}

# Prints "left top right bottom" of the first node matching the attribute.
node_bounds() { # $1 = attribute (resource-id|content-desc|text), $2 = value
  adb -s "$SERIAL" shell rm -f /sdcard/grid-dump.xml >/dev/null 2>&1 || true
  adb -s "$SERIAL" shell uiautomator dump /sdcard/grid-dump.xml >/dev/null 2>&1 || return 1
  adb -s "$SERIAL" shell cat /sdcard/grid-dump.xml | tr -d '\r' > "$WORK/dump.xml"
  # A dump taken while the device is still busy can come back empty; that is
  # "not on screen yet", for the caller to retry, not a parse error.
  [ -s "$WORK/dump.xml" ] || return 1
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
  local elapsed=0
  while [ "$elapsed" -lt "$2" ]; do
    [ -n "$(desc_bounds "$1")" ] && return 0
    sleep 1; elapsed=$((elapsed + 1))
  done
  die "timed out (${2}s) waiting for '$1' on screen: $3"
}

wait_id() { # $1 = resource-id (test tag), $2 = timeout (s), $3 = description
  local elapsed=0
  while [ "$elapsed" -lt "$2" ]; do
    [ -n "$(id_bounds "$1")" ] && return 0
    sleep 1; elapsed=$((elapsed + 1))
  done
  die "timed out (${2}s) waiting for '$1' on screen: $3"
}

tap_bounds() { # $1 = "l t r b"
  set -- $1
  adb -s "$SERIAL" shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

tap_desc() { # $1 = content-desc
  local b
  b="$(desc_bounds "$1")"
  [ -n "$b" ] || die "'$1' is not on screen"
  tap_bounds "$b"
}

tap_id() { # $1 = resource-id (test tag)
  local b
  b="$(id_bounds "$1")"
  [ -n "$b" ] || die "'$1' is not on screen"
  tap_bounds "$b"
}

tap_text() { # $1 = visible text; returns 1 (no exit) when absent, so callers can fall back
  local b
  b="$(node_bounds text "$1")"
  [ -n "$b" ] || return 1
  tap_bounds "$b"
}

# "id left top right bottom" for every grid cell on screen.
dump_cells() {
  adb -s "$SERIAL" shell rm -f /sdcard/grid-dump.xml >/dev/null 2>&1 || true
  adb -s "$SERIAL" shell uiautomator dump /sdcard/grid-dump.xml >/dev/null 2>&1 || { printf "uiautomator dump failed\n" >&2; return 1; }
  adb -s "$SERIAL" shell cat /sdcard/grid-dump.xml | tr -d '\r' > "$WORK/dump.xml"
  # A dump taken while the device is still busy can come back empty; that is
  # "not on screen yet", for the caller to retry, not a parse error.
  [ -s "$WORK/dump.xml" ] || return 1
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

wait_cells() { # $1 = expected count, $2 = timeout (s)
  local elapsed=0
  while [ "$elapsed" -lt "$2" ]; do
    [ "$(dump_cells 2>/dev/null | wc -l)" -ge "$1" ] && return 0
    sleep 1; elapsed=$((elapsed + 1))
  done
  die "timed out (${2}s) waiting for $1 cells on screen"
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
  wait_desc grid-edit-done 15 "edit bar after the long press"
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
    || die "$SERIAL has no CLOSED/HALF_OPENED/OPENED postures: $states"
}

posture() { # $1 = closed | half | opened
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
  sleep 3
}

# The physical id of the display that is ON (a foldable exposes two).
active_display() {
  # One DisplayDeviceInfo line per panel; nested braces inside, so match the
  # whole line rather than a brace-delimited run.
  adb -s "$SERIAL" shell dumpsys display | tr -d '\r' \
    | grep 'DisplayDeviceInfo{' | grep 'state ON' \
    | sed -n 's/.*uniqueId="local:\([0-9]*\)".*/\1/p' | sed -n 1p
}

# Screenshot to $1 (png). Multi-display devices need the physical id.
screenshot() { # $1 = output file
  local id
  id="$(active_display 2>/dev/null || true)"
  if [ -n "$id" ] && adb -s "$SERIAL" exec-out screencap -d "$id" -p > "$1" 2>/dev/null \
    && file "$1" | grep -q 'PNG image'; then
    return 0
  fi
  adb -s "$SERIAL" exec-out screencap -p > "$1" 2>/dev/null
  file "$1" | grep -q 'PNG image' || die "screencap did not produce a PNG for $1"
}
