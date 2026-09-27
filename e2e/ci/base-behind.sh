#!/usr/bin/env bash
# base_behind_ok <files changed on main since the base> <files the PR changes>
# (newline-separated). AGENTS.md "Merging a pull request": a base behind main
# is fine when the commits in between touch nothing the PR touches and nothing
# shared - e2e/lib/, core/, services/, data/, libs/ (vendored modules),
# build files, gradle.properties, the wrapper and the version catalog; app/
# is counted as shared too, to err toward rebasing. .github/ is not: CI
# changes alter which checks run, not what the code does, and a gate
# requires the pull request's own checks green anyway. Prints why
# and returns 1 when a rebase is needed; empty input on either side is not
# "nothing to check" but refused, because an empty diff here means a lookup
# failed.
#
# Sourced by merge gates, so it sets no shell options and runs no bare grep.
# Do not "tidy" `set -e` in, and do not drop the `|| true` after the grep: a
# grep that finds nothing exits 1, and under `set -e` that kills the caller
# with exit 1 and no message. A predicate that dies silently is worse than one
# that answers wrongly - one gate's copy did exactly that, so its allowance
# branch had never once executed and nobody knew. The answer is the return
# status and the printed reason, nothing else.
#
#   source e2e/ci/base-behind.sh
#   why="$(base_behind_ok "$between" "$mine")" || echo "rebase: $why"
base_behind_ok() {
  local between="$1" mine="$2"
  [ -n "$between" ] || { echo "no files between base and main: the diff failed"; return 1; }
  [ -n "$mine" ] || { echo "the pull request changes no files: the diff failed"; return 1; }
  local shared overlap
  shared="$(grep -E '^(e2e/lib/|core/|services/|data/|app/|libs/|gradle/|gradle\.properties$|gradlew$|gradlew\.bat$|build\.gradle\.kts$|settings\.gradle\.kts$|.*/build\.gradle\.kts$)' <<<"$between" || true)"
  [ -z "$shared" ] || { echo "main changed shared paths since the base: $(tr '\n' ' ' <<<"$shared")"; return 1; }
  overlap="$(comm -12 <(sort -u <<<"$between") <(sort -u <<<"$mine"))"
  [ -z "$overlap" ] || { echo "main changed files the pull request changes: $(tr '\n' ' ' <<<"$overlap")"; return 1; }
  return 0
}
