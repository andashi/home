#!/usr/bin/env bash
# Tests for e2e/ci/l2-with-evidence.sh that need no device: adb is a fake on
# PATH, and its window list is whatever the test wrote into $WORK/windows.
#
#   e2e/ci/l2-with-evidence.test.sh
set -euo pipefail
cd "$(dirname "$0")"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
# shellcheck source=l2-with-evidence.sh
. ./l2-with-evidence.sh

passed=0 total=0
check() { # $1 = description, $2... = command that must succeed
  local what="$1"; shift
  total=$((total + 1))
  if "$@"; then printf ' + %s\n' "$what"; passed=$((passed + 1)); else printf ' x %s\n' "$what"; fi
}

# A fake adb. `dumpsys window windows` prints $WORK/windows; `am force-stop`
# appends the package to $WORK/stopped and, if $WORK/sticky does not exist,
# removes that package's ANR window, as a real force-stop does. A persistent
# process survives force-stop ($WORK/sticky); its dialog's Wait button is in
# the uiautomator dump, and a tap on it (logged to $WORK/taps) closes the
# topmost ANR window, unless $WORK/waitsticky exists.
mkdir -p "$WORK/bin"
cat > "$WORK/bin/adb" <<'EOF'
#!/usr/bin/env bash
work="$ADB_FAKE_WORK"
case "$*" in
  *"dumpsys window windows"*)
    echo x >> "$work/reads"
    if [ -e "$work/closeat" ] && [ "$(date +%s%N)" -ge "$(cut -d' ' -f1 "$work/closeat")" ]; then
      grep -v "Application Not Responding: $(cut -d' ' -f2 "$work/closeat")}" "$work/windows" > "$work/w2" || true
      mv "$work/w2" "$work/windows"; rm -f "$work/closeat"
    fi
    if grep -q 'Application Not Responding' "$work/windows"; then
      # The test APK lands while the window list is being read: on the Nth
      # read that finds a dialog, N from $WORK/installonread.
      echo x >> "$work/anrreads"
      if [ -e "$work/installonread" ] && [ "$(wc -l < "$work/anrreads")" -ge "$(cat "$work/installonread")" ]; then
        echo new > "$work/installed"
      fi
      # A read that takes a while once there is a dialog to report.
      [ ! -e "$work/slowread" ] || sleep 3
      # Reads that take a while after a tap, while the Wait is verified.
      [ ! -e "$work/slowaftertap" ] || [ ! -e "$work/taps" ] || sleep 2
    fi
    # A tapped Wait closes its dialog only after two more reads have seen it,
    # as on the device, where the rule's first look right after the tap still
    # found System UI's (#189). Two, not one: the rule reads once more after
    # its wait, and a rule that does not wait at all must still meet the dialog.
    if [ -e "$work/closing" ]; then
      seen=$(( $(cat "$work/closing.seen" 2>/dev/null || echo 0) + 1 ))
      if [ "$seen" -gt 2 ]; then
        grep -v "Application Not Responding: $(cat "$work/closing")}" "$work/windows" > "$work/w2" || true
        mv "$work/w2" "$work/windows"; rm -f "$work/closing" "$work/closing.seen"
      else
        echo "$seen" > "$work/closing.seen"
      fi
    fi
    if [ -e "$work/dumpfail" ] && [ -e "$work/stopped" ]; then exit 1; fi
    if [ -e "$work/dumphang" ] && [ -e "$work/stopped" ]; then sleep 60; fi
    if [ -e "$work/firstfail" ]; then exit 1; fi
    if [ -e "$work/failaftertap" ] && [ -e "$work/taps" ]; then exit 1; fi
    cat "$work/windows" ;;
  *"am force-stop "*) for a in "$@"; do pkg="$a"; done; echo "$pkg" >> "$work/stopped"
    # The test APK lands during a force-stop; the reads made by then are kept.
    if [ -e "$work/installonstop" ]; then
      echo new > "$work/installed"; wc -l < "$work/reads" > "$work/reads.atstop"
    fi
    [ -e "$work/sticky" ] || { grep -v "Application Not Responding: $pkg}" "$work/windows" > "$work/w2" || true; mv "$work/w2" "$work/windows"; } ;;
  *"uiautomator dump"*)
    # A dump that takes a while, as on a loaded runner (about 8 s on main).
    [ ! -e "$work/slowdump" ] || sleep "$(cat "$work/slowdump")"
    # The test APK lands while the (slow) dump runs.
    [ ! -e "$work/installondump" ] || echo new > "$work/installed"
    [ ! -e "$work/dumphangui" ] || sleep 60
    # An ANR of ours that comes up while the (slow) dump runs.
    if [ -e "$work/oursondump" ]; then
      printf '  Window #8 Window{8 u0 Application Not Responding: org.andashi.home}:\n' >> "$work/windows"
    fi ;;
  *"cat /sdcard/anr-dump.xml"*)
    if grep -q 'Application Not Responding' "$work/windows"; then
      echo '<hierarchy><node resource-id="android:id/aerr_wait" bounds="[75,966][1005,1110]"/></hierarchy>'
    else
      echo '<hierarchy/>'
    fi ;;
  *"input tap "*) echo "$*" >> "$work/taps"
    # The tapped dialog closes this many milliseconds after the tap.
    if [ -e "$work/closedelayms" ]; then
      top="$(grep 'Window #.*Application Not Responding: ' "$work/windows" | tail -1 |
        sed -n 's/.*Application Not Responding: \([A-Za-z0-9_.]*\).*/\1/p')"
      echo "$(( $(date +%s%N) + $(cat "$work/closedelayms") * 1000000 )) $top" > "$work/closeat"
      exit 0
    fi
    [ ! -e "$work/taphang" ] || sleep 5
    if [ ! -e "$work/waitsticky" ]; then
      # The topmost ANR window, not the focus line; it closes on a later read.
      top="$(grep 'Window #.*Application Not Responding: ' "$work/windows" | tail -1 |
        sed -n 's/.*Application Not Responding: \([A-Za-z0-9_.]*\).*/\1/p')"
      [ -z "$top" ] || echo "$top" > "$work/closing"
    fi ;;
  *"cat /proc/uptime"*) echo "123.45 456.78" ;;
  *"pm list packages "*)
    # A device answers both; the wrapper asks one of them.
    [ ! -e "$work/pmfail" ] || exit 1
    [ ! -e "$work/installed" ] || echo "package:de.mm20.launcher2.ui.test" ;;
  *"dumpsys package "*)
    # A start-signal read that takes a while.
    [ ! -e "$work/slowstamp" ] || sleep 3.5
    # $WORK/installed holds the test APK's lastUpdateTime; absent, it is not installed.
    [ ! -e "$work/pmfail" ] || exit 1
    [ ! -e "$work/installed" ] || printf '    lastUpdateTime=%s\n' "$(cat "$work/installed")" ;;
  *) : ;;
