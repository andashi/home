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
# after boot. Before the run, the ANR dialogs of every package but the app
# under test are dismissed; the app under test's own stays as visible as any
# failure, and the evidence printed on failure is what names it.
set -uo pipefail

# Sourcing this file defines its functions and runs nothing, so
# l2-with-evidence.test.sh can exercise them with a fake adb. Without the
# guard, sourcing runs the body, `"$@"` expands to nothing, and the `exit 0`
# below ends the test file before a single check runs - silently, and
# looking exactly like a passing suite.

# An "Application Not Responding" dialog never goes away on its own. It sits
# above everything with a dim layer, so every screen-sampling test whose
# sample falls under it fails on pixels rather than on a name (#113). The
# CI emulator's own launcher ANRs on roughly one boot in ten, about 45 s
# before the first test; #189 met one of System UI's.
#
# The rule: an ANR of the app under test is reported and left where it is,
# because the tests must see it and the evidence below names it. Any other
# package's is dismissed: its dialog carries no signal about us and covers
# any test's window. The first version dismissed only the stock launcher and
# left everything else, which drew the line one case short (#189).
#
# Ours is named exactly and never as a pattern: a package that only looks
# like one of ours, or matches one only as a regex, is foreign.
#
# An ANR that fires after this check - a slower boot - still lands on a test,
# and the post-failure dump names it there instead. That is the right
# failure mode: this removes a known obstruction, it does not promise a clear
# screen.
#
# The app under test: the instrumentation package of :app:ui's tests, and the
# launcher's own ids. A caller running another app's tests sets it.
: "${APP_UNDER_TEST:=de.mm20.launcher2.ui.test org.andashi.home org.andashi.home.debug}"
# The re-check after the dismissal is wall-clock time (#164): "5 tries, 1 s
# apart" read as five seconds, but each try is a dumpsys over adb, and on a
# loaded runner five unbounded dumpsys calls take however long they take.
# Self-contained on purpose: e2e/ci/ runs on a bare runner, without the
# device library.
: "${ANR_RECHECK_SECONDS:=5}" "${ANR_RECHECK_SLEEP:=1}"

anr_packages() { # [$1 = seconds for the read, default 10] one package per ANR window on screen
  timeout "${1:-10}" adb shell dumpsys window windows |
    sed -n 's/.*Application Not Responding: \([A-Za-z0-9_.]*\).*/\1/p' | sort -u
}

is_ours() { # $1 = package; exact string equality, never a pattern
  local p
  for p in $APP_UNDER_TEST; do [ "$1" = "$p" ] && return 0; done
  return 1
}

