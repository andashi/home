#!/usr/bin/env bash
# Tests for e2e/check-waits.sh against a table of loop shapes (#164).
#
#   e2e/check-waits.test.sh
set -euo pipefail
cd "$(dirname "$0")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
failed=0

verdict() { # $1 = expected (caught|quiet), $2 = description, stdin = script body
  rm -rf "$WORK/e2e"; mkdir -p "$WORK/e2e/lib"; : > "$WORK/e2e/lib/grid-device.sh"
  { printf '#!/usr/bin/env bash\n'; cat; } > "$WORK/e2e/script.sh"
  local got=quiet
  ./check-waits.sh "$WORK/e2e" >/dev/null 2>&1 || got=caught
  if [ "$got" = "$1" ]; then printf ' + %s: %s\n' "$got" "$2"; else printf ' x %s, expected %s: %s\n' "$got" "$1" "$2"; failed=1; fi
}

verdict caught "one-line seq loop with a sleep" <<'S'
for i in $(seq 10); do search_open && break; sleep 1; done
S
verdict caught "multi-line seq loop with a sleep" <<'S'
for _ in $(seq 1 10); do
  if cells="$(dump_cells)"; then break; fi
  sleep 1
done
S
verdict caught "while \$elapsed loop" <<'S'
while [ "$elapsed" -lt "$timeout" ]; do
  query && return 0
  sleep 1; elapsed=$((elapsed + 1))
done
S
verdict caught "a wait nested inside a marked repetition loop" <<'S'
# not a wait: measurement repetitions
for run in $(seq "$RUNS"); do
  measure
  for _ in $(seq 40); do pidof perfetto || break; sleep 0.5; done
done
S
verdict caught "while : with an attempt counter" <<'S'
while :; do
  out="$(query)" && break
  attempt=$((attempt + 1)); [ "$attempt" -lt 15 ] || return 1
  sleep 2
done
S
verdict caught "until loop with a sleep" <<'S'
until alive; do sleep 1; done
S
verdict quiet "a loop bounded by \$SECONDS" <<'S'
while [ "$SECONDS" -lt "$deadline" ]; do sleep 1; check && break; done
S
verdict quiet "a while read loop" <<'S'
while IFS=$'\t' read -r n t; do echo "$n"; done < list
S
verdict quiet "a marker whose explanation runs onto a second comment line" <<'S'
# not a wait: up to 8 scroll attempts, each one
# bounded through adb_t
until tap_it; do swipe; sleep 1; done
S
verdict caught "a marker separated from its loop by code" <<'S'
# not a wait: something else
echo unrelated
for i in $(seq 3); do sleep 1; done
S
verdict quiet "a marked repetition loop" <<'S'
# not a wait: measurement repetitions
for run in $(seq "$RUNS"); do measure; sleep 2; done
S
verdict quiet "a seq loop without a sleep" <<'S'
for i in 1 2 3; do echo "$i"; done
for i in $(seq 3); do echo "$i"; done
S
verdict quiet "a wait through retry_for" <<'S'
retry_for 10 search_open || die "search did not open"
S
verdict quiet "a sleep only in a comment" <<'S'
for i in $(seq 3); do echo "$i"; done  # no sleep here
S
verdict caught "an arithmetic for loop with a sleep" <<'S'
for ((i = 0; i < 5; i++)); do check && break; sleep 1; done
S
verdict caught "a sleep after a quoted #" <<'S'
for i in $(seq 3); do echo "#"; sleep 1; done
S
verdict caught "\$SECONDS only in the body of a counted loop" <<'S'
for i in $(seq 5); do echo "$SECONDS"; sleep 1; done
S
exit "$failed"