esac
EOF
chmod +x "$WORK/bin/adb"
# GNU timeout reads a duration of 0 as no limit at all, so a call given 0
# could hang forever, and one given less than 0 is a time left that went
# stale before it was used: the fake records every such call, and the last
# check requires none (#191 and #196 reviews). It stays out of the per-check reset, so it
# covers every scenario of the suite.
REAL_TIMEOUT="$(command -v timeout)"
cat > "$WORK/bin/timeout" <<EOF2
#!/usr/bin/env bash
case "\$1" in 0|0s|0.0|-*) echo "\$*" >> "$WORK/timeout0" ;; esac
exec "$REAL_TIMEOUT" "\$@"
EOF2
chmod +x "$WORK/bin/timeout"
export ADB_FAKE_WORK="$WORK"
export PATH="$WORK/bin:$PATH"
# The re-check polls; the intervals are the script's own, shortened here so
# the suite stays in seconds. The bound itself is what is under test.
export ANR_RECHECK_SECONDS=3 ANR_RECHECK_SLEEP=0 ANR_WAIT_SECONDS=3 ANR_WATCH_SECONDS=30 ANR_WATCH_SLEEP=1

windows() { # $@ = "Application Not Responding: <pkg>" entries, in z-order
  : > "$WORK/windows"; rm -f "$WORK/stopped" "$WORK/sticky" "$WORK/dumphang" "$WORK/firstfail" "$WORK/taps" "$WORK/waitsticky" "$WORK/closing" "$WORK/closing.seen" "$WORK/oursondump" "$WORK/dumphangui" "$WORK/failaftertap" "$WORK/taphang" "$WORK/installed" "$WORK/installondump" "$WORK/pmfail" "$WORK/reads" "$WORK/slowread" "$WORK/installonread" "$WORK/slowaftertap" "$WORK/anrreads" "$WORK/reads.at2s" "$WORK/slowstamp" "$WORK/installonstop" "$WORK/reads.atstop" "$WORK/slowdump" "$WORK/closedelayms" "$WORK/closeat"
  WAIT_AMBIGUOUS=0
  printf '  Window #1 Window{1 u0 com.example/com.example.Main}:\n' >> "$WORK/windows"
  local i=2 w
  for w in "$@"; do printf '  Window #%s Window{%s u0 %s}:\n' "$i" "$i" "$w" >> "$WORK/windows"; i=$((i + 1)); done
  printf '  mCurrentFocus=Window{9 u0 %s}\n' "${1:-com.example/com.example.Main}" >> "$WORK/windows"
}
stopped() { cat "$WORK/stopped" 2>/dev/null || true; }
taps() { [ -f "$WORK/taps" ] && wc -l < "$WORK/taps" || echo 0; }

# The CI emulator's own launcher ANRs on about one boot in ten, roughly 45 s
# before the first test, and its dialog never goes away by itself (#113).
dismisses_the_stock_launcher() {
  windows "Application Not Responding: com.android.launcher3"
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  [ "$(stopped)" = "com.android.launcher3" ] &&
    ! grep -q 'Application Not Responding: com.android.launcher3' "$WORK/windows"
}
check "a stock-launcher ANR window is dismissed and gone afterwards" dismisses_the_stock_launcher

