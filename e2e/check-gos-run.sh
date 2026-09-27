#!/usr/bin/env bash
# Fails when anything in e2e/ can call provisioning's run.sh around gos_run.
#
# gos_run (lib/grid-device.sh) checks at the moment of the call that
# LOCK_OWNER is exported with a value, and run.sh itself refuses a foreign
# owner (provisioning 08c2834). Neither reads a script's text, so neither can
# be fooled by quoting, ordering, unset or eval. What is left for a static
# check is whether a script goes around them: only gos_run's body may hold
# the path /run.sh. It anywhere else fails - a call, a path in a variable, a
# substitution, a comment - since each is a way to reach run.sh unchecked,
# and no line is exempt, so the guard never has to tell code from comment.
# The boundary: the guard reads the literal name. A name put together at
# runtime (run"."sh, a path assembled from variables) is beyond what reading
# text can decide - deciding it is the game this guard exists to stop
# playing. Such a call still meets run.sh's own refusal of a foreign owner;
# it loses only gos_run's earlier, clearer refusal, and review is where it
# gets caught. Stated so that nobody reads this guard's silence as more.
# It replaces a line scanner that tried to decide by reading bash whether
# run.sh would get an owner, and needed eight review rounds to stop saying
# yes when the answer was no (#194).
#
#   e2e/check-gos-run.sh        # exit 1 and name every offender
set -euo pipefail
cd "${1:-$(dirname "$0")}"  # a directory laid out like e2e/, for the tests

# Lines holding the path /run.sh, as line:text, comments included: a file
# is executed by a path with a slash, and telling a comment from a line in a
# quoted string would be reading bash again. Prose says "run.sh" without the
# slash. grep exits 1 when a file has none; that one status is an empty
# result, any other aborts.
mentions() { grep -nF '/run.sh' "$1" || [ $? -eq 1 ]; }

found=0
lib=lib/grid-device.sh
[ -f "$lib" ] || { printf '::error file=e2e/%s::missing\n' "$lib"; exit 1; }
in_lib="$(mentions "$lib")"
# gos_run's body, as a line range: from `gos_run() {` to the first `}` in
# column one after it.
range="$(awk '/^gos_run\(\) \{/ { s = NR } s && NR > s && /^\}/ { print s, NR; exit }' "$lib")"
outside=""
if [ -z "$range" ]; then
  outside="no gos_run() in the library"
elif [ -z "$in_lib" ]; then
  outside="gos_run does not hold the path /run.sh"
else
  read -r from to <<<"$range"
  while IFS= read -r hit; do
    n="${hit%%:*}"
    { [ "$n" -gt "$from" ] && [ "$n" -lt "$to" ]; } || outside="$outside$hit"$'\n'
  done <<<"$in_lib"
fi
if [ -n "$outside" ]; then
  printf '::error file=e2e/%s::the path /run.sh may appear only inside gos_run:\n%s\n' "$lib" "$outside"
  found=1
fi
while IFS= read -r -d '' script; do
  script="${script#./}"
  case "$script" in "$lib" | *.test.sh | check-gos-run.sh) continue ;; esac
  hits="$(mentions "$script")"
  [ -z "$hits" ] && continue
  while IFS= read -r hit; do
    printf '::error file=e2e/%s,line=%s::holds the path /run.sh outside gos_run; call gos_run instead\n' "$script" "${hit%%:*}"
  done <<<"$hits"
  found=1
done < <(find . -name '*.sh' -type f -print0 | sort -z)
[ "$found" = 0 ] && echo "run.sh is reached only through gos_run"
exit "$found"
