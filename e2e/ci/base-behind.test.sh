#!/usr/bin/env bash
# Cases for e2e/ci/base-behind.sh, counted: exits non-zero unless every case
# answered as expected AND exactly thirteen ran - a file that stops early (an exit
# inherited from what it sources, a case deleted) must not read as green.
# No `set -e`, for the reason the predicate's own header gives.
#
#   e2e/ci/base-behind.test.sh
source "$(dirname "$0")/base-behind.sh"
n=0; bad=0
t() {
  local want=$1 between=$2 mine=$3 name=$4 got
  n=$((n + 1))
  if base_behind_ok "$between" "$mine" >/dev/null; then got=ok; else got=refused; fi
  if [ "$got" = "$want" ]; then echo " + $name: $got"; else echo " x $name: got $got want $want"; bad=$((bad + 1)); fi
}
PR=$'services/config/src/main/A.kt\ne2e/l4-search.sh'
t ok "AGENTS.md" "$PR" "docs only in between"
t ok $'AGENTS.md\ndocs/configuration/apps.md' "$PR" "docs only, two files"
t refused "services/config/src/main/B.kt" "$PR" "a shared module in between"
t refused "e2e/lib/grid-device.sh" "$PR" "the shared shell library"
t refused "gradle/libs.versions.toml" "$PR" "the version catalog"
t refused "app/ui/build.gradle.kts" "$PR" "a build file"
t refused "gradle.properties" "$PR" "the project-wide Gradle properties"
t refused "gradlew" "$PR" "the wrapper script"
t refused "gradlew.bat" "$PR" "the Windows wrapper script"
t refused "libs/address-formatter/src/main/java/Formatter.kt" "$PR" "a vendored library module's source"
t refused "e2e/l4-search.sh" "$PR" "a file the PR touches"
t refused "" "$PR" "an empty diff between"
t refused "AGENTS.md" "" "an empty PR diff"
echo "$n cases, $bad wrong"
[ "$bad" = 0 ] && [ "$n" = 13 ]