# The whole point of naming the package: an ANR of ours must reach the tests.
leaves_our_own_alone() {
  windows "Application Not Responding: org.andashi.home"
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  [ -z "$(stopped)" ] && grep -q 'org.andashi.home' "$WORK/log"
}
check "an ANR window of ours is left alone and reported" leaves_our_own_alone

# Any package that is not the app under test is dismissed: its dialog
# carries no signal about us, and it covers any test's window. The CI run
# that prompted this had System UI's (#189).
dismisses_system_ui() {
  windows "Application Not Responding: com.android.systemui"
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  [ "$(stopped)" = "com.android.systemui" ] &&
    ! grep -q 'Application Not Responding: com.android.systemui' "$WORK/windows"
}
check "a System UI ANR window is dismissed and gone afterwards" dismisses_system_ui

# Both directions at once: the foreign one goes, ours stays and is named.
mixed_foreign_and_ours() {
  windows "Application Not Responding: com.android.systemui" "Application Not Responding: de.mm20.launcher2.ui.test"
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  [ "$(stopped)" = "com.android.systemui" ] &&
    grep -q 'Application Not Responding: de.mm20.launcher2.ui.test' "$WORK/windows" &&
    grep -q 'de.mm20.launcher2.ui.test' "$WORK/log"
}
check "with ours and a foreign one on screen, only the foreign one is dismissed" mixed_foreign_and_ours

# Ours is named exactly: a package that only extends one of our names is
# foreign, and a near miss must not be taken for ours.
dismisses_a_lookalike_of_ours() {
  windows "Application Not Responding: org.andashi.home.evil"
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  [ "$(stopped)" = "org.andashi.home.evil" ]
}
check "a package that only looks like ours is dismissed" dismisses_a_lookalike_of_ours

does_nothing_without_an_anr() {
  windows
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  [ -z "$(stopped)" ]
}
check "nothing is stopped when no ANR window is on screen" does_nothing_without_an_anr

# A slow boot can ANR the launcher again right after it restarts. Looping on
# force-stop would spend the job's time; the tests and #162's evidence are
# the honest reporters.
survives_a_dialog_that_comes_back() {
  windows "Application Not Responding: com.android.launcher3"
  : > "$WORK/sticky"; : > "$WORK/waitsticky"
  local start=$SECONDS
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  [ $((SECONDS - start)) -le 10 ] &&
    [ "$(stopped | wc -l)" -eq 1 ] &&
    grep -qi 'still' "$WORK/log"
}
check "a dialog that survives the dismissal is reported once, not looped on" survives_a_dialog_that_comes_back

# A persistent process survives force-stop: System UI did on the device, pid
# unchanged, dialog still up (#189). Its dialog has a Wait button
# (android:id/aerr_wait), and pressing it closes the dialog without killing
# anything; measured on emulator-5558.
waits_out_a_persistent_process() {
  windows "Application Not Responding: com.android.systemui"
  : > "$WORK/sticky"
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  [ "$(stopped)" = "com.android.systemui" ] && [ "$(taps)" -ge 1 ] &&
    ! grep -q 'Application Not Responding' "$WORK/windows"
}
check "a dialog that survives force-stop is closed with its Wait button" waits_out_a_persistent_process

# The Wait button cannot be told apart by package in the dump, so it is only
# pressed while no ANR window of ours is on screen: the one pressed could be ours.
never_waits_while_ours_is_up() {
  windows "Application Not Responding: com.android.systemui" "Application Not Responding: org.andashi.home"
  : > "$WORK/sticky"
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  [ "$(taps)" -eq 0 ] &&
    grep -q 'Application Not Responding: org.andashi.home' "$WORK/windows"
}
check "no Wait button is pressed while an ANR window of ours is up" never_waits_while_ours_is_up

# The Wait button cannot be tied to a package, so it is pressed only when the
# screen is unambiguous: exactly one ANR window, and that one foreign. Two
# survivors are reported and left, not guessed at.
waits_only_on_a_single_dialog() {
  windows "Application Not Responding: com.android.systemui" "Application Not Responding: com.android.phone"
  : > "$WORK/sticky"
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  [ "$(taps)" -eq 0 ] && grep -qi 'still' "$WORK/log"
}
check "no Wait button is pressed while more than one ANR window is up" waits_only_on_a_single_dialog

# What is counted is packages, not windows (#191 review). Two dialogs of one
# foreign package still get Wait: there is nothing of ours under either, and
# a line count would refuse it, because the window list names one dialog on
# more than one line (the window and the focus).
waits_on_two_dialogs_of_one_foreign_package() {
  windows "Application Not Responding: com.android.systemui" "Application Not Responding: com.android.systemui"
  : > "$WORK/sticky"
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  [ "$(taps)" -eq 1 ] && grep -q 'Gone after Wait' "$WORK/log"
}
check "two dialogs of one foreign package get their Wait pressed" waits_on_two_dialogs_of_one_foreign_package

