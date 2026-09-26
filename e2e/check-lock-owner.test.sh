#!/usr/bin/env bash
# Runs e2e/check-lock-owner.sh against small e2e-shaped directories: one
# script each, and whether the guard must pass it.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
failed=0
case_() { # $1 = name, $2 = expect pass|fail, $3 = script body
  local d="$WORK/$1" rc
  mkdir -p "$d"; printf '%s\n' "$3" > "$d/s.sh"
  "$here/check-lock-owner.sh" "$d" >/dev/null 2>&1; rc=$?
  if { [ "$2" = pass ] && [ $rc -eq 0 ]; } || { [ "$2" = fail ] && [ $rc -ne 0 ]; }; then
    echo " + $1"
  else
    echo " x $1 (expected $2, exit $rc)"; failed=1
  fi
}
case_ exported pass 'export LOCK_OWNER="x@$SERIAL#$$"
(cd "$GOS_REPO" && emulator/run.sh start)'
case_ exported-with-default pass 'export LOCK_OWNER="${LOCK_OWNER:-x@$SERIAL#$$}"
"$RUN" snapshot a'
case_ plain-assignment fail 'LOCK_OWNER="x@$SERIAL#$$"
(cd "$GOS_REPO" && emulator/run.sh start)'
case_ indented-plain-assignment fail '  LOCK_OWNER="x@$SERIAL#$$"'
case_ exported-on-a-later-line pass 'LOCK_OWNER="x@$SERIAL#$$"
export LOCK_OWNER
emulator/run.sh stop'
# The owner has to be in the environment when run.sh starts, not somewhere
# in the file.
case_ run-sh-before-the-export fail '(cd "$GOS_REPO" && emulator/run.sh start)
export LOCK_OWNER="x@$SERIAL#$$"'
case_ export-before-run-sh pass 'export LOCK_OWNER="x@$SERIAL#$$"
"$RUN" start'
case_ run-sh-without-an-owner fail '(cd "$GOS_REPO" && SERIAL="$SERIAL" emulator/run.sh stop)'
case_ no-run-sh-no-owner pass 'adb -s "$SERIAL" shell true'
case_ comment-mentions-run-sh pass '# emulator/run.sh is started by the caller'
# A long script: `sed | grep -q` under pipefail read an early match as none.
case_ plain-assignment-in-a-long-script fail "LOCK_OWNER=\"x@\$SERIAL#\$\$\"
$(for i in $(seq 1 20000); do echo ": filler line $i"; done)"
# lib/ and test harnesses are not entry points.
mkdir -p "$WORK/libdir/lib"; printf '%s\n' '(cd "$GOS_REPO" && emulator/run.sh stop)' > "$WORK/libdir/lib/x.sh"
printf '%s\n' 'LOCK_OWNER=x' > "$WORK/libdir/x.test.sh"
if "$here/check-lock-owner.sh" "$WORK/libdir" >/dev/null 2>&1; then echo " + lib-and-test-files-skipped"; else echo " x lib-and-test-files-skipped"; failed=1; fi
exit "$failed"
