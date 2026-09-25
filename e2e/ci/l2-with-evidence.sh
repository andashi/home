#!/usr/bin/env bash
# Runs an L2 command on the CI emulator; when it fails, prints what was on
# screen and what crashed or stopped responding, while the emulator is
# still up (#113).
#
#   e2e/ci/l2-with-evidence.sh ./gradlew :app:ui:connectedDebugAndroidTest
#
# A screen-sampling test that meets a surface in front of its window fails
# with pixels, not a name. The window list (z-order, focus) and the ANR and
# crash lines name that surface, whichever test it lands on. Nothing is
# dismissed or suppressed here: a dialog of the launcher's own must stay as
# visible as any other.
set -uo pipefail

"$@"
status=$?
[ "$status" -eq 0 ] && exit 0

echo "::group::#113 evidence: windows on screen"
adb shell dumpsys window windows | grep -E '^\s*(Window #|mCurrentFocus|mFocusedApp)' || true
adb shell dumpsys window | grep -E '^\s*(mCurrentFocus|mFocusedApp)' || true
echo "::endgroup::"
echo "::group::#113 evidence: ANRs and crashes (logcat)"
# The last lines only: a process stuck in an ANR loop repeats every few
# seconds for as long as the emulator runs.
adb logcat -d -b main,system,crash,events |
  grep -E 'ANR in|FATAL EXCEPTION|am_anr|am_crash|Application Not Responding|isn.t responding' |
  tail -n 80 || true
echo "::endgroup::"
exit "$status"
