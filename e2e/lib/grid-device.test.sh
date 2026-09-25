#!/usr/bin/env bash
# Tests for e2e/lib/grid-device.sh that need no device: adb is a fake on PATH.
#
#   e2e/lib/grid-device.test.sh
set -euo pipefail
cd "$(dirname "$0")"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
SERIAL=fake PKG=org.andashi.home.test
log() { :; }
die() { printf 'die: %s\n' "$*" >&2; exit 1; }
# shellcheck source=grid-device.sh
. ./grid-device.sh

failed=0
check() { # $1 = description, $2... = command that must succeed
  local what="$1"; shift
  if "$@"; then printf ' + %s\n' "$what"; else printf ' x %s\n' "$what"; failed=1; fi
}

# A fake adb whose `uiautomator dump` hangs, as a wedged device's does.
mkdir -p "$WORK/bin"
cat > "$WORK/bin/adb" <<'EOF'
#!/usr/bin/env bash
case "$*" in *"uiautomator dump"*) sleep 60 ;; esac
EOF
chmod +x "$WORK/bin/adb"

# wait_cell's timeout is wall-clock time. One dump took 3.84 s on the
# emulator (#127), so counting rounds made `wait_cell ... 10` run for most of
# a minute, and a hanging dump made it unbounded.
wait_cell_is_bounded() {
  local start=$SECONDS
  ( PATH="$WORK/bin:$PATH" wait_cell analog 3 "test" ) 2>/dev/null && return 1
  [ $((SECONDS - start)) -le 6 ]
}
check "wait_cell gives up after its timeout even while the dump hangs" wait_cell_is_bounded

# The same when the dump returns but reading it back hangs.
mkdir -p "$WORK/catbin"
cat > "$WORK/catbin/adb" <<'EOF'
#!/usr/bin/env bash
case "$*" in *"cat /sdcard/grid-dump.xml"*) sleep 60 ;; esac
EOF
chmod +x "$WORK/catbin/adb"
wait_cell_is_bounded_when_the_read_hangs() {
  local start=$SECONDS
  ( PATH="$WORK/catbin:$PATH" wait_cell analog 3 "test" ) 2>/dev/null && return 1
  [ $((SECONDS - start)) -le 6 ]
}
check "wait_cell gives up after its timeout even while reading the dump hangs" wait_cell_is_bounded_when_the_read_hangs

# Control: a device that answers has the cell found at once.
mkdir -p "$WORK/answers"
cat > "$WORK/answers/adb" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"cat /sdcard/grid-dump.xml"*)
    echo '<hierarchy><node resource-id="grid-item:analog" bounds="[0,100][200,300]"/></hierarchy>' ;;
esac
EOF
chmod +x "$WORK/answers/adb"
wait_cell_finds_a_cell() {
  local start=$SECONDS
  # Success is the assertion; the time only has to stay inside the deadline,
  # with a margin for a loaded runner (a busy machine must not turn it red).
  ( PATH="$WORK/answers:$PATH" wait_cell analog 3 "test" ) && [ $((SECONDS - start)) -lt 3 ]
}
check "wait_cell returns at once when the cell is on screen" wait_cell_finds_a_cell


# tap_text taps a node only where a tap reaches it (#155). A settings row
# scrolled under the top app bar is still in the dump; a tap at its centre
# lands on the bar, the switch never changes, and l4-write-back failed on
# exactly that. What covers a node is measured, not assumed, so the fixtures
# have the shapes of real dumps of the test instance (1080x1920, 480 dpi):
# the "Grid and icons" bar is a later sibling of the scrolling list at
# [0,0][1080,264], and `dumpsys window` puts the status bar at 0-72 and the
# gesture navigation bar at 1848-1920.
mkdir -p "$WORK/screen"
cat > "$WORK/screen/adb" <<EOF
#!/usr/bin/env bash
case "\$*" in
  *"dumpsys window"*) cat "$WORK/screen/window.txt" ;;
  *"wm size"*) echo "Physical size: 1080x1920" ;;
  *"wm density"*) echo "Physical density: 480" ;;
  *"cat /sdcard/grid-dump.xml"*) cat "$WORK/screen/fixture.xml" ;;
  *"input tap"*) echo "\$*" >> "$WORK/taps" ;;
