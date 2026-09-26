#!/usr/bin/env bash
# Fails when an e2e script can reach run.sh without an exported LOCK_OWNER.
#
# The authority is run.sh itself: since provisioning 08c2834 it refuses to
# start, stop or restore an instance whose lock anyone but LOCK_OWNER holds,
# at runtime, whatever the script looks like. This guard is the early
# warning, so a missing export costs a CI run instead of a device run. A hole
# in it delays that discovery; it does not let the defect through.
#
# Contract: the guard reads the forms that occur in e2e/, not every way bash
# can run a program (the same rule as check-waits.sh):
#   call        (cd "$GOS_REPO" && [VAR=value ...] emulator/run.sh verb ...)
#               [! | && | if | then | do] "$RUN" verb ...
#   not a call  RUN="$GOS_REPO/emulator/run.sh"   (a plain quoted assignment)
# Any other line that names run.sh or $RUN - a command substitution, eval, an
# unquoted or braced $RUN, a second mention next to a readable call, any
# mention in an export line - fails as unreadable, by file and line. Not
# catching a form and not knowing it was not caught are different failures;
# only the second is dangerous, so an unknown form never passes quietly.
# A call must come after `export LOCK_OWNER=value` (or an assignment and then
# `export LOCK_OWNER`), because run.sh inherits only what was exported
# before it ran. Those two, and an assignment at the start of a line, are
# the only forms in which the guard reads LOCK_OWNER changing; any other
# (unset, export -n, declare, a second change on the line) is unreadable,
# because after it the tracked export no longer says what run.sh gets.
#
#   e2e/check-lock-owner.sh        # exit 1 and name every offender
set -euo pipefail
cd "${1:-$(dirname "$0")}"  # a directory laid out like e2e/, for the tests

export_re='^[[:space:]]*export[[:space:]]+LOCK_OWNER([=[:space:]]|$)'
assign_re='^[[:space:]]*LOCK_OWNER='
owner_word_re='(^|[^${A-Za-z0-9_])LOCK_OWNER([^A-Za-z0-9_]|$)'
mention_re='run\.sh|\$\{?RUN\}?([^A-Za-z0-9_]|$)'
prefix='([A-Za-z_][A-Za-z0-9_]*=("[^"`$]*(\$[A-Za-z_{][A-Za-z0-9_}]*[^"`$]*)*"|[^[:space:]"`$]*)[[:space:]]+)*'
call_a='^[[:space:]]*\(cd "\$GOS_REPO" && '"$prefix"'emulator/run\.sh [a-z-]+'
call_b='(^[[:space:]]*|[;&|!(][[:space:]]*|(^|[[:space:]])(if|then|do)[[:space:]]+)'"$prefix"'"\$RUN" [a-z-]+'
path_assign='^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*="[^"`]*"[[:space:]]*$'

found=0
# Entry points only: lib/ relies on the calling script's export, and a
# *.test.sh drives fakes, not instances. Lines are read with a here-string,
# not `sed | grep -q`: under pipefail a grep that stops early reads as no
# match, which hid five of the fifteen offenders in this guard's first form.
for script in *.sh; do
  [ -f "$script" ] || continue
  case "$script" in *.test.sh|check-lock-owner.sh) continue ;; esac
  exported=0 assigned=0 n=0 first_call=""
  while IFS= read -r line || [ -n "$line" ]; do
    n=$((n + 1))
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    # A readable line names run.sh once; an export names it not at all.
    # grep exits 1 on a line without a mention; that one status is a count
    # of zero, any other aborts.
    mentions="$( { grep -oE "$mention_re" <<<"$line" || [ $? -eq 1 ]; } | wc -l)"
    if [ "$mentions" -gt 1 ] || { [ "$mentions" = 1 ] && [[ "$line" =~ $export_re ]]; }; then
      printf '::error file=e2e/%s,line=%s::names run.sh in a form this guard cannot read; use a form from its contract (e2e/check-lock-owner.sh)\n' "$script" "$n"
      found=1; continue
    fi
    # LOCK_OWNER as a word, not a $-expansion, is read only as an assignment
    # at the start of a line or an export, once per line.
    owner_words="$( { grep -oE "$owner_word_re" <<<"$line" || [ $? -eq 1 ]; } | wc -l)"
    if [ "$owner_words" -gt 1 ] || { [ "$owner_words" = 1 ] && ! [[ "$line" =~ $export_re ]] && ! [[ "$line" =~ $assign_re ]]; }; then
      printf '::error file=e2e/%s,line=%s::changes LOCK_OWNER in a form this guard cannot read; use a form from its contract (e2e/check-lock-owner.sh)\n' "$script" "$n"
      found=1; continue
    fi
    if [[ "$line" =~ $export_re ]]; then
      # With a value only: its own, or an earlier assignment's.
      if [[ "$line" =~ LOCK_OWNER= ]] || [ "$assigned" = 1 ]; then exported=1; fi
      continue
    fi
    [[ "$line" =~ $assign_re ]] && assigned=1
    [[ "$line" =~ $mention_re ]] || continue
    if [[ "$line" =~ $path_assign ]] && [[ "$line" != *'$('* ]]; then continue; fi
    if [[ "$line" =~ $call_a ]] || [[ "$line" =~ $call_b ]]; then
      [ "$exported" = 1 ] || [ -n "$first_call" ] || first_call=$n
      continue
    fi
    printf '::error file=e2e/%s,line=%s::names run.sh in a form this guard cannot read; use a form from its contract (e2e/check-lock-owner.sh)\n' "$script" "$n"
    found=1
  done <<<"$(cat "$script")"
  if [ -n "$first_call" ]; then
    printf '::error file=e2e/%s,line=%s::calls run.sh before LOCK_OWNER is exported; run.sh will not see the owner\n' "$script" "$first_call"
    found=1
  elif [ "$assigned" = 1 ] && [ "$exported" = 0 ]; then
    printf '::error file=e2e/%s::assigns LOCK_OWNER without exporting it; run.sh will not see the owner\n' "$script"
    found=1
  fi
done
[ "$found" = 0 ] && echo "every e2e script that calls run.sh exports LOCK_OWNER"
exit "$found"
