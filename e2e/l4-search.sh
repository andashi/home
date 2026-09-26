#!/usr/bin/env bash
# L4 scenario: leaving search (#95). On the GrapheneOS emulator, through the
# launcher's public surface only (input, uiautomator dump):
#
#   SERIAL=emulator-5556 OVERLAY_DIR=<gos-repo>/emulator/instances/test \
#     e2e/l4-search.sh [path/to/app-default-debug.apk]
#
# 1. takes the instance's device lock, boots it from `clean`, installs the
#    launcher and makes it the home app;
# 2. opens search by tapping the bar and types a query, then leaves it three
#    ways - the Back key (3-button navigation, a keyboard), a swipe down on
#    the results, the Home button - and asserts each time that the home
#    screen comes back and stays: the Back key used to close search and
#    reopen it at once, because the framework handed focus back to the
#    search field;
# 3. stops the instance and releases the lock.
#
# Runs as the unrooted shell (uid 2000, asserted).
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
LOCK_OWNER="l4-search@$SERIAL#$$"
SNAPSHOT="${SNAPSHOT:-clean}"
HERE="$(cd "$(dirname "$0")" && pwd)"
APK="${1:-$HERE/../app/app/build/outputs/apk/default/debug/app-default-debug.apk}"
PKG="${PKG:-org.andashi.home.debug}"

c(){ [ -t 1 ] && printf '\033[%sm%s\033[0m\n' "$1" "$2" || printf '%s\n' "$2"; }
log(){ c '1;34' ":: $*"; }; ok(){ c '1;32' " + $*"; }; warn(){ c '1;33' " ! $*"; }
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
# The Home-button step needs this launcher to be home: in another launcher
# it would pass without testing anything.
adb -s "$SERIAL" shell cmd role add-role-holder android.app.role.HOME "$PKG" >/dev/null 2>&1 \
  || die "could not grant the HOME role to $PKG"
roles="$(adb -s "$SERIAL" shell dumpsys role 2>/dev/null | tr -d '\r')"
grep -A2 'android.app.role.HOME' <<<"$roles" | grep -q "holders=$PKG" \
  || die "$PKG does not hold the HOME role after add-role-holder"
ok "HOME role granted to $PKG"
show_home
wait_desc Search 30 "the launcher's search bar"

# Opens search and types a letter, then closes the keyboard: it takes the
# first Back for itself, and that is not under test. Search must still be
# open afterwards. screen_state, open_search_field and dismiss_keyboard are the
# library's (#164).
enter_search() {
  open_search_field c
  dismiss_keyboard
  search_is_open || die "search closed with the keyboard"
}

# Home must come back and still be there 3 s later: the defect closed search
# and reopened it within 50 ms, so one early look would pass. Both looks
# must positively see home. The first gets 3 looks, bounded by rounds (#126):
# a dump takes about 4 s on the emulator, so "within 5 s" had room for one
# look, while the loop it replaces allowed up to 25 dumps. The second look
# retries only for a readable dump, never for a second chance at home.
assert_home_stays() { # $1 = how search was left
  retry_rounds 3 "${ROUND_CAP:-20}" home_is_shown \
    || die "$1: not back on the home screen after 3 looks (screen: $(screen_state))"
  sleep 3
  retry_rounds 3 "${ROUND_CAP:-20}" screen_known || true
  [ "$SCREEN" = home ] || die "$1: home did not stay (screen: $SCREEN)"
  ok "$1: back on the home screen, and it stays"
}

log "leaving search with the Back key"
enter_search
adb -s "$SERIAL" shell input keyevent KEYCODE_BACK
assert_home_stays "Back key"

log "leaving search with a swipe down on the results"
enter_search
read -r width height < <(adb -s "$SERIAL" shell wm size | tr -d '\r' | awk '/size/ {s=$NF} END {split(s, a, "x"); print a[1], a[2]}')
adb -s "$SERIAL" shell input swipe $((width / 2)) $((height / 4)) $((width / 2)) $((height * 3 / 4)) 250
assert_home_stays "swipe down"

log "leaving search with the Home button"
enter_search
adb -s "$SERIAL" shell input keyevent KEYCODE_HOME
assert_home_stays "Home button"

# --- contacts asked for in a profile without READ_CONTACTS (#140) ---------
# The key stays as written and reads back true: the read-back feeds
# write-back and --pull, so a missing permission must not become a written
# `false`. The report says why contact search finds nothing, and the banner
# stays: its Turn off writes `contacts: false` into the file since #3 slice 4,
# so it is a real choice, which is why #140's "no banner" no longer holds.
log "search.contacts: true without READ_CONTACTS"
adb -s "$SERIAL" shell pm revoke "$PKG" android.permission.READ_CONTACTS >/dev/null 2>&1 || true
printf '{ "schemaVersion": 2, "search": { "contacts": true } }\n' > "$WORK/contacts.json"
push_config "$WORK/contacts.json" "contacts"
report="$(query_json diagnostics)"
assert_jq "$report" \
  '.success == true and ([.diagnostics[]? | select(.code == "permission-missing" and .path == "search.contacts" and .severity == "warning")] | length) == 1' \
  "the report says contact search cannot work here"
assert_jq "$(query_json config)" '.search.contacts == true' "the read-back keeps what the file asked for"
# enter_search: #172 wrote this against the script's former open_search,
# which also typed a letter and closed the keyboard. The library's
# open_search_field types nothing, and the banner answers a query.
enter_search
wait_text "Contacts permission is required to search your contacts" 15
wait_text "Turn off" 5
adb -s "$SERIAL" shell input keyevent KEYCODE_HOME
assert_home_stays "Home button after the contacts banner"
ok "without READ_CONTACTS: permission-missing reported, read-back true, the banner offers Grant and Turn off"

# Control: once the profile holds the permission, a reload reports nothing.
adb -s "$SERIAL" shell pm grant "$PKG" android.permission.READ_CONTACTS || die "could not grant READ_CONTACTS"
reload_broadcast
# The report before the grant is a broadcast report too, so waiting for any
# broadcast report could match it (#172 review): wait for one without the
# warning, which only the reload after the grant can write.
wait_report '.trigger == "broadcast" and .success == true and ([.diagnostics[]? | select(.code == "permission-missing")] | length) == 0' \
  30 "a reload after the grant that no longer reports permission-missing"
ok "with READ_CONTACTS: the reload reports nothing"

ok "l4-search passed"