esac
EOF
chmod +x "$WORK/screen/adb"
cat > "$WORK/screen/window.txt" <<'EOF'
        InsetsSource id=a5530001 type=navigationBars frame=[0,1848][1080,1920] visible=true flags=SUPPRESS_SCRIM|ANIMATE_RESIZING sideHint=BOTTOM
        InsetsSource id=a5530005 type=mandatorySystemGestures frame=[0,1824][1080,1920] visible=true flags= sideHint=BOTTOM
        InsetsSource id=c0b60000 type=statusBars frame=[0,0][1080,72] visible=true flags= sideHint=TOP
EOF
settings_row() { # $1 = row top, $2 = row bottom, $3 = the app bar's bottom edge
  cat > "$WORK/screen/fixture.xml" <<EOF
<hierarchy><node bounds="[0,0][1080,1920]">
  <node scrollable="true" bounds="[0,0][1080,1920]">
    <node text="Show apps in a list" bounds="[84,$1][733,$2]"/>
  </node>
  <node bounds="[0,0][1080,$3]"><node text="Grid and icons" bounds="[313,119][768,217]"/></node>
</node></hierarchy>
EOF
  rm -f "$WORK/taps"
}
fixture() { cat > "$WORK/screen/fixture.xml"; rm -f "$WORK/taps"; }
taps() { [ -f "$WORK/taps" ] && wc -l < "$WORK/taps" || echo 0; }
tapped_at() { [ "$(taps)" = 1 ] && grep -q "input tap $1\$" "$WORK/taps"; }
on_screen() { PATH="$WORK/screen:$PATH" tap_text "$1"; }

tap_text_refuses_a_row_under_the_app_bar() {
  settings_row 150 222 264
  ! ( on_screen "Show apps in a list" ) && [ "$(taps)" = 0 ]
}
check "tap_text refuses a row whose centre is under the app bar" tap_text_refuses_a_row_under_the_app_bar

# An expanded bar is what makes a list scroll under something: 152 dp at
# 480 dpi is 456 px, and a row centred at 120 dp (360 px) is under it.
tap_text_refuses_a_row_under_an_expanded_app_bar() {
  settings_row 330 390 456
  ! ( on_screen "Show apps in a list" ) && [ "$(taps)" = 0 ]
}
check "tap_text refuses a row under an expanded app bar, whatever its height" tap_text_refuses_a_row_under_an_expanded_app_bar

tap_text_refuses_a_row_over_the_navigation_bar() {
  settings_row 1860 1910 264
  ! ( on_screen "Show apps in a list" ) && [ "$(taps)" = 0 ]
}
check "tap_text refuses a row whose centre is over the navigation bar" tap_text_refuses_a_row_over_the_navigation_bar

tap_text_refuses_a_row_under_the_status_bar() {
  fixture <<'EOF'
<hierarchy><node bounds="[0,0][1080,1920]">
  <node scrollable="true" bounds="[0,0][1080,1920]"><node text="Show apps in a list" bounds="[84,10][733,60]"/></node>
</node></hierarchy>
EOF
  ! ( on_screen "Show apps in a list" ) && [ "$(taps)" = 0 ]
}
check "tap_text refuses a row whose centre is under the status bar, on a screen without an app bar" tap_text_refuses_a_row_under_the_status_bar

tap_text_taps_a_row_just_above_the_navigation_bar() {
  settings_row 1770 1830 264
  ( on_screen "Show apps in a list" ) && tapped_at "408 1800"
}
check "tap_text taps a row just above the navigation bar" tap_text_taps_a_row_just_above_the_navigation_bar

tap_text_taps_a_row_in_the_open() {
  settings_row 800 900 264
  ( on_screen "Show apps in a list" ) && tapped_at "408 850"
}
check "tap_text taps a row in the open part of the screen at its centre" tap_text_taps_a_row_in_the_open

