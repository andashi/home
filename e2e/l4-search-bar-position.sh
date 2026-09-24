#!/usr/bin/env bash
# L4 scenario: the search bar has a position of its own in open search (#107).
# On the GrapheneOS emulator, through the launcher's public surface only:
#
#   SERIAL=emulator-5556 OVERLAY_DIR=<gos-repo>/emulator/instances/test \
#     e2e/l4-search-bar-position.sh [path/to/app-default-debug.apk]
#
# 1. push `home.searchBar.position: bottom` with `search.barPosition: top`;
#    the read-back serves both;
# 2. on the home screen the bar sits in the bottom quarter;
# 3. open search and type: the bar sits in the top quarter, and the best
#    match is right below it (no more than two rows of space between them);
# 4. back to home: the bar is at the bottom again.
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
LOCK_OWNER="l4-search-bar-position@$SERIAL#$$"
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

# The centre y and the bottom of the first node whose content-desc or text is
# $1, as "centre bottom"; empty when it is not on screen.
node_y() { # $1 = content-desc or text
  local b
  b="$(node_bounds content-desc "$1")"
  [ -n "$b" ] || b="$(node_bounds text "$1")"
  [ -n "$b" ] || return 0
  awk '{ printf "%d %d %d\n", ($2 + $4) / 2, $4, $2 }' <<<"$b"
}

screen_height() {
  adb -s "$SERIAL" shell wm size | tr -d '\r' | awk -F'[x ]' '/Physical size/ { print $NF }'
}

(cd "$GOS_REPO" && emulator/device-lock.sh acquire "$LOCK_OWNER" "$SERIAL")
HAVE_LOCK=1
log "booting $SERIAL from snapshot '$SNAPSHOT' (overlays: $OVERLAY_DIR)"
(cd "$GOS_REPO" && SNAPSHOT="$SNAPSHOT" emulator/run.sh start)
adb -s "$SERIAL" unroot >/dev/null 2>&1 || true
adb -s "$SERIAL" wait-for-device
[ "$(adb -s "$SERIAL" shell id -u | tr -d '\r')" = "2000" ] || die "adb is not the unrooted shell"
ok "adb as unrooted shell (uid 2000)"
adb -s "$SERIAL" install -r "$APK" | grep -q Success || die "launcher install failed"
adb -s "$SERIAL" shell cmd role add-role-holder android.app.role.HOME "$PKG" >/dev/null 2>&1 \
  || die "could not grant the HOME role to $PKG"
H="$(screen_height)"
[ -n "$H" ] || die "could not read the screen height"

log "pushing bottom on home, top in search"
cat > "$WORK/c.json" <<'EOF'
{
  "schemaVersion": 2,
  "home": { "searchBar": { "position": "bottom" } },
  "search": { "barPosition": "top", "openKeyboard": false }
}
EOF
push_config "$WORK/c.json" "bar-position"
config="$(query_json config)" || die "could not read back the config"
jq -e '.home.searchBar.position == "bottom" and .search.barPosition == "top"' <<<"$config" >/dev/null \
  || { jq -c '{home: .home.searchBar, search: .search}' <<<"$config" >&2; die "the read-back does not serve both positions"; }
ok "read-back: home bottom, search top"

show_home
wait_desc Search 60 "the launcher's search bar"
sleep 2
read -r home_y _ _ <<<"$(node_y Search)"
[ "$home_y" -gt $((H * 3 / 4)) ] || die "home: the bar is at y $home_y of $H, not in the bottom quarter"
ok "home: the bar is at y $home_y of $H (bottom quarter)"

log "opening search"
tap_desc Search
sleep 2
adb -s "$SERIAL" shell input text set
sleep 3
read -r bar_y bar_bottom _ <<<"$(node_y set)"
[ -n "${bar_y:-}" ] || die "search: the field with the query is not on screen"
[ "$bar_y" -lt $((H / 4)) ] || die "search: the bar is at y $bar_y of $H, not in the top quarter"
ok "search: the bar is at y $bar_y of $H (top quarter)"
read -r _ _ match_top <<<"$(node_y Settings)"
[ -n "${match_top:-}" ] || die "search: the best match (Settings) is not on screen"
gap=$((match_top - bar_bottom))
[ "$gap" -lt $((H / 6)) ] || die "search: $gap px between the bar and the best match"
ok "search: the best match starts $gap px below the bar"

log "closing search"
adb -s "$SERIAL" shell input keyevent KEYCODE_BACK
sleep 1
adb -s "$SERIAL" shell input keyevent KEYCODE_BACK
sleep 2
read -r back_y _ _ <<<"$(node_y Search)"
[ -n "${back_y:-}" ] && [ "$back_y" -gt $((H * 3 / 4)) ] || die "home again: the bar is at y ${back_y:-?} of $H"
ok "home again: the bar is at y $back_y of $H (bottom quarter)"

ok "l4-search-bar-position passed"
