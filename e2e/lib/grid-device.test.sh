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
# scrolled half under the top app bar is still in the dump; a tap at its
# centre lands on the bar, the switch never changes, and l4-write-back failed
# two runs in five on exactly that. A 1080x1920 screen at 420 dpi: the top
# 100 dp (262 px) are status and app bar, the bottom 48 dp (126 px) the
# navigation bar.
mkdir -p "$WORK/screen"
cat > "$WORK/screen/adb" <<EOF
#!/usr/bin/env bash
case "\$*" in
  *"wm size"*) echo "Physical size: 1080x1920" ;;
  *"wm density"*) echo "Physical density: 420" ;;
  *"cat /sdcard/grid-dump.xml"*) cat "$WORK/screen/fixture.xml" ;;
  *"input tap"*) echo "\$*" >> "$WORK/taps" ;;
esac
EOF
chmod +x "$WORK/screen/adb"
row_at() { # $1 = top, $2 = bottom: the one row on screen
  echo "<hierarchy><node text=\"Show apps in a list\" bounds=\"[84,$1][733,$2]\"/></hierarchy>" > "$WORK/screen/fixture.xml"
  rm -f "$WORK/taps"
}
taps() { [ -f "$WORK/taps" ] && wc -l < "$WORK/taps" || echo 0; }

tap_text_refuses_a_row_under_the_app_bar() {
  row_at 150 222
  ! ( PATH="$WORK/screen:$PATH" tap_text "Show apps in a list" ) && [ "$(taps)" = 0 ]
}
check "tap_text returns 1 and does not tap a row whose centre is under the app bar" tap_text_refuses_a_row_under_the_app_bar

tap_text_refuses_a_row_over_the_navigation_bar() {
  row_at 1860 1910
  ! ( PATH="$WORK/screen:$PATH" tap_text "Show apps in a list" ) && [ "$(taps)" = 0 ]
}
check "tap_text returns 1 and does not tap a row whose centre is over the navigation bar" tap_text_refuses_a_row_over_the_navigation_bar

tap_text_taps_a_row_in_the_open() {
  row_at 800 900
  ( PATH="$WORK/screen:$PATH" tap_text "Show apps in a list" ) && [ "$(taps)" = 1 ] && grep -q "input tap 408 850" "$WORK/taps"
}
check "tap_text taps a row in the open part of the screen at its centre" tap_text_taps_a_row_in_the_open

tap_text_still_returns_1_when_absent() {
  echo "<hierarchy/>" > "$WORK/screen/fixture.xml"; rm -f "$WORK/taps"
  ! ( PATH="$WORK/screen:$PATH" tap_text "Show apps in a list" ) && [ "$(taps)" = 0 ]
}
check "tap_text still returns 1 when the text is not on screen" tap_text_still_returns_1_when_absent

exit "$failed"