clear_foreign_anrs() {
  local pkgs
  # Split from the declaration, and checked: a read that failed or timed out
  # leaves the list empty, and an empty list must not read as "no ANR
  # window" - the dialog could still be there (#179 review). Say so and run
  # the tests; this step never fails the job.
  if ! pkgs="$(anr_packages)"; then
    printf 'Could not read the window list before the tests; not claiming there is no ANR dialog.\n'
    return 0
  fi
  [ -n "$pkgs" ] || return 0
  printf '::group::#113 ANR windows on screen before the tests\n%s\n::endgroup::\n' "$pkgs"
  local pkg foreign=()
  while IFS= read -r pkg; do
    if is_ours "$pkg"; then
      printf 'Leaving %s: it is the app under test, and the tests must see it.\n' "$pkg"
    else
      foreign+=("$pkg")
    fi
  done <<<"$pkgs"
  [ "${#foreign[@]}" -gt 0 ] || return 0
  for pkg in "${foreign[@]}"; do
    printf 'Dismissing the ANR dialog of %s, which is not the app under test, so it cannot cover a test.\n' "$pkg"
    adb shell am force-stop "$pkg"
  done
  local deadline=$((SECONDS + ANR_RECHECK_SECONDS)) after left
  # Bounded by `deadline`; each read gets what is left of it.
  while [ "$SECONDS" -lt "$deadline" ]; do
    # The pause is capped by what is left, and the time is checked again
    # after it: a read never starts past the deadline (#179 review).
    left=$((deadline - SECONDS))
    [ "$ANR_RECHECK_SLEEP" = 0 ] || sleep "$(( ANR_RECHECK_SLEEP < left ? ANR_RECHECK_SLEEP : left ))"
    [ "$SECONDS" -lt "$deadline" ] || break
    # Split from the declaration: `local after=$(...)` reports the
    # declaration's status, not the read's, and a screen that could not be
    # read would then look like an empty one.
    if ! after="$(anr_packages "$(( deadline - SECONDS > 0 ? deadline - SECONDS : 1 ))")"; then
      printf 'Could not read the window list; not claiming the dialogs are gone.\n'
      return 0
    fi
    local remaining=0
    for pkg in "${foreign[@]}"; do
      if printf '%s\n' "$after" | grep -Fxq -- "$pkg"; then remaining=1; fi
    done
    [ "$remaining" = 1 ] || { printf 'Gone.\n'; return 0; }
  done
  # Still there. A persistent process (System UI) survives force-stop: on the
  # device its pid stayed the same and the dialog stayed up (#189). Its
  # dialog's Wait button closes it without killing anything. The button
  # cannot be tied to a package in the dump, so it is pressed only while no
  # ANR window of ours is on screen - the one pressed could be ours.
  if press_wait_on_foreign "${foreign[@]}"; then
    printf 'Gone after Wait.\n'
    return 0
  fi
  # A dismissed app restarts after a force-stop and can ANR again on a slow
  # boot. Saying so once is worth more than looping: the tests and the
  # evidence below are the honest reporters, and a second attempt would only
  # spend the job's time.
  printf 'Still there after the dismissal; leaving it and running the tests anyway.\n'
  return 0
}

# Presses the Wait button (android:id/aerr_wait) of the topmost ANR dialog,
# once per foreign package, while every ANR window on screen is foreign.
# Succeeds when none of $@ is left.
press_wait_on_foreign() { # $@ = the foreign packages
  local round now pkg bounds
  for round in "$@"; do
    now="$(anr_packages 5)" || return 1
    local left=0 ours_up=0
    while IFS= read -r pkg; do
      [ -n "$pkg" ] || continue
      if is_ours "$pkg"; then ours_up=1; else left=1; fi
    done <<<"$now"
    [ "$left" = 1 ] || return 0
    if [ "$ours_up" = 1 ]; then
      printf 'An ANR window of the app under test is up too; not pressing Wait, it could be ours.\n'
      return 1
    fi
    timeout 10 adb shell uiautomator dump /sdcard/anr-dump.xml >/dev/null 2>&1 || return 1
    bounds="$(timeout 10 adb shell cat /sdcard/anr-dump.xml 2>/dev/null | tr -d '\r' |
      grep -o 'resource-id="android:id/aerr_wait"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' |
      sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p' | head -1)"
    [ -n "$bounds" ] || { printf 'No Wait button in the dump.\n'; return 1; }
    set -- $bounds
    printf 'Pressing Wait on the ANR dialog that survived force-stop.\n'
    timeout 10 adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
    # The dialog closes a moment after the tap: the device's first look right
    # after it still found System UI's (#189). Wait for the list to change,
    # bounded like the re-check above.
    local before="$now" deadline=$((SECONDS + ANR_RECHECK_SECONDS)) pause
    while [ "$SECONDS" -lt "$deadline" ]; do
      pause=$((deadline - SECONDS))
      [ "$ANR_RECHECK_SLEEP" = 0 ] || sleep "$(( ANR_RECHECK_SLEEP < pause ? ANR_RECHECK_SLEEP : pause ))"
      now="$(anr_packages "$(( deadline - SECONDS > 0 ? deadline - SECONDS : 1 ))")" || return 1
      [ "$now" = "$before" ] || break
    done
  done
  now="$(anr_packages 5)" || return 1
  while IFS= read -r pkg; do
    [ -n "$pkg" ] || continue
    is_ours "$pkg" || return 1
  done <<<"$now"
  return 0
}

[ "${BASH_SOURCE[0]}" = "$0" ] || return 0

clear_foreign_anrs

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
