#!/usr/bin/env bash
# Runs e2e/check-gos-run.sh against small e2e-shaped directories: a library
# with gos_run, one script, and whether the guard must pass it.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
failed=0
LIB='gos_run() {
  (cd "$GOS_REPO" && SERIAL="$SERIAL" emulator/run.sh "$@")
}'
case_() { # $1 = name, $2 = expect pass|fail, $3 = script body, [$4 = library body]
  local d="$WORK/$1" rc
  mkdir -p "$d/lib"; printf '%s\n' "${4:-$LIB}" > "$d/lib/grid-device.sh"; printf '%s\n' "$3" > "$d/s.sh"
  "$here/check-gos-run.sh" "$d" >/dev/null 2>&1; rc=$?
  if { [ "$2" = pass ] && [ $rc -eq 0 ]; } || { [ "$2" = fail ] && [ $rc -ne 0 ]; }; then
    echo " + $1"
  else
    echo " x $1 (expected $2, exit $rc)"; failed=1
  fi
}
case_ through-gos-run pass 'SNAPSHOT="$SNAPSHOT" gos_run start'
case_ comment-names-run-sh pass '# gos_run calls emulator/run.sh'
case_ direct-call fail '(cd "$GOS_REPO" && emulator/run.sh start)'
case_ path-in-a-variable fail 'RUN="$GOS_REPO/emulator/run.sh"'
case_ substitution fail 'RUN="$(realpath emulator/run.sh)"'
case_ indented-direct-call fail '  (cd "$GOS_REPO" && SNAPSHOT="$SNAPSHOT" emulator/run.sh start) >&2'
case_ direct-call-in-a-long-script fail "$(for i in $(seq 1 20000); do echo ": filler $i"; done)
emulator/run.sh stop"
case_ library-with-a-second-call fail 'gos_run stop' "$LIB
other() { emulator/run.sh stop; }"
case_ library-without-gos-run fail 'gos_run stop' 'other() { (cd "$GOS_REPO" && emulator/run.sh "$@"); }'
# A script in a subdirectory is an entry point too; *.test.sh drive fakes.
mkdir -p "$WORK/sub/lib" "$WORK/sub/ci"; printf '%s\n' "$LIB" > "$WORK/sub/lib/grid-device.sh"
printf '%s\n' 'emulator/run.sh stop' > "$WORK/sub/ci/x.sh"
if "$here/check-gos-run.sh" "$WORK/sub" >/dev/null 2>&1; then echo " x subdirectory-script (passed)"; failed=1; else echo " + subdirectory-script"; fi
mkdir -p "$WORK/deep/lib" "$WORK/deep/ci/nested"; printf '%s\n' "$LIB" > "$WORK/deep/lib/grid-device.sh"
printf '%s\n' 'emulator/run.sh stop' > "$WORK/deep/ci/nested/x.sh"
if "$here/check-gos-run.sh" "$WORK/deep" >/dev/null 2>&1; then echo " x nested-script (passed)"; failed=1; else echo " + nested-script"; fi
mkdir -p "$WORK/tst/lib"; printf '%s\n' "$LIB" > "$WORK/tst/lib/grid-device.sh"
printf '%s\n' 'emulator/run.sh stop' > "$WORK/tst/x.test.sh"
if "$here/check-gos-run.sh" "$WORK/tst" >/dev/null 2>&1; then echo " + test-files-skipped"; else echo " x test-files-skipped"; failed=1; fi
exit "$failed"
