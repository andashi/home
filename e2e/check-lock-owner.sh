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
found=0
# Entry points only: lib/ relies on the calling script's export, and a
# *.test.sh drives fakes, not instances.
for script in *.sh; do
  [ -f "$script" ] || continue
  case "$script" in *.test.sh) continue ;; esac
  c="$(sed -E 's/^[[:space:]]*#.*//' "$script")"  # comment lines out
  exported=0
  grep -qE '^[[:space:]]*export[[:space:]]+LOCK_OWNER([=[:space:]]|$)' <<<"$c" && exported=1
  if [ "$exported" = 0 ] && grep -qE '^[[:space:]]*LOCK_OWNER=' <<<"$c"; then
    printf '::error file=e2e/%s::assigns LOCK_OWNER without exporting it; run.sh will not see the owner\n' "$script"
    found=1
  elif [ "$exported" = 0 ] && grep -qE 'emulator/run\.sh|"\$RUN"' <<<"$c"; then
    printf '::error file=e2e/%s::calls run.sh without an exported LOCK_OWNER\n' "$script"
    found=1
  fi
done
[ "$found" = 0 ] && echo "every e2e script that calls run.sh exports LOCK_OWNER"
exit "$found"
