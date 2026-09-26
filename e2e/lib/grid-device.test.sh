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
check "wait_text gives up after its timeout" bounded wait_text "Grid and icons" 3
check "wait_id gives up after its timeout" bounded wait_id grid-edit-done 3 test
check "wait_cells gives up after its timeout" bounded wait_cells 1 3
check "wait_report gives up after its timeout" bounded "wait_report '.success' 3 test"
# wait_on_home is bounded by rounds: each round's dump capped by ROUND_CAP,
# its recovery by RECOVERY_CAP, a 1 s pause between rounds, then the
# diagnosis's own 2 s. At 1 s caps, 3 rounds: 3 x (1 + 1) + 2 + 2 = 10 s.
# SECONDS counts whole seconds and a loaded CI runner adds scheduling time,
# so the check allows 3 s on top (review on #163). A regression to an
# unbounded wait would be 60 s, far outside it.
wait_on_home_is_bounded_by_its_rounds() {
  local start=$SECONDS nominal=$((3 * (1 + 1) + 2 + 2))
  ( PATH="$WORK/wedged:$PATH" timeout 60 bash -c "$(declare -f); $(declare -p SERIAL PKG WORK STATE_URI 2>/dev/null); ROUND_CAP=1 RECOVERY_CAP=1 wait_on_home grid-item:dock 3" ) >/dev/null 2>&1 && return 1
  [ $((SECONDS - start)) -le $((nominal + 3)) ]
}
check "wait_on_home gives up after its rounds on a wedged device" wait_on_home_is_bounded_by_its_rounds

# Controls: a device that answers ends each wait at once, so a loop that
# "passes" the tests above by always giving up would fail here.
mkdir -p "$WORK/answering"
cat > "$WORK/answering/adb" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"cat /sdcard/grid-dump.xml"*)
    echo '<hierarchy><node content-desc="Search" bounds="[0,100][200,300]"/></hierarchy>' ;;
  *"content query"*"/diagnostics"*) echo 'Row: 0 json={"success":true}' ;;
esac
EOF
chmod +x "$WORK/answering/adb"
answers_at_once() { # $@ = a wait call with a 3 s timeout
  local start=$SECONDS
  ( PATH="$WORK/answering:$PATH"; "$@" ) >/dev/null 2>&1 && [ $((SECONDS - start)) -lt 3 ]
}
check "wait_desc returns at once when the node is on screen" answers_at_once wait_desc Search 3 test
check "wait_report returns at once when the report matches" answers_at_once wait_report '.success == true' 3 test

# A timeout on a device that answers, but without the node, names what had
# focus and leaves a picture of the screen.
python3 -c 'import base64,sys; sys.stdout.buffer.write(base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="))' > "$WORK/one.png"
mkdir -p "$WORK/nodock" "$WORK/shots"
cat > "$WORK/nodock/adb" <<EOF
#!/usr/bin/env bash
case "\$*" in
  *"cat /sdcard/grid-dump.xml"*) echo '<hierarchy><node resource-id="grid-item:analog" bounds="[0,0][1,1]"/></hierarchy>' ;;
  *"dumpsys window"*) echo '  mCurrentFocus=Window{1 u0 com.example/.Other}' ;;
  *"screencap"*) cat "$WORK/one.png" ;;
esac
EOF
chmod +x "$WORK/nodock/adb"
a_timeout_says_why() {
  local out shot
  out="$( ( PATH="$WORK/nodock:$PATH" MISS_DIR="$WORK/shots"; wait_on_home grid-item:dock 3 ) 2>&1 )" && return 1
  grep -q "focus: com.example/.Other}" <<<"$out" || { printf '%s\n' "$out" >&2; return 1; }
  shot="$(sed -n 's/.*screen: \([^)]*\)).*/\1/p' <<<"$out")"
  [ -n "$shot" ] && file "$shot" | grep -q 'PNG image'
}
check "a wait_on_home timeout names the focus and leaves a screenshot" a_timeout_says_why

# After a snapshot load the device answers `adb` a moment later than the
# emulator says it is up; the first `id -u` fails or answers for the wrong
# user (seen 2026-09-25 on emulator-5560, 22:11). unrooted_shell waits for
# uid 2000 against a real deadline instead of asserting it once.
mkdir -p "$WORK/late"
cat > "$WORK/late/adb" <<EOF
#!/usr/bin/env bash
case "\$*" in
  *"shell id -u"*)
    n=\$(( \$(cat "$WORK/late/calls" 2>/dev/null || echo 0) + 1 )); echo "\$n" > "$WORK/late/calls"
    [ "\$n" -ge 3 ] && echo 2000 || exit 1 ;;