# The window list is read after the slow dump, right before the tap, so an
# ANR of ours that came up during the dump is seen and nothing is pressed. A
# tap meant for a foreign dialog must not land on ours: a test that should
# fail would pass.
sees_ours_arrive_during_the_dump() {
  windows "Application Not Responding: com.android.systemui"
  : > "$WORK/sticky"; : > "$WORK/oursondump"
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  [ "$(taps)" -eq 0 ] &&
    grep -q 'Application Not Responding: org.andashi.home' "$WORK/windows"
}
check "an ANR of ours that comes up during the dump is seen before any tap" sees_ours_arrive_during_the_dump

# The Wait fallback has one wall-clock deadline: a dump that hangs must not
# carry it past ANR_WAIT_SECONDS (#191 review).
wait_fallback_is_wall_clock() {
  windows "Application Not Responding: com.android.systemui"
  : > "$WORK/sticky"; : > "$WORK/dumphangui"
  local start=$SECONDS
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  [ $((SECONDS - start)) -le $((ANR_RECHECK_SECONDS + ANR_WAIT_SECONDS + 2)) ]
}
check "a hanging dump cannot carry the Wait fallback past its deadline" wait_fallback_is_wall_clock

# A tap closes one dialog, the topmost. If an ANR of ours came up just before
# it, the tap closed ours and the foreign one is still there - so a foreign
# dialog that survives its Wait is the one signature under which ours may have
# been dismissed. It is flagged, never passed over (#191 review).
flags_a_wait_that_left_its_dialog() {
  windows "Application Not Responding: com.android.systemui"
  : > "$WORK/sticky"; : > "$WORK/waitsticky"
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  [ "$WAIT_AMBIGUOUS" = 1 ] && grep -q '::error::' "$WORK/log"
}
check "a Wait that leaves its foreign dialog up is flagged" flags_a_wait_that_left_its_dialog

# Control: a Wait that closed its dialog flags nothing.
a_clean_wait_flags_nothing() {
  windows "Application Not Responding: com.android.systemui"
  : > "$WORK/sticky"
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  [ "$WAIT_AMBIGUOUS" = 0 ] && ! grep -q '::error::' "$WORK/log"
}
check "a Wait that closed its dialog flags nothing" a_clean_wait_flags_nothing

# The check after the tap has a bound of its own. On main (2026-09-27) the
# dump took about 8 s of the Wait's 10, the dialog closed 1 s after the tap,
# and the check had less than that left: a clean Wait was flagged. A slow dump
# must not starve the check that follows the tap.
# The timings leave room on a loaded runner and still separate the two
# designs: the dump leaves 2 s of the Wait budget for the read and the tap,
# the dialog closes 3.5 s after the tap - past what a shared budget would
# have left - and the check's own 6 s bound still sees it (#202 review).
a_slow_dump_leaves_the_check_its_time() {
  local ANR_WAIT_SECONDS=6 ANR_RECHECK_SECONDS=6
  windows "Application Not Responding: com.android.systemui"
  : > "$WORK/sticky"
  echo 4 > "$WORK/slowdump"; echo 3500 > "$WORK/closedelayms"
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  [ "$(taps)" -eq 1 ] && [ "$WAIT_AMBIGUOUS" = 0 ] && grep -q 'Gone after Wait' "$WORK/log"
}
check "a slow dump leaves the check after the tap its own time" a_slow_dump_leaves_the_check_its_time

# The check's own bound is a bound: a dialog that never closes is flagged
# within it, however long the dump took.
the_check_after_the_tap_is_bounded() {
  local ANR_WAIT_SECONDS=6 ANR_RECHECK_SECONDS=6
  windows "Application Not Responding: com.android.systemui"
  : > "$WORK/sticky"; : > "$WORK/waitsticky"
  echo 4 > "$WORK/slowdump"
  local start=$SECONDS
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  [ "$WAIT_AMBIGUOUS" = 1 ] &&
    [ $((SECONDS - start)) -le $((ANR_RECHECK_SECONDS + ANR_WAIT_SECONDS + ANR_RECHECK_SECONDS + 2)) ]
}
check "the check after the tap is bounded by its own time" the_check_after_the_tap_is_bounded

# A flag says what it means: the run cannot be vouched for, which is not a
# failure of the launcher.
a_flag_says_what_it_means() {
  windows "Application Not Responding: com.android.systemui"
  : > "$WORK/sticky"; : > "$WORK/waitsticky"
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  grep -q 'not a launcher failure' "$WORK/log"
}
check "a flag says the run is unverified, not that the launcher failed" a_flag_says_what_it_means

# A read that fails after the tap cannot show the foreign dialog gone, so it
# cannot rule out that the tap closed an ANR of ours: flagged like a dialog
# that stayed (#191 review).
flags_a_failed_read_after_the_tap() {
  windows "Application Not Responding: com.android.systemui"
  : > "$WORK/sticky"; : > "$WORK/failaftertap"
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  [ "$(taps)" -eq 1 ] && [ "$WAIT_AMBIGUOUS" = 1 ] && grep -q '::error::' "$WORK/log"
}
check "a window read that fails after the tap is flagged" flags_a_failed_read_after_the_tap

