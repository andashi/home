#!/usr/bin/env bash
# Runs an L2 command on the CI emulator; when it fails, prints what was on
# screen, what crashed or stopped responding, and when, while the emulator
# is still up (#113).
#
#   e2e/ci/l2-with-evidence.sh ./gradlew :app:ui:connectedDebugAndroidTest
#
# A screen-sampling test that meets a surface in front of its window fails
# with pixels, not a name. The window list (z-order, focus) and the ANR and
# crash lines name that surface, whichever test it lands on. Every logcat
# line is stamped in seconds since boot, and so are the tests' starts and
# failures, which is what tells whether the surface comes at a fixed time
# after boot. Nothing is dismissed or suppressed here: a dialog of the
# launcher's own must stay as visible as any other.
set -uo pipefail

# Sourcing this file defines its functions and runs nothing, so
# l2-with-evidence.test.sh can exercise them with a fake adb. Without the
# guard, sourcing runs the body, `"$@"` expands to nothing, and the `exit 0`
# below ends the test file before a single check runs - silently, and
# looking exactly like a passing suite.

# The CI emulator's own launcher ANRs on roughly one boot in ten, about 45 s
# before the first test, and the "Application Not Responding" dialog it
# leaves never goes away on its own. It sits above everything with a dim
# layer, so every screen-sampling test whose sample falls under it fails on
# pixels rather than on a name (#113).
#
# Only that one package is touched, named exactly and never as a pattern. An
# ANR of the launcher under test, or of anything else, is reported here and
# left where it is: the tests must see it, and the evidence below names it.
#
# An ANR that fires after this check - a slower boot - still lands on a test,
# and the post-failure dump names it there instead. That is the right
# failure mode: this removes a known obstruction, it does not promise a clear
# screen.
STOCK_HOME=com.android.launcher3
: "${ANR_RECHECK_TRIES:=5}" "${ANR_RECHECK_SLEEP:=1}"

anr_packages() { # one package per "Application Not Responding" window on screen
  adb shell dumpsys window windows |
    sed -n 's/.*Application Not Responding: \([A-Za-z0-9_.]*\).*/\1/p' | sort -u
}

clear_stock_launcher_anr() {
  local pkgs; pkgs="$(anr_packages)"
  [ -n "$pkgs" ] || return 0
  printf '::group::#113 ANR windows on screen before the tests\n%s\n::endgroup::\n' "$pkgs"
  if ! printf '%s\n' "$pkgs" | grep -qx "$STOCK_HOME"; then
    printf 'Leaving them: none is %s. The tests will show what they cover.\n' "$STOCK_HOME"
    return 0
  fi
  printf 'Dismissing the stock launcher ANR dialog (%s) so it cannot cover a test.\n' "$STOCK_HOME"
  adb shell am force-stop "$STOCK_HOME"
  local i=0
  while [ "$i" -lt "$ANR_RECHECK_TRIES" ]; do
    [ "$ANR_RECHECK_SLEEP" = 0 ] || sleep "$ANR_RECHECK_SLEEP"
    printf '%s\n' "$(anr_packages)" | grep -qx "$STOCK_HOME" || { printf 'Gone.\n'; return 0; }
    i=$((i + 1))
  done
  # HOME restarts after a force-stop and can ANR again on a slow boot.
  # Saying so once is worth more than looping: the tests and the evidence
  # below are the honest reporters, and a second attempt would only spend
  # the job's time.
  printf 'Still there after the dismissal; leaving it and running the tests anyway.\n'
  return 0
}

[ "${BASH_SOURCE[0]}" = "$0" ] || return 0

clear_stock_launcher_anr

"$@"
status=$?
[ "$status" -eq 0 ] && exit 0

echo "::group::#113 evidence: windows on screen at $(adb shell cat /proc/uptime | cut -d' ' -f1) s since boot"
adb shell dumpsys window windows | grep -E '^\s*(Window #|mCurrentFocus|mFocusedApp)' || true
adb shell dumpsys window | grep -E '^\s*(mCurrentFocus|mFocusedApp)' || true
echo "::endgroup::"
echo "::group::#113 evidence: ANRs and crashes (logcat, seconds since boot)"
# The last lines only: a process stuck in an ANR loop repeats every few
# seconds for as long as the emulator runs.
adb logcat -d -v monotonic -b main,system,crash,events |
  grep -E 'ANR in|FATAL EXCEPTION|am_anr|am_crash|Application Not Responding|isn.t responding' |
  tail -n 80 || true
echo "::endgroup::"
echo "::group::#113 evidence: test starts and failures (logcat, seconds since boot)"
adb logcat -d -v monotonic -s TestRunner:I | grep -E 'started:|failed:' | tail -n 200 || true
echo "::endgroup::"
exit "$status"