# Nothing scrolls under what does not scroll: a search chip right under a top
# search bar, and a label its own button is drawn over, are where they are
# drawn (both from the real dumps of the search and the badges screens).
tap_text_taps_a_chip_under_a_top_search_bar() {
  fixture <<'EOF'
<hierarchy><node bounds="[0,0][1080,1920]">
  <node bounds="[36,96][1044,240]"><node text="andashi" bounds="[168,132][768,204]"/></node>
  <node resource-id="search-actions" bounds="[36,264][1044,336]"><node text="Pinned" bounds="[560,270][736,330]"/></node>
</node></hierarchy>
EOF
  ( on_screen "Pinned" ) && tapped_at "648 300"
}
check "tap_text taps a chip right under a top search bar" tap_text_taps_a_chip_under_a_top_search_bar

tap_text_taps_a_label_under_its_own_button() {
  fixture <<'EOF'
<hierarchy><node bounds="[0,0][1080,1920]">
  <node scrollable="true" bounds="[0,0][1080,1920]">
    <node bounds="[701,1696][960,1816]"><node text="Grant" bounds="[770,1726][890,1786]"/><node bounds="[701,1696][960,1816]"/></node>
  </node>
  <node bounds="[0,0][1080,264]"/>
</node></hierarchy>
EOF
  ( on_screen "Grant" ) && tapped_at "830 1756"
}
check "tap_text taps a label its own button is drawn over" tap_text_taps_a_label_under_its_own_button

tap_text_still_returns_1_when_absent() {
  fixture <<<"<hierarchy/>"
  ! ( on_screen "Show apps in a list" ) && [ "$(taps)" = 0 ]
}
check "tap_text still returns 1 when the text is not on screen" tap_text_still_returns_1_when_absent

# A dump that fails returns 1 from node_bounds, and an assignment from it
# under `set -e` ended the script with no message: l4-write-back's first run
# on the new tap_text stopped mid-step like that (#155). A caller says why.
mkdir -p "$WORK/nodump"
cat > "$WORK/nodump/adb" <<'EOF'
#!/usr/bin/env bash
case "$*" in *"uiautomator dump"*) exit 1 ;; esac
EOF
chmod +x "$WORK/nodump/adb"
# Its own bash process: `check` runs each test as an `if` condition, where
# `set -e` is ignored even inside a subshell, so the defect would not show.
says_why_when_the_dump_fails() { # $1 = helper, $2 = argument
  local err
  err="$(PATH="$WORK/nodump:$PATH" bash -c '
    set -euo pipefail
    SERIAL=fake PKG=org.andashi.home.test WORK="$1"
    die() { printf "die: %s\n" "$*" >&2; exit 1; }
    . ./grid-device.sh
    "$2" "$3"' _ "$WORK" "$1" "$2" 2>&1 >/dev/null)" && return 1
  grep -q '^die: ' <<<"$err"
}
check "tap_desc says why when the dump fails" says_why_when_the_dump_fails tap_desc Search
check "tap_id says why when the dump fails" says_why_when_the_dump_fails tap_id grid-edit-done

# Every other wait loop in the library has the same contract: its timeout is
# wall-clock time, whatever the device does. This fake hangs on every call,
# as a wedged adb connection does. An outer timeout keeps a red run short.
mkdir -p "$WORK/wedged"
cat > "$WORK/wedged/adb" <<'EOF'
#!/usr/bin/env bash
sleep 60
EOF
chmod +x "$WORK/wedged/adb"
bounded() { # $@ = a wait call with a 3 s timeout
  local start=$SECONDS
  ( PATH="$WORK/wedged:$PATH" timeout 20 bash -c "$(declare -f); $(declare -p SERIAL PKG WORK STATE_URI 2>/dev/null); $*" ) >/dev/null 2>&1
  [ $((SECONDS - start)) -le 6 ]
}
check "wait_desc gives up after its timeout" bounded wait_desc Search 3 test
check "wait_id gives up after its timeout" bounded wait_id grid-edit-done 3 test
check "wait_cells gives up after its timeout" bounded wait_cells 1 3
check "wait_report gives up after its timeout" bounded "wait_report '.success' 3 test"
check "wait_on_home gives up after its timeout" bounded wait_on_home grid-item:dock 3

exit "$failed"