esac
EOF
chmod +x "$WORK/late/adb"
waits_for_the_shell() {
  rm -f "$WORK/late/calls"
  ( PATH="$WORK/late:$PATH"; unrooted_shell 10 ) >/dev/null 2>&1
}
check "unrooted_shell waits until adb answers as uid 2000" waits_for_the_shell
cat > "$WORK/late/root" <<'EOF'
#!/usr/bin/env bash
case "$*" in *"shell id -u"*) echo 0 ;; esac
EOF
mkdir -p "$WORK/rooted"; mv "$WORK/late/root" "$WORK/rooted/adb"; chmod +x "$WORK/rooted/adb"
refuses_root() {
  local start=$SECONDS
  ( PATH="$WORK/rooted:$PATH"; unrooted_shell 3 ) >/dev/null 2>&1 && return 1
  [ $((SECONDS - start)) -le 6 ]
}
check "unrooted_shell refuses a root shell once its timeout is up" refuses_root

# A loaded host makes each observation slow, not the device: 2 rounds in 30 s
# on emulator-5560 while a second emulator ran (2026-09-25 22:58). The dock
# that appears on the third dump must be waited for however long a dump
# takes, and the success must say what it cost.
mkdir -p "$WORK/slow"
cat > "$WORK/slow/adb" <<EOF
#!/usr/bin/env bash
case "\$*" in
  *"uiautomator dump"*) sleep 11 ;;
  *"cat /sdcard/grid-dump.xml"*)
    n=\$(( \$(cat "$WORK/slow/dumps" 2>/dev/null || echo 0) + 1 )); echo "\$n" > "$WORK/slow/dumps"
    [ "\$n" -ge 3 ] && echo '<hierarchy><node resource-id="grid-item:dock" bounds="[0,0][9,9]"/></hierarchy>' \
      || echo '<hierarchy/>' ;;
esac
EOF
chmod +x "$WORK/slow/adb"
waits_three_slow_rounds() {
  local out
  rm -f "$WORK/slow/dumps"
  out="$( ( PATH="$WORK/slow:$PATH"; log() { printf '%s\n' "$*"; }; wait_on_home grid-item:dock ) 2>&1 )" || { printf '%s\n' "$out" >&2; return 1; }
  grep -qE "after 3 rounds, [0-9]+ s" <<<"$out"
}
check "wait_on_home waits three rounds on a slow host and says what they cost" waits_three_slow_rounds

# Every device command is scoped to $SERIAL. An unscoped `adb reconnect
# offline` reaches every emulator on the host, including instances other
# sessions hold under their locks (review on #163).
mkdir -p "$WORK/record"
cat > "$WORK/record/adb" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "$WORK/record/calls"
case "\$*" in
  *"shell id -u"*)
    n=\$(( \$(cat "$WORK/record/n" 2>/dev/null || echo 0) + 1 )); echo "\$n" > "$WORK/record/n"
    [ "\$n" -ge 2 ] && echo 2000 || exit 1 ;;
esac
EOF
chmod +x "$WORK/record/adb"
reconnect_is_scoped() {
  rm -f "$WORK/record/calls" "$WORK/record/n"
  ( PATH="$WORK/record:$PATH"; unrooted_shell 10 ) >/dev/null 2>&1 || return 1
  grep -q "reconnect" "$WORK/record/calls" || { echo "no reconnect attempted" >&2; return 1; }
  ! grep -v "^-s fake " "$WORK/record/calls" | grep -q .
}
check "unrooted_shell scopes every adb call, the reconnect included, to its serial" reconnect_is_scoped

# unrooted_shell's timeout covers the whole operation, the unroot included:
# a hanging `adb unroot` must not delay the deadline.
mkdir -p "$WORK/stuckroot"
cat > "$WORK/stuckroot/adb" <<'EOF'
#!/usr/bin/env bash
case "$*" in *" unroot"*) sleep 60 ;; *"shell id -u"*) echo 2000 ;; esac
EOF
chmod +x "$WORK/stuckroot/adb"
unroot_is_under_the_deadline() {
  local start=$SECONDS
  ( PATH="$WORK/stuckroot:$PATH"; timeout 30 bash -c "$(declare -f); $(declare -p SERIAL PKG WORK 2>/dev/null); unrooted_shell 3" ) >/dev/null 2>&1
  [ $((SECONDS - start)) -le 6 ]
}
check "unrooted_shell's timeout covers a hanging unroot" unroot_is_under_the_deadline