# A tap that uses up the whole deadline leaves no read after it: the loop
# ends on the list read before the tap, which still has the foreign dialog,
# so it is flagged - never passed on to the last read, which follows only a
# tap that a read showed closing its dialog (#191 review).
flags_a_tap_that_used_the_deadline() {
  windows "Application Not Responding: com.android.systemui"
  : > "$WORK/sticky"; : > "$WORK/taphang"
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  [ "$(taps)" -eq 1 ] && [ "$WAIT_AMBIGUOUS" = 1 ] && grep -q '::error::' "$WORK/log"
}
check "a tap that uses up the deadline is flagged" flags_a_tap_that_used_the_deadline

# And the flag reaches the job: a run whose tests pass fails, so a dismissed
# ANR of ours cannot turn into a pass.
a_flagged_wait_fails_a_passing_run() {
  windows "Application Not Responding: com.android.systemui"
  : > "$WORK/sticky"; : > "$WORK/waitsticky"
  local rc=0
  bash ./l2-with-evidence.sh true > "$WORK/run" 2>&1 || rc=$?
  [ "$rc" -ne 0 ] && grep -qi 'may have' "$WORK/run"
}
check "a flagged Wait fails a run whose tests pass" a_flagged_wait_fails_a_passing_run

# --- the watch until the tests start (#190's l2 red) ----------------------
# The stock launcher's ANR lands 45-55 s after boot, when the wrapper starts:
# on #190 the first look ran 0.3 s before the dialog existed, and the tests
# began 137 s later under it. The wrapper keeps looking until the test APK
# is installed, which gradle does right before it instruments, and then
# touches nothing. Each scenario runs the wrapper itself around a command
# that stands in for gradle.
run_wrapper() { # $1 = the command script's body; the wrapper's output goes to $WORK/run
  printf '#!/usr/bin/env bash\nw="$ADB_FAKE_WORK"\n%s\n' "$1" > "$WORK/cmd"
  chmod +x "$WORK/cmd"
  WRAPPER_RC=0
  bash ./l2-with-evidence.sh "$WORK/cmd" > "$WORK/run" 2>&1 || WRAPPER_RC=$?
}
ANR_LAUNCHER3='  Window #7 Window{7 u0 Application Not Responding: com.android.launcher3}:'

