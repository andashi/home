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
# removes that package's ANR window, as a real force-stop does.
mkdir -p "$WORK/bin"
cat > "$WORK/bin/adb" <<'EOF'
#!/usr/bin/env bash
work="$ADB_FAKE_WORK"
case "$*" in
  *"dumpsys window windows"*)
    if [ -e "$work/dumpfail" ] && [ -e "$work/stopped" ]; then exit 1; fi
    if [ -e "$work/dumphang" ] && [ -e "$work/stopped" ]; then sleep 60; fi
    if [ -e "$work/firstfail" ]; then exit 1; fi
    cat "$work/windows" ;;
  *"am force-stop "*) for a in "$@"; do pkg="$a"; done; echo "$pkg" >> "$work/stopped"
    [ -e "$work/sticky" ] || { grep -v "Application Not Responding: $pkg}" "$work/windows" > "$work/w2" || true; mv "$work/w2" "$work/windows"; } ;;
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
  : > "$WORK/windows"; rm -f "$WORK/stopped" "$WORK/sticky" "$WORK/dumphang" "$WORK/firstfail"
  printf '  Window #1 Window{1 u0 com.example/com.example.Main}:\n' >> "$WORK/windows"
  local i=2 w
  for w in "$@"; do printf '  Window #%s Window{%s u0 %s}:\n' "$i" "$i" "$w" >> "$WORK/windows"; i=$((i + 1)); done
  printf '  mCurrentFocus=Window{9 u0 %s}\n' "${1:-com.example/com.example.Main}" >> "$WORK/windows"
}
stopped() { cat "$WORK/stopped" 2>/dev/null || true; }

# The CI emulator's own launcher ANRs on about one boot in ten, roughly 45 s
# before the first test, and its dialog never goes away by itself (#113).
dismisses_the_stock_launcher() {
  windows "Application Not Responding: com.android.launcher3"
  clear_stock_launcher_anr > "$WORK/log" 2>&1 || return 1
  [ "$(stopped)" = "com.android.launcher3" ] &&
    ! grep -q 'Application Not Responding: com.android.launcher3' "$WORK/windows"
}
check "a stock-launcher ANR window is dismissed and gone afterwards" dismisses_the_stock_launcher

# The whole point of naming the package: an ANR of ours must reach the tests.
leaves_our_own_alone() {
  windows "Application Not Responding: org.andashi.home"
  clear_stock_launcher_anr > "$WORK/log" 2>&1 || return 1
  [ -z "$(stopped)" ] && grep -q 'org.andashi.home' "$WORK/log"
}
check "an ANR window of ours is left alone and reported" leaves_our_own_alone

leaves_a_third_party_alone() {
  windows "Application Not Responding: com.android.launcher3.evil"
  clear_stock_launcher_anr > "$WORK/log" 2>&1 || return 1
  [ -z "$(stopped)" ]
}
check "a package that only looks like the stock launcher is left alone" leaves_a_third_party_alone

does_nothing_without_an_anr() {
  windows
  clear_stock_launcher_anr > "$WORK/log" 2>&1 || return 1
  [ -z "$(stopped)" ]
}
check "nothing is stopped when no ANR window is on screen" does_nothing_without_an_anr

# A slow boot can ANR the launcher again right after it restarts. Looping on
# force-stop would spend the job's time; the tests and #162's evidence are
# the honest reporters.
survives_a_dialog_that_comes_back() {
  windows "Application Not Responding: com.android.launcher3"
  : > "$WORK/sticky"
  local start=$SECONDS
  clear_stock_launcher_anr > "$WORK/log" 2>&1 || return 1
  [ $((SECONDS - start)) -le 10 ] &&
    [ "$(stopped | wc -l)" -eq 1 ] &&
    grep -qi 'still' "$WORK/log"
}
check "a dialog that survives the dismissal is reported once, not looped on" survives_a_dialog_that_comes_back

# The re-check is wall-clock time (#164). "5 tries, 1 s apart" read as five
# seconds, but each try is a dumpsys over adb with no bound, so on a loaded
# runner it lasted however long five of them took. A read that hangs must
# not carry the re-check past ANR_RECHECK_SECONDS, and it must still end in
# "running the tests anyway", never in a failed job.
recheck_is_wall_clock() {
  windows "Application Not Responding: com.android.launcher3"
  : > "$WORK/sticky"; : > "$WORK/dumphang"
  local start=$SECONDS
  clear_stock_launcher_anr > "$WORK/log" 2>&1 || return 1
  [ $((SECONDS - start)) -le $((ANR_RECHECK_SECONDS + 3)) ]
}
check "a hanging window read cannot carry the re-check past its deadline" recheck_is_wall_clock

# The pause between reads is capped by what is left: a sleep longer than the
# deadline must not start a read after it (#179 review).
sleep_is_capped_by_the_deadline() {
  windows "Application Not Responding: com.android.launcher3"
  : > "$WORK/sticky"
  local start=$SECONDS
  ANR_RECHECK_SECONDS=3 ANR_RECHECK_SLEEP=5 clear_stock_launcher_anr > "$WORK/log" 2>&1 || return 1
  [ $((SECONDS - start)) -le 4 ]
}
check "the re-check's pause cannot carry it past its deadline" sleep_is_capped_by_the_deadline

# A first read of the window list that fails is said out loud, never taken
# for "no ANR window": the dialog could still be there (#179 review).
a_failed_first_read_is_reported() {
  windows "Application Not Responding: com.android.launcher3"
  : > "$WORK/firstfail"
  clear_stock_launcher_anr > "$WORK/log" 2>&1 || return 1
  grep -qi "could not read" "$WORK/log"
}
check "a failed first window read is reported, not taken for no ANR" a_failed_first_read_is_reported

# The count, not the colour: a suite that reports only its passes hides the
# checks that never ran (AGENTS.md, test policy).
# "Named exactly, never a pattern" has to be true of the match as well:
# grep -x still reads the package as a regex, so every period is a wildcard
# and a package that differs only in those characters would force-stop the
# real launcher.
leaves_a_regex_near_miss_alone() {
  windows "Application Not Responding: comXandroidXlauncher3"
  clear_stock_launcher_anr > "$WORK/log" 2>&1 || return 1
  [ -z "$(stopped)" ]
}
check "a package matching only as a regex is left alone" leaves_a_regex_near_miss_alone

# The re-check reads the window list again. If that read fails, an empty
# result must not read as "no ANR window": claiming the dialog is gone
# without having seen the screen is the silent direction.
does_not_claim_gone_when_the_read_fails() {
  windows "Application Not Responding: com.android.launcher3"
  : > "$WORK/sticky"; : > "$WORK/dumpfail"
  clear_stock_launcher_anr > "$WORK/log" 2>&1 || return 1
  rm -f "$WORK/dumpfail"
  # Not "gone" as a word - the message for an unreadable screen says the
  # dialog is not being claimed gone, and contains it. The success line is
  # what must be absent.
  ! grep -qx 'Gone\.' "$WORK/log" && grep -q 'Could not read' "$WORK/log"
}
check "a failed window read is not reported as the dialog being gone" does_not_claim_gone_when_the_read_fails

printf '%s of %s checks passed\n' "$passed" "$total"
[ "$passed" -eq "$total" ] || { printf 'FAILED\n'; exit 1; }