# A round whose dump uses up ROUND_CAP still wakes the device and reopens
# home: the recovery gets time of its own, or the next round looks at the
# same screen (review on #163).
mkdir -p "$WORK/slowdump"
cat > "$WORK/slowdump/adb" <<EOF
#!/usr/bin/env bash
case "\$*" in
  *"uiautomator dump"*) sleep 60 ;;
  *"KEYCODE_WAKEUP"*|*"am start"*) echo "\$*" >> "$WORK/slowdump/recovery" ;;
esac
EOF
chmod +x "$WORK/slowdump/adb"
recovery_runs_after_a_slow_dump() {
  rm -f "$WORK/slowdump/recovery"
  ( PATH="$WORK/slowdump:$PATH"; ROUND_CAP=1; wait_on_home grid-item:dock 2 ) >/dev/null 2>&1
  grep -q KEYCODE_WAKEUP "$WORK/slowdump/recovery" 2>/dev/null && grep -q "am start" "$WORK/slowdump/recovery"
}
check "a round whose dump used up its cap still wakes the device and reopens home" recovery_runs_after_a_slow_dump

# The search helpers config-screenshots.sh and l4-search.sh each carried a
# copy of (#164). screen_state reads one dump; the waits around it are
# bounded like every other wait in the library.
mkdir -p "$WORK/search"
cat > "$WORK/search/adb" <<EOF
#!/usr/bin/env bash
case "\$*" in
  *"cat /sdcard/grid-dump.xml"*) cat "$WORK/search/screen.xml" ;;
  *"dumpsys input_method"*) cat "$WORK/search/ime.txt" ;;
  *"KEYCODE_BACK"*) echo "  mInputShown=false" > "$WORK/search/ime.txt" ;;
  *"input tap"*) echo '<hierarchy><node content-desc="Show filters" bounds="[0,0][9,9]"/></hierarchy>' > "$WORK/search/screen.xml" ;;
esac
EOF
chmod +x "$WORK/search/adb"
home_screen() { echo '<hierarchy><node content-desc="Search" bounds="[0,0][9,9]"/></hierarchy>' > "$WORK/search/screen.xml"; }
screen_state_reads_the_screen() {
  local home search none
  home_screen; home="$(PATH="$WORK/search:$PATH" screen_state)"
  echo '<hierarchy><node content-desc="Show filters" bounds="[0,0][9,9]"/><node content-desc="Search" bounds="[0,0][9,9]"/></hierarchy>' > "$WORK/search/screen.xml"
  search="$(PATH="$WORK/search:$PATH" screen_state)"
  : > "$WORK/search/screen.xml"; none="$(PATH="$WORK/search:$PATH" screen_state)"
  [ "$home/$search/$none" = "home/search/unknown" ]
}
check "screen_state tells home from search, and an empty dump from both" screen_state_reads_the_screen
opens_search_and_dismisses_the_keyboard() {
  home_screen; echo "  mInputShown=true" > "$WORK/search/ime.txt"
  ( PATH="$WORK/search:$PATH"; open_search_field c && dismiss_keyboard ) >/dev/null 2>&1 \
    && grep -q "mInputShown=false" "$WORK/search/ime.txt"
}
check "open_search_field opens search, dismiss_keyboard closes the keyboard" opens_search_and_dismisses_the_keyboard
# 3 rounds at a 1 s cap, each a look, a tap lookup and a look: 3 x 1 + 2 s
# of pauses = 5 s nominal, plus 3 s for whole-second rounding and scheduling.
open_search_is_bounded_by_its_rounds() {
  local start=$SECONDS
  ( PATH="$WORK/wedged:$PATH" timeout 60 bash -c "$(declare -f); $(declare -p SERIAL PKG WORK STATE_URI 2>/dev/null); ROUND_CAP=1 open_search_field c" ) >/dev/null 2>&1 && return 1
  [ $((SECONDS - start)) -le $((3 * 1 + 2 + 3)) ]
}
check "open_search_field gives up after its rounds on a wedged device" open_search_is_bounded_by_its_rounds
check "dismiss_keyboard gives up after its timeout on a wedged device" bounded "KEYBOARD_TIMEOUT=1 dismiss_keyboard"

