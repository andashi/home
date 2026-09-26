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
(cd "$GOS_REPO" && emulator/run.sh stop)'
# The owner has to be in the environment when run.sh starts, not somewhere
# in the file.
case_ run-sh-before-the-export fail '(cd "$GOS_REPO" && emulator/run.sh start)
export LOCK_OWNER="x@$SERIAL#$$"'
case_ export-before-run-sh pass 'export LOCK_OWNER="x@$SERIAL#$$"
"$RUN" start'
# An export of an unset variable gives run.sh no owner either.
case_ export-without-a-value fail 'export LOCK_OWNER
(cd "$GOS_REPO" && emulator/run.sh stop)'
case_ assigned-then-exported pass 'LOCK_OWNER="x@$SERIAL#$$"
export LOCK_OWNER
"$RUN" stop'
# Naming run.sh in an assignment is not calling it.
case_ run-path-assigned-before-the-export pass 'RUN="$GOS_REPO/emulator/run.sh"
export LOCK_OWNER="x@$SERIAL#$$"
"$RUN" start'
# An assignment whose value runs a command still calls run.sh.
case_ run-sh-in-a-command-substitution fail 'out="$(emulator/run.sh stop)"
export LOCK_OWNER="x@$SERIAL#$$"'
case_ run-sh-in-backticks fail 'out=`emulator/run.sh stop`
export LOCK_OWNER="x@$SERIAL#$$"'
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
# The contract: forms outside it fail by name, never pass quietly. Each of
# these names run.sh in a way the guard does not read, after the export.
unreadable_case() { # $1 = name, $2 = body
  local d="$WORK/$1" out
  mkdir -p "$d"; printf 'export LOCK_OWNER="x@$SERIAL#$$"\n%s\n' "$2" > "$d/s.sh"
  if out="$("$here/check-lock-owner.sh" "$d" 2>&1)"; then echo " x $1 (passed)"; failed=1
  elif grep -q "cannot read" <<<"$out" && grep -q "line=2" <<<"$out"; then echo " + $1"
  else echo " x $1 (failed, but not as unreadable at line 2: $out)"; failed=1; fi
}
unreadable_case unreadable-realpath-substitution 'RUN="$(realpath "$GOS_REPO/emulator/run.sh")"'
unreadable_case unreadable-eval 'eval "emulator/run.sh stop"'
unreadable_case unreadable-unquoted-run '$RUN stop'
unreadable_case unreadable-braced-run '"${RUN}" stop'
unreadable_case unreadable-bare-path 'emulator/run.sh stop'
# A readable call is the only mention on its line, and an export names no
# run.sh at all: a substitution in the export runs before the export does.
unreadable_case unreadable-eval-after-a-readable-call 'if "$RUN" stop; then eval "emulator/run.sh start"; fi'
unreadable_case unreadable-mention-in-an-export 'export LOCK_OWNER="$(emulator/run.sh status)"'
# Every form the contract names, after the export: all pass.
case_ contract-forms pass 'export LOCK_OWNER="x@$SERIAL#$$"
RUN="$GOS_REPO/emulator/run.sh"
(cd "$GOS_REPO" && SNAPSHOT="$SNAPSHOT" emulator/run.sh start) >&2
"$RUN" restore "$1" >/dev/null
if [ "$n" -gt 0 ] && ! "$RUN" restore clean >/dev/null 2>&1; then :; fi'
exit "$failed"
