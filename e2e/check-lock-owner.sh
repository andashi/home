#!/usr/bin/env bash
# Fails when an e2e script can reach run.sh without exporting LOCK_OWNER.
# run.sh refuses to start, stop or restore an instance whose lock anyone but
# LOCK_OWNER holds (provisioning 08c2834). A script that acquires the lock
# under a LOCK_OWNER it does not export is refused on its own instance, and
# a cleanup stop behind `|| true` is refused silently, leaving the instance
# running with nobody's lock.
#
#   e2e/check-lock-owner.sh        # exit 1 and name every offender
set -euo pipefail
cd "${1:-$(dirname "$0")}"  # a directory laid out like e2e/, for the tests

# The text is read once and grepped as a string: `sed | grep -q` under
# pipefail fails whenever grep stops reading before sed is done, which on a
# long script hid five of the fifteen offenders.
# The file is scanned in order: run.sh inherits LOCK_OWNER only from an
# export that ran before it, so a call above the export, or an assignment
# that is never exported, is an offence. Lines are read with a here-string,
# not `sed | grep -q`: under pipefail a grep that stops early reads as no
# match, which hid five of the fifteen offenders in this guard's first form.
found=0
export_re='^[[:space:]]*export[[:space:]]+LOCK_OWNER([=[:space:]]|$)'
assign_re='^[[:space:]]*LOCK_OWNER='
call_re='emulator/run\.sh|"\$RUN"'
# A line that only assigns (RUN="$GOS_REPO/emulator/run.sh") names run.sh
# without calling it; an assignment prefixed to a command still calls it.
only_assign_re='^[[:space:]]*(local[[:space:]]+|readonly[[:space:]]+)?[A-Za-z_][A-Za-z0-9_]*=("[^"]*"|'"'"'[^'"'"']*'"'"'|[^[:space:]]*)[[:space:]]*$'
# Entry points only: lib/ relies on the calling script's export, and a
# *.test.sh drives fakes, not instances.
for script in *.sh; do
  [ -f "$script" ] || continue
  case "$script" in *.test.sh) continue ;; esac
  exported=0 assigned=0 n=0 first_call=""
  while IFS= read -r line || [ -n "$line" ]; do
    n=$((n + 1))
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    # An export carries an owner only with a value: its own, or an earlier
    # assignment's. `export LOCK_OWNER` of an unset variable gives none.
    if [[ "$line" =~ $export_re ]]; then
      if [[ "$line" =~ LOCK_OWNER= ]] || [ "$assigned" = 1 ]; then exported=1; fi
      continue
    fi
    [[ "$line" =~ $assign_re ]] && assigned=1
    [[ "$line" =~ $only_assign_re ]] && continue
    if [ "$exported" = 0 ] && [ -z "$first_call" ] && [[ "$line" =~ $call_re ]]; then first_call=$n; fi
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