# A wait inside a wait cannot extend the outer deadline: the inner retry_for
# gets what is left of the outer one at most (#164, query_json_as_user is
# called inside wait_diagnostics_sha).
nested_waits_share_the_outer_deadline() {
  local start=$SECONDS
  never() { sleep 1; return 1; }
  inner() { retry_for 30 never; return 1; }
  retry_for 3 inner
  [ $((SECONDS - start)) -le 6 ]
}
check "a nested retry_for cannot extend the outer deadline" nested_waits_share_the_outer_deadline

# Opening search is an action that can be lost: a tap that did not register
# cannot be waited out, only repeated. On the fold with software rendering a
# dump took about 4 s, so a 10 s bound had room for two looks, and
# config-screenshots.sh failed there (#164, 2026-09-26 10:11). open_search_field
# makes up to 3 rounds, each re-issuing the tap, each capped, and says what
# they cost. The fake loses the first two taps.
mkdir -p "$WORK/slowsearch"
cat > "$WORK/slowsearch/adb" <<EOF
#!/usr/bin/env bash
case "\$*" in
  *"uiautomator dump"*) sleep 4 ;;
  *"input tap"*)
    n=\$(( \$(cat "$WORK/slowsearch/taps" 2>/dev/null || echo 0) + 1 )); echo "\$n" > "$WORK/slowsearch/taps" ;;
  *"cat /sdcard/grid-dump.xml"*)
    # the first two taps are lost; search opens on the third
    if [ "\$(cat "$WORK/slowsearch/taps" 2>/dev/null || echo 0)" -ge 3 ]; then echo '<hierarchy><node content-desc="Show filters" bounds="[0,0][9,9]"/></hierarchy>'
    else echo '<hierarchy><node content-desc="Search" bounds="[0,0][9,9]"/></hierarchy>'; fi ;;
esac
EOF
chmod +x "$WORK/slowsearch/adb"
open_search_waits_three_slow_rounds() {
  local out
  rm -f "$WORK/slowsearch/taps"
  out="$( ( PATH="$WORK/slowsearch:$PATH"; log() { printf '%s\n' "$*"; }; open_search_field ) 2>&1 )" || { printf '%s\n' "$out" >&2; return 1; }
  grep -qE "search open after 3 rounds, [0-9]+ s" <<<"$out"
}
check "open_search_field waits three rounds on a slow host and says what they cost" open_search_waits_three_slow_rounds

# The old name is gone on purpose: a call site that still means the
# script's former open_search (type a letter, close the keyboard) must fail
# with "command not found", never run the library's different function.
old_open_search_is_gone() { ! declare -F open_search >/dev/null; }
check "no open_search exists, so an old call site fails loudly" old_open_search_is_gone

# Round-based waits inside a wall-clock wait stop at the outer deadline too:
# the pause and the next round are skipped once it has passed (#179 review).
rounds_keep_the_outer_deadline() {
  local start=$SECONDS
  failing() { sleep 1; return 1; }
  retry_for 2 retry_rounds 5 1 failing
  [ $((SECONDS - start)) -le 3 ]
}
check "retry_rounds inside retry_for stops at the outer deadline" rounds_keep_the_outer_deadline
# tap_text's own adb calls (the window insets it measures, the tap itself)
# are bounded like every other call: a fake that finds the node but hangs on
# them must not hold tap_text past its deadline (#179 review).
mkdir -p "$WORK/tapwedge"
cat > "$WORK/tapwedge/adb" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"cat /sdcard/grid-dump.xml"*) echo '<hierarchy><node text="Search" bounds="[100,900][300,1000]"/></hierarchy>' ;;
  *"dumpsys window"*|*"input tap"*) sleep 60 ;;
  *"wm size"*) echo "Physical size: 1080x1920" ;;
esac
EOF
chmod +x "$WORK/tapwedge/adb"
tap_text_is_bounded() {
  local start=$SECONDS
  ( PATH="$WORK/tapwedge:$PATH" timeout 60 bash -c "$(declare -f); $(declare -p SERIAL PKG WORK 2>/dev/null); ADB_DEADLINE=\$((SECONDS + 3)); tap_text Search" ) >/dev/null 2>&1
  [ $((SECONDS - start)) -le 6 ]
}
check "tap_text's own adb calls give up at the deadline" tap_text_is_bounded

