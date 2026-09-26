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
    cat "$work/windows" ;;
  *"am force-stop "*) for a in "$@"; do pkg="$a"; done; echo "$pkg" >> "$work/stopped"
    [ -e "$work/sticky" ] || { grep -v "Application Not Responding: $pkg}" "$work/windows" > "$work/w2" || true; mv "$work/w2" "$work/windows"; } ;;
  *"uiautomator dump"*) : ;;
  *"cat /sdcard/anr-dump.xml"*)
    if grep -q 'Application Not Responding' "$work/windows"; then
      echo '<hierarchy><node resource-id="android:id/aerr_wait" bounds="[75,966][1005,1110]"/></hierarchy>'
    else
      echo '<hierarchy/>'
    fi ;;
  *"input tap "*) echo "$*" >> "$work/taps"
    if [ ! -e "$work/waitsticky" ]; then
      # The topmost ANR window, not the focus line; it closes on a later read.
      top="$(grep 'Window #.*Application Not Responding: ' "$work/windows" | tail -1 |
        sed -n 's/.*Application Not Responding: \([A-Za-z0-9_.]*\).*/\1/p')"
      [ -z "$top" ] || echo "$top" > "$work/closing"
    fi ;;
  *"cat /proc/uptime"*) echo "123.45 456.78" ;;
  *) : ;;
esac
EOF
chmod +x "$WORK/bin/adb"
export ADB_FAKE_WORK="$WORK"
export PATH="$WORK/bin:$PATH"
# The re-check polls; the intervals are the script's own, shortened here so
# the suite stays in seconds. The bound itself is what is under test.
export ANR_RECHECK_SECONDS=3 ANR_RECHECK_SLEEP=0

windows() { # $@ = "Application Not Responding: <pkg>" entries, in z-order
  : > "$WORK/windows"; rm -f "$WORK/stopped" "$WORK/sticky" "$WORK/dumphang" "$WORK/firstfail" "$WORK/taps" "$WORK/waitsticky" "$WORK/closing" "$WORK/closing.seen"
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

printf '%s of %s checks passed\n' "$passed" "$total"
[ "$passed" -eq "$total" ] || { printf 'FAILED\n'; exit 1; }
