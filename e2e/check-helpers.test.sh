#!/usr/bin/env bash
# Tests for e2e/check-helpers.sh against a table of Bash declaration forms.
# A guard that recognises only the form that prompted it is blind to the next
# duplicate written another way (review on #163).
#
#   e2e/check-helpers.test.sh
set -euo pipefail
cd "$(dirname "$0")"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
failed=0

# Every form Bash accepts for a function named wait_on_home.
forms=(
  'wait_on_home() { :; }'
  'wait_on_home () { :; }'
  'wait_on_home ( ) { :; }'
  'function wait_on_home() { :; }'
  'function wait_on_home { :; }'
  'function wait_on_home () { :; }'
  '  wait_on_home() { :; }'
  '	function wait_on_home {'
  'wait_on_home()'
)

for form in "${forms[@]}"; do
  rm -rf "$WORK/e2e"; mkdir -p "$WORK/e2e/lib"
  printf 'wait_on_home() { :; }\n' > "$WORK/e2e/lib/grid-device.sh"
  printf '#!/usr/bin/env bash\n%s\n' "$form" > "$WORK/e2e/script.sh"
  if ./check-helpers.sh "$WORK/e2e" >/dev/null 2>&1; then
    printf ' x missed: %s\n' "$form"; failed=1
  else
    printf ' + caught: %s\n' "$form"
  fi
done

# Controls: a call, a comment and a longer name that merely starts with the
# helper's name are not declarations.
for line in 'wait_on_home grid-item:dock' '# wait_on_home() used to live here' 'wait_on_home_twice() { :; }' 'echo "wait_on_home() {"'; do
  rm -rf "$WORK/e2e"; mkdir -p "$WORK/e2e/lib"
  printf 'wait_on_home() { :; }\n' > "$WORK/e2e/lib/grid-device.sh"
  printf '#!/usr/bin/env bash\n%s\n' "$line" > "$WORK/e2e/script.sh"
  if ./check-helpers.sh "$WORK/e2e" >/dev/null 2>&1; then
    printf ' + ignored: %s\n' "$line"
  else
    printf ' x false alarm: %s\n' "$line"; failed=1
  fi
done

exit "$failed"