# A timed-out wait_report prints the last report it saw, even when the query
# after it failed: a failed query must not wipe the evidence (#179 review).
mkdir -p "$WORK/flaky"
cat > "$WORK/flaky/adb" <<EOF
#!/usr/bin/env bash
case "\$*" in
  *"content query"*"/diagnostics"*)
    n=\$(( \$(cat "$WORK/flaky/n" 2>/dev/null || echo 0) + 1 )); echo "\$n" > "$WORK/flaky/n"
    [ "\$n" = 1 ] && echo 'Row: 0 json={"success":false,"marker":"seen"}' || exit 1 ;;
esac
EOF
chmod +x "$WORK/flaky/adb"
timeout_keeps_the_last_report() {
  local out
  rm -f "$WORK/flaky/n"
  out="$( ( PATH="$WORK/flaky:$PATH"; wait_report '.success == true' 3 test ) 2>&1 )" && return 1
  grep -q '"marker":"seen"' <<<"$out"
}
check "a wait_report timeout prints the last report seen, not what a failed query left" timeout_keeps_the_last_report

# No round starts after an outer deadline has passed: the time is checked
# again after the pause, not only before it (#179 review).
no_round_starts_late() {
  local deadline_at starts=()
  record() { echo "$SECONDS" >> "$WORK/starts"; sleep 1; return 1; }
  rm -f "$WORK/starts"
  deadline_at=$((SECONDS + 2))
  ( ADB_DEADLINE=$deadline_at; retry_rounds 5 1 record ) || true
  while read -r t; do [ "$t" -le "$deadline_at" ] || return 1; done < "$WORK/starts"
}
check "retry_rounds starts no round after an outer deadline" no_round_starts_late

# grant_home_role: the one place that makes the launcher the home app (#181).
# Eleven inline copies discarded the command's own error; the helper reports
# it, and checks the holder afterwards by exact package name.
mkdir -p "$WORK/role"
cat > "$WORK/role/adb" <<EOF
#!/usr/bin/env bash
case "\$*" in
  *"add-role-holder"*)
    [ -e "$WORK/role/refuse" ] && { echo "Error: role not available for this package"; exit 1; }
    : ;;
  *"get-role-holders"*) cat "$WORK/role/holders" ;;
esac
EOF
chmod +x "$WORK/role/adb"
role_case() { # $1 = holders file content, [$2 = refuse]
  printf '%s\n' "$1" > "$WORK/role/holders"; rm -f "$WORK/role/refuse"
  [ -z "${2:-}" ] || : > "$WORK/role/refuse"
  ( PATH="$WORK/role:$PATH"; PKG=org.andashi.home; grant_home_role ) 2>&1
}
grants_when_it_holds() { role_case "org.andashi.home" >/dev/null; }
check "grant_home_role succeeds when the package then holds the role" grants_when_it_holds
reports_the_commands_error() {
  local out; out="$(role_case "com.android.launcher3" refuse)" && return 1
  grep -q "role not available for this package" <<<"$out"
}
check "grant_home_role puts the command's own error into the failure" reports_the_commands_error
refuses_a_prefix_match() {
  local out; out="$(role_case "org.andashi.home.debug")" && return 1
  grep -q "org.andashi.home.debug" <<<"$out"
}
check "grant_home_role does not take org.andashi.home.debug for org.andashi.home" refuses_a_prefix_match
check "grant_home_role gives up on a wedged device" bounded "ADB_DEADLINE=\$((SECONDS + 3)) PKG=org.andashi.home grant_home_role"

