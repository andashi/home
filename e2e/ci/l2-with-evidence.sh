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
