#!/usr/bin/env bash
# Fails when an e2e script defines a function that e2e/lib/grid-device.sh also
# defines (#126). A helper that exists twice drifts: l4-grid.sh's copy of
# enter_edit_mode was updated for the test-tag lookups of #125, the library's
# was not, and screenshots.sh - which uses the library - would have hung for
# 15 s and died, with nothing in CI running it. One definition, in the
# library; a script that needs different behaviour gets a parameter there, or
# a function of its own under its own name.
#
#   e2e/check-helpers.sh        # exit 1 and name every duplicate
set -euo pipefail
cd "${1:-$(dirname "$0")}"  # a directory laid out like e2e/, for the tests

# Every form Bash accepts for a declaration, indented or not:
#   name() {   name () {   function name() {   function name {
# (e2e/check-helpers.test.sh runs this against a table of them). A call, a
# comment or a string that contains "name()" is not a declaration.
defined() {
  grep -E '^[[:space:]]*(function[[:space:]]+[A-Za-z_][A-Za-z0-9_]*([[:space:]]*\([[:space:]]*\))?[[:space:]]*(\{|$)|[A-Za-z_][A-Za-z0-9_]*[[:space:]]*\([[:space:]]*\))' "$1" \
    | sed -E 's/^[[:space:]]*(function[[:space:]]+)?([A-Za-z_][A-Za-z0-9_]*).*/\2/' | sort -u
}

lib="$(defined lib/grid-device.sh)"
found=0
for script in *.sh */*.sh; do
  [ "$script" = lib/grid-device.sh ] && continue
  dup="$(comm -12 <(printf '%s\n' "$lib") <(defined "$script") | tr '\n' ' ')"
  if [ -n "$dup" ]; then
    printf '::error file=e2e/%s::defines helpers the library already has: %s\n' "$script" "$dup"
    found=1
  fi
done
[ "$found" = 0 ] && echo "no e2e script redefines a helper from lib/grid-device.sh"
exit "$found"