# Pushing a config gives up at the deadline, and says it failed: a wedged
# content write or broadcast must neither hold the caller, and with it the
# device lock, nor pass for a success (#175 review). Checked in a shell
# without pipefail, as a caller's may be.
echo '{}' > "$WORK/config.json"
fails_in_time() { # $@ = a call that must fail within 6 s on a wedged device
  local start=$SECONDS
  ( PATH="$WORK/wedged:$PATH" timeout 20 bash -c "$(declare -f); $(declare -p SERIAL PKG WORK INGEST_URI RECEIVER ACTION 2>/dev/null); $*" ) >/dev/null 2>&1 && return 1
  [ $((SECONDS - start)) -le 6 ]
}
check "write_config fails at the deadline while the content write hangs" fails_in_time "ADB_DEADLINE=\$((SECONDS + 3)) write_config $WORK/config.json"
check "reload_broadcast fails at the deadline while the broadcast hangs" fails_in_time "ADB_DEADLINE=\$((SECONDS + 3)) reload_broadcast"
# The same for every helper that branches on adb's status: a hung dumpsys
# is not a hidden keyboard, and a hung grant says so rather than blaming
# whoever holds the role.
check "ime_read fails, and does not say hidden, while dumpsys hangs" fails_in_time "ADB_DEADLINE=\$((SECONDS + 3)) ime_read"
check "query_json fails at the deadline while the query hangs" fails_in_time "ADB_DEADLINE=\$((SECONDS + 3)) query_json diagnostics"
grant_names_the_hung_grant() {
  local out
  out="$( ( PATH="$WORK/wedged:$PATH" timeout 20 bash -c "$(declare -f); $(declare -p SERIAL WORK 2>/dev/null); PKG=org.andashi.home; ADB_DEADLINE=\$((SECONDS + 3)); grant_home_role" ) 2>&1 )" && return 1
  grep -q "could not grant the HOME role" <<<"$out"
}
check "grant_home_role names the hung grant, not the role holder" grant_names_the_hung_grant
# The report about a push is the one with its hash that was not there before
# the push, whatever caused the reload: the trigger names the cause, not the
# push. l4-config waited for "file-watcher" and missed it, because the grid's
# first measurement reloaded the same file 1.2 s later and replaced the report
# (ADR 0003, section 4).
mkdir -p "$WORK/reports"
cat > "$WORK/reports/adb" <<EOF
#!/usr/bin/env bash
case "\$*" in
  *"content query"*"/diagnostics"*) cat "$WORK/reports/now" ;;
esac
EOF
chmod +x "$WORK/reports/adb"
serve_report() { printf 'Row: 0 json=%s\n' "$1" > "$WORK/reports/now"; }
push_report_found() { # $1 = report before the push, $2 = report now, $3 = hash pushed
  serve_report "$2"
  ( PATH="$WORK/reports:$PATH"; wait_push_report "$1" "$3" 1 test ) >/dev/null 2>&1
}
takes_a_report_whatever_caused_it() {
  push_report_found '{"configSha256":"a","trigger":"broadcast"}' '{"configSha256":"a","trigger":"grid-measured"}' a
}
check "wait_push_report takes the push's report whatever caused the reload" takes_a_report_whatever_caused_it
takes_the_first_report() {
  push_report_found 'null' '{"configSha256":"a","trigger":"startup-check"}' a
}
check "wait_push_report takes the first report there is" takes_the_first_report
refuses_the_report_from_before() {
  ! push_report_found '{"configSha256":"a","trigger":"broadcast"}' '{"configSha256":"a","trigger":"broadcast"}' a
}
check "wait_push_report does not take the report from before the push" refuses_the_report_from_before
refuses_another_files_report() {
  ! push_report_found '{"configSha256":"a","trigger":"broadcast"}' '{"configSha256":"b","trigger":"file-watcher"}' a
}
check "wait_push_report does not take another file's report" refuses_another_files_report
# The provider answers the JSON literal null before the first report
# (ConfigStateProvider). A failed query is not that answer: taken for it, an
# older report of the same hash would pass as the push's (#192 review).
report_now_is_null_without_one() {
  serve_report null
  [ "$( PATH="$WORK/reports:$PATH"; report_now )" = null ]
}
check "report_now passes on the provider's null before the first report" report_now_is_null_without_one
report_now_fails_on_a_failed_query() {
  printf 'Error: provider not ready\n' > "$WORK/reports/now"
  ! ( PATH="$WORK/reports:$PATH" timeout 20 bash -c "$(declare -f); $(declare -p SERIAL PKG WORK STATE_URI 2>/dev/null); REPORT_NOW_TIMEOUT=1 report_now" ) >/dev/null 2>&1
}
check "report_now fails, and does not say null, when the query fails" report_now_fails_on_a_failed_query

# A wait that succeeds says how long it took, so a bound that is about to
# start failing shows it first: 9.8 s of 10 is not the same pass as 0.2 s.
# It says so on stderr, never stdout, because callers capture stdout:
# diag="$(query_json_as_user ...)" wraps a retry_for (#182 follow-up).
reports_elapsed_on_stderr_only() {
  local out err n=0
  second_try() { n=$((n + 1)); [ "$n" -ge 2 ] && echo value; }
  out="$( { retry_for 5 second_try 2>"$WORK/err"; } )" || return 1
  err="$(cat "$WORK/err")"
  [ "$out" = value ] && grep -qE "after [0-9]+ s of 5 s" <<<"$err" && grep -q "second_try" <<<"$err"
}
check "a successful retry_for reports its elapsed time on stderr, and stdout is untouched" reports_elapsed_on_stderr_only

exit "$failed"