# An ANR that comes up after the first look is dismissed before the tests.
dismisses_an_anr_after_the_first_look() {
  windows
  run_wrapper "sleep 1
printf '%s\n' '$ANR_LAUNCHER3' >> \"\$w/windows\"
end=\$((SECONDS + 10))
while [ \"\$SECONDS\" -lt \"\$end\" ]; do
  grep -q launcher3 \"\$w/windows\" || { echo new > \"\$w/installed\"; exit 0; }
  sleep 0.5
done
exit 3"
  [ "$WRAPPER_RC" -eq 0 ] && grep -qx com.android.launcher3 "$WORK/stopped" 2>/dev/null
}
check "an ANR that comes up after the first look is dismissed before the tests" dismisses_an_anr_after_the_first_look

# Once the test APK is installed nothing is touched: no force-stop, no tap,
# and the watch ends - it stops reading, rather than reading on to its
# deadline behind the other guards.
touches_nothing_once_the_tests_start() {
  windows
  run_wrapper "echo new > \"\$w/installed\"
sleep 2
wc -l < \"\$w/reads\" > \"\$w/reads.at2s\"
printf '%s\n' '$ANR_LAUNCHER3' >> \"\$w/windows\"
sleep 3"
  [ "$WRAPPER_RC" -eq 0 ] && [ -z "$(stopped)" ] && [ "$(taps)" -eq 0 ] &&
    [ "$(wc -l < "$WORK/reads")" -le "$(cat "$WORK/reads.at2s")" ]
}
check "nothing is touched once the test APK is installed, and the watch ends" touches_nothing_once_the_tests_start

# A device that cannot say whether the tests started is taken as started:
# a watch that kept going into the tests could tap one.
an_unreadable_device_ends_the_watch() {
  windows
  run_wrapper ": > \"\$w/pmfail\"
sleep 2
printf '%s\n' '$ANR_LAUNCHER3' >> \"\$w/windows\"
sleep 3"
  [ "$WRAPPER_RC" -eq 0 ] && [ -z "$(stopped)" ]
}
check "a device that cannot say whether the tests started ends the watch" an_unreadable_device_ends_the_watch

# The watch ends with the command: the wrapper returns with it, not at the
# watch's deadline, and nothing is read after it has returned.
the_watch_ends_with_the_command() {
  windows
  local start=$SECONDS after
  run_wrapper "sleep 2"
  [ $((SECONDS - start)) -le 5 ] || return 1
  after="$(wc -l < "$WORK/reads")"
  sleep 3
  [ "$WRAPPER_RC" -eq 0 ] && [ "$(wc -l < "$WORK/reads")" -eq "$after" ]
}
check "the watch ends when the command does, and reads nothing after" the_watch_ends_with_the_command

# And it has a deadline of its own: past ANR_WATCH_SECONDS it touches nothing.
the_watch_has_a_deadline() {
  windows
  ANR_WATCH_SECONDS=2 run_wrapper "sleep 4
printf '%s\n' '$ANR_LAUNCHER3' >> \"\$w/windows\"
sleep 3"
  [ "$WRAPPER_RC" -eq 0 ] && [ -z "$(stopped)" ]
}
check "the watch touches nothing past its deadline" the_watch_has_a_deadline

# A Wait in the watch that leaves its dialog up fails a passing run, as one
# in the first look does: the flag crosses from the watch to the wrapper.
# And a dialog that survives is reported once, not retried every round.
run_a_flagged_wait_in_the_watch() {
  windows
  # Not a fixed delay (#196 review): the command waits for the tap it is
  # there to see, then for the Wait's own budget, which bounds the check
  # after the tap, then three rounds of the watch for the retry check below.
  run_wrapper "sleep 1
: > \"\$w/sticky\"; : > \"\$w/waitsticky\"
printf '%s\n' '  Window #7 Window{7 u0 Application Not Responding: com.android.systemui}:' >> \"\$w/windows\"
end=\$((SECONDS + 20))
while [ ! -e \"\$w/taps\" ] && [ \"\$SECONDS\" -lt \"\$end\" ]; do sleep 0.2; done
[ -e \"\$w/taps\" ] || exit 3
sleep \$((ANR_WAIT_SECONDS + 1 + 3 * ANR_WATCH_SLEEP))"
}
a_flagged_wait_in_the_watch_fails_a_passing_run() {
  run_a_flagged_wait_in_the_watch
  [ "$WRAPPER_RC" -ne 0 ] && grep -qi 'may have' "$WORK/run"
}
check "a flagged Wait in the watch fails a run whose tests pass" a_flagged_wait_in_the_watch_fails_a_passing_run
a_survivor_is_not_retried_every_round() {
  # Reads the run above: it watched for about ten more seconds after the Wait.
  [ "$(grep -cx com.android.systemui "$WORK/stopped")" -eq 1 ] && [ "$(taps)" -eq 1 ]
}
check "a dialog that survives the watch's dismissal is not retried every round" a_survivor_is_not_retried_every_round

# The last look before a tap: if the test APK arrived during the dump, no tap.
no_tap_once_the_tests_start_during_the_dump() {
  # Inside the watch: the test APK was not installed when the wrapper started.
  local APK_STAMP_AT_START=""
  windows "Application Not Responding: com.android.systemui"
  : > "$WORK/sticky"; : > "$WORK/installondump"
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  [ "$(taps)" -eq 0 ] && grep -qi 'test' "$WORK/log"
}
check "no Wait is pressed once the tests start during the dump" no_tap_once_the_tests_start_during_the_dump

# A test APK already installed when the wrapper starts is not the start of
# the tests: what counts is gradle installing it, which changes its
# lastUpdateTime (#196 review).
a_preinstalled_apk_does_not_end_the_watch() {
  windows
  echo old > "$WORK/installed"
  run_wrapper "sleep 1
printf '%s\n' '$ANR_LAUNCHER3' >> \"\$w/windows\"
end=\$((SECONDS + 10))
while [ \"\$SECONDS\" -lt \"\$end\" ]; do
  grep -q launcher3 \"\$w/windows\" || { echo new > \"\$w/installed\"; exit 0; }
  sleep 0.5
done
exit 3"
  [ "$WRAPPER_RC" -eq 0 ] && grep -qx com.android.launcher3 "$WORK/stopped" 2>/dev/null
}
check "a test APK installed before the wrapper does not end the watch" a_preinstalled_apk_does_not_end_the_watch

# A read that runs past the watch's deadline starts no dismissal: the read is
# given only the time left, and the deadline is checked again before acting
# (#196 review).
a_read_past_the_deadline_starts_nothing() {
  windows
  ANR_WATCH_SECONDS=3 run_wrapper ": > \"\$w/slowread\"
sleep 1.5
printf '%s\n' '$ANR_LAUNCHER3' >> \"\$w/windows\"
sleep 7"
  [ "$WRAPPER_RC" -eq 0 ] && [ -z "$(stopped)" ]
}
check "a read that runs past the watch's deadline starts no dismissal" a_read_past_the_deadline_starts_nothing

# The test APK can land while the window list is read: no force-stop then
# either (#196 review).
no_force_stop_once_the_tests_start_during_the_read() {
  windows
  run_wrapper "echo 1 > \"\$w/installonread\"
sleep 1
printf '%s\n' '$ANR_LAUNCHER3' >> \"\$w/windows\"
sleep 5"
  [ "$WRAPPER_RC" -eq 0 ] && [ -z "$(stopped)" ]
}
check "no force-stop once the tests start during the read" no_force_stop_once_the_tests_start_during_the_read

# The same during the dismissal's own read, after the watch has looked: the
# dismissal checks for itself before it force-stops anything.
no_force_stop_once_the_tests_start_during_the_dismissals_read() {
  windows
  run_wrapper "echo 2 > \"\$w/installonread\"
sleep 1
printf '%s\n' '$ANR_LAUNCHER3' >> \"\$w/windows\"
sleep 5"
  [ "$WRAPPER_RC" -eq 0 ] && [ -z "$(stopped)" ] && grep -q 'tests are starting' "$WORK/run"
}
check "no force-stop once the tests start during the dismissal's own read" no_force_stop_once_the_tests_start_during_the_dismissals_read

# Control: a Wait in the watch that closes its dialog leaves a passing run
# passing - the in-flight mark is cleared once the dialog is seen gone.
a_clean_wait_in_the_watch_passes() {
  windows
  run_wrapper "sleep 1
: > \"\$w/sticky\"
printf '%s\n' '  Window #7 Window{7 u0 Application Not Responding: com.android.systemui}:' >> \"\$w/windows\"
end=\$((SECONDS + 20))
while grep -q systemui \"\$w/windows\" && [ \"\$SECONDS\" -lt \"\$end\" ]; do sleep 0.2; done
sleep \$((2 * ANR_WATCH_SLEEP))
! grep -q systemui \"\$w/windows\""
  [ "$WRAPPER_RC" -eq 0 ] && [ "$(taps)" -eq 1 ] && ! grep -q '::error::' "$WORK/run"
}
check "a Wait in the watch that closes its dialog leaves a passing run passing" a_clean_wait_in_the_watch_passes

# A Wait still being checked when the command ends has not shown that its
# tap closed the foreign dialog: killing the watch then must not read as a
# clean Wait (#196 review).
a_wait_cut_short_fails_a_passing_run() {
  windows
  run_wrapper "sleep 1
: > \"\$w/sticky\"; : > \"\$w/slowaftertap\"
printf '%s\n' '  Window #7 Window{7 u0 Application Not Responding: com.android.systemui}:' >> \"\$w/windows\"
end=\$((SECONDS + 20))
while [ ! -e \"\$w/taps\" ] && [ \"\$SECONDS\" -lt \"\$end\" ]; do sleep 0.2; done
[ -e \"\$w/taps\" ] || exit 3"
  [ "$WRAPPER_RC" -ne 0 ] && grep -q '::error::' "$WORK/run"
}
check "a Wait still being checked when the command ends fails a passing run" a_wait_cut_short_fails_a_passing_run

# A flagged Wait stays flagged when a later round's Wait comes out clean:
# the later one clears its own in-flight mark, not the earlier verdict.
a_flag_survives_a_later_clean_wait() {
  windows
  run_wrapper "sleep 1
: > \"\$w/sticky\"; : > \"\$w/waitsticky\"
printf '%s\n' '  Window #7 Window{7 u0 Application Not Responding: com.android.systemui}:' >> \"\$w/windows\"
end=\$((SECONDS + 20))
while [ ! -e \"\$w/taps\" ] && [ \"\$SECONDS\" -lt \"\$end\" ]; do sleep 0.2; done
[ -e \"\$w/taps\" ] || exit 3
sleep \$((ANR_WAIT_SECONDS + 1))
grep -v systemui \"\$w/windows\" > \"\$w/w3\" || true; mv \"\$w/w3\" \"\$w/windows\"; rm -f \"\$w/waitsticky\"
printf '%s\n' '  Window #8 Window{8 u0 Application Not Responding: com.android.phone}:' >> \"\$w/windows\"
end=\$((SECONDS + 20))
while grep -q com.android.phone \"\$w/windows\" && [ \"\$SECONDS\" -lt \"\$end\" ]; do sleep 0.2; done
! grep -q com.android.phone \"\$w/windows\" || exit 4
sleep \$((2 * ANR_WATCH_SLEEP))"
  [ "$WRAPPER_RC" -ne 0 ] && [ "$(taps)" -eq 2 ] && grep -qi 'may have' "$WORK/run"
}
check "a flagged Wait stays flagged after a later clean one" a_flag_survives_a_later_clean_wait

# The start check is an adb call of its own. When it outlasts the watch's
# deadline, the time left read after it is 0 or less, and a read handed that
# would run unbounded (0) or not at all (below 0): the watch stops instead.
a_slow_start_check_leaves_no_stale_time() {
  windows
  # Counted, not cleared: the suite-wide check at the end reads the same file.
  local before; before="$(cat "$WORK/timeout0" 2>/dev/null | wc -l)"
  : > "$WORK/slowstamp"
  ANR_WATCH_SECONDS=3 run_wrapper "sleep 6"
  rm -f "$WORK/slowstamp"
  [ "$WRAPPER_RC" -eq 0 ] && [ "$(cat "$WORK/timeout0" 2>/dev/null | wc -l)" -eq "$before" ]
}
check "a start check that outlasts the deadline hands no read a stale time" a_slow_start_check_leaves_no_stale_time

# With two foreign dialogs, the test APK can land during the first
# force-stop: the second is not stopped then (#196 review).
the_second_force_stop_waits_for_nothing() {
  local APK_STAMP_AT_START=""
  windows "Application Not Responding: com.android.launcher3" "Application Not Responding: com.android.phone"
  : > "$WORK/installonstop"
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  [ "$(stopped)" = "com.android.launcher3" ] && grep -q 'tests are starting' "$WORK/log"
}
check "no second force-stop once the tests start during the first" the_second_force_stop_waits_for_nothing

# After a dismissal the watch asks again whether the tests have started
# before it reads the screen once more: a dismissal takes seconds, and a
# watch past the start has nothing left to read (#196 review). The
# dismissal's own re-check reads once after the force-stop; the watch adds
# nothing.
the_watch_reads_nothing_after_a_dismissal_the_tests_began_in() {
  windows
  run_wrapper "sleep 1
: > \"\$w/installonstop\"
printf '%s\n' '$ANR_LAUNCHER3' >> \"\$w/windows\"
sleep 4"
  [ "$WRAPPER_RC" -eq 0 ] && [ -e "$WORK/reads.atstop" ] &&
    [ "$(wc -l < "$WORK/reads")" -eq $(( $(cat "$WORK/reads.atstop") + 1 )) ]
}
check "the watch reads nothing more after a dismissal the tests began in" the_watch_reads_nothing_after_a_dismissal_the_tests_began_in

# The re-check is wall-clock time (#164). "5 tries, 1 s apart" read as five
# seconds, but each try is a dumpsys over adb with no bound, so on a loaded
# runner it lasted however long five of them took. A read that hangs must
# not carry the re-check past ANR_RECHECK_SECONDS, and it must still end in
# "running the tests anyway", never in a failed job.
recheck_is_wall_clock() {
  windows "Application Not Responding: com.android.launcher3"
  : > "$WORK/sticky"; : > "$WORK/dumphang"
  local start=$SECONDS
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  [ $((SECONDS - start)) -le $((ANR_RECHECK_SECONDS + 3)) ]
}
check "a hanging window read cannot carry the re-check past its deadline" recheck_is_wall_clock

# The pause between reads is capped by what is left: a sleep longer than the
# deadline must not start a read after it (#179 review). A dialog that
# survives force-stop gets a second bounded re-check after its Wait (#189),
# so the step as a whole takes at most two of them; uncapped, a 5 s pause
# would carry each past its 3 s deadline, 10 s in all.
sleep_is_capped_by_the_deadline() {
  windows "Application Not Responding: com.android.launcher3"
  : > "$WORK/sticky"; : > "$WORK/waitsticky"
  local start=$SECONDS
  ANR_RECHECK_SECONDS=3 ANR_RECHECK_SLEEP=5 clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  [ $((SECONDS - start)) -le $((2 * 3 + 1)) ]
}
check "the re-check's pause cannot carry it past its deadline" sleep_is_capped_by_the_deadline

# A first read of the window list that fails is said out loud, never taken
# for "no ANR window": the dialog could still be there (#179 review).
a_failed_first_read_is_reported() {
  windows "Application Not Responding: com.android.launcher3"
  : > "$WORK/firstfail"
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  grep -qi "could not read" "$WORK/log"
}
check "a failed first window read is reported, not taken for no ANR" a_failed_first_read_is_reported

# "Named exactly, never a pattern" has to be true of the match as well: a
# regex reads every period as a wildcard, so a package that differs from
# ours only in those characters must not be taken for ours.
dismisses_a_regex_near_miss_of_ours() {
  windows "Application Not Responding: orgXandashiXhome"
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  [ "$(stopped)" = "orgXandashiXhome" ]
}
check "a package matching ours only as a regex is dismissed" dismisses_a_regex_near_miss_of_ours

# The re-check reads the window list again. If that read fails, an empty
# result must not read as "no ANR window": claiming the dialog is gone
# without having seen the screen is the silent direction.
does_not_claim_gone_when_the_read_fails() {
  windows "Application Not Responding: com.android.launcher3"
  : > "$WORK/sticky"; : > "$WORK/dumpfail"
  clear_foreign_anrs > "$WORK/log" 2>&1 || return 1
  rm -f "$WORK/dumpfail"
  # Not "gone" as a word - the message for an unreadable screen says the
  # dialog is not being claimed gone, and contains it. The success line is
  # what must be absent.
  ! grep -qx 'Gone\.' "$WORK/log" && grep -q 'Could not read' "$WORK/log"
}
check "a failed window read is not reported as the dialog being gone" does_not_claim_gone_when_the_read_fails

# No timeout in the suite was given 0, which GNU timeout reads as no limit.
no_timeout_was_unbounded() { [ ! -s "$WORK/timeout0" ] || { cat "$WORK/timeout0" >&2; return 1; }; }
check "no timeout was given 0, which means no limit" no_timeout_was_unbounded

# A guard that reads the time left and a call that reads it again can see
# two different values: a tick between them hands the call 0. That tick is
# too narrow to force with a fake, so the property is held by structure -
# the Wait fallback reads what is left once per step and passes that value
# on - and this checks the structure (#191 review).
reads_the_time_left_once_per_step() {
  ! grep -nE '(timeout|anr_packages) "\$\(wleft\)"' ./l2-with-evidence.sh
}
check "the Wait fallback passes the time left it checked, not a second reading" reads_the_time_left_once_per_step

printf '%s of %s checks passed\n' "$passed" "$total"
[ "$passed" -eq "$total" ] || { printf 'FAILED\n'; exit 1; }
