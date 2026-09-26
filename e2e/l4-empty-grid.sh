#!/usr/bin/env bash
# L4 scenario: an empty grid on a fresh profile stays empty (#92). On the
# GrapheneOS emulator, through the launcher's public surface only:
#
#   SERIAL=emulator-5556 OVERLAY_DIR=<gos-repo>/emulator/instances/test \
#     e2e/l4-empty-grid.sh [path/to/app-default-debug.apk]
#
# The sequence the provisioning host measured:
# 1. boot from `clean`, install the launcher, make it the home app, but do
#    not bring it to the front;
# 2. push a config whose phone and fold layouts are `items: []` and wait
#    until the launcher reports it applied;
# 3. bring the launcher to the front for the first time;
# 4. assert that the read-back still serves both layouts empty and that no
#    dock is on screen: the default favorites row must not have been
#    written over the file's empty layout.
#
# Runs as the unrooted shell (uid 2000, asserted), under the device lock.
set -euo pipefail

gos_repo_default() {
  local d; d="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  while [ "$d" != "/" ]; do
    [ -d "$d/provisioning/emulator" ] && { printf '%s\n' "$d/provisioning"; return; }
    d="$(dirname "$d")"
  done
  printf '%s\n' "$HOME/Development/GrapheneOS"
}
GOS_REPO="${GOS_REPO:-$(gos_repo_default)}"
INSTANCE_OVERRIDE="${SERIAL:+s}${OVERLAY_DIR:+o}"
SERIAL="${SERIAL:-emulator-5556}"
export SERIAL
export OVERLAY_DIR="${OVERLAY_DIR:-$GOS_REPO/emulator/instances/test}"
LOCK_OWNER="l4-empty-grid@$SERIAL#$$"
SNAPSHOT="${SNAPSHOT:-clean}"
HERE="$(cd "$(dirname "$0")" && pwd)"
APK="${1:-$HERE/../app/app/build/outputs/apk/default/debug/app-default-debug.apk}"
PKG="${PKG:-org.andashi.home.debug}"

c(){ [ -t 1 ] && printf '\033[%sm%s\033[0m\n' "$1" "$2" || printf '%s\n' "$2"; }
log(){ c '1;34' ":: $*"; }; ok(){ c '1;32' " + $*"; }
die(){ c '1;31' " x $*" >&2; exit 1; }

[ "$INSTANCE_OVERRIDE" = "" ] || [ "$INSTANCE_OVERRIDE" = "so" ] \
  || die "SERIAL and OVERLAY_DIR name ONE instance - set both or neither"
[ -f "$APK" ] || die "APK not found: $APK"

WORK="$(mktemp -d)"
# shellcheck source=lib/grid-device.sh
. "$HERE/lib/grid-device.sh"

HAVE_LOCK=0
cleanup() {
  if [ "$HAVE_LOCK" = 1 ]; then
    (cd "$GOS_REPO" && SERIAL="$SERIAL" emulator/run.sh stop) >/dev/null 2>&1 || true
    (cd "$GOS_REPO" && emulator/device-lock.sh release "$LOCK_OWNER" "$SERIAL") >/dev/null 2>&1 || true
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

(cd "$GOS_REPO" && emulator/device-lock.sh acquire "$LOCK_OWNER" "$SERIAL")
HAVE_LOCK=1
log "booting $SERIAL from snapshot '$SNAPSHOT' (overlays: $OVERLAY_DIR)"
(cd "$GOS_REPO" && SNAPSHOT="$SNAPSHOT" emulator/run.sh start)
unrooted_shell
adb -s "$SERIAL" install -r "$APK" | grep -q Success || die "launcher install failed"
grant_home_role
ok "launcher installed and home, not yet in the foreground"

log "pushing a config with empty phone and fold layouts before the first foreground"
cat > "$WORK/empty.json" <<'EOF'
{
  "schemaVersion": 2,
  "home": {
    "favorites": [],
    "widgets": { "enabled": true },
    "grid": { "layouts": { "phone": { "items": [] }, "fold": { "items": [] } } }
  }
}
EOF
push_config "$WORK/empty.json" "empty-layouts"
ok "empty layouts applied"

log "first foreground"
show_home
wait_desc Search 30 "the launcher's search bar"
# The default row is written on the grid's first composition; give it time.
sleep 5

config="$(query_json config)" || die "could not read back the config"
jq -e '.home.grid.layouts.phone.items == [] and .home.grid.layouts.fold.items == []' <<<"$config" >/dev/null \
  || { printf '%s\n' "$config" | jq -c '.home.grid.layouts' >&2; die "the read-back is no longer empty"; }
ok "read-back: both layouts still empty"
# dump_cells fails on a failed or empty dump; that proves nothing, so retry
# and never read a missing dump as "no cells". Bounded by rounds (#164): the
# question is whether a readable dump can be taken at all.
cells=""
read_cells() { cells="$(dump_cells)"; }
retry_rounds 3 "${ROUND_CAP:-20}" read_cells || die "could not dump the screen to look for grid cells"
[ -z "$cells" ] || { printf '%s\n' "$cells" >&2; die "grid cells are on screen"; }
ok "no grid cell on screen"

ok "l4-empty-grid passed"
