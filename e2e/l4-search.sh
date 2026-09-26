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

# What the screen shows, from one valid dump: "search" while search is open
# (its filter button is on screen), "home" while the launcher's bar is on
# screen without it, "unknown" for an empty dump or anything else - which is
# never taken for either, so a failed look cannot pass an exit check.
screen_state() {
  local i
  for i in $(seq 5); do
    adb -s "$SERIAL" shell rm -f /sdcard/grid-dump.xml >/dev/null 2>&1 || true
    if adb -s "$SERIAL" shell uiautomator dump /sdcard/grid-dump.xml >/dev/null 2>&1; then
      adb -s "$SERIAL" shell cat /sdcard/grid-dump.xml | tr -d '\r' > "$WORK/state.xml"
      if [ -s "$WORK/state.xml" ]; then
        if grep -q 'content-desc="Show filters"' "$WORK/state.xml"; then echo search; return; fi
        if grep -q 'content-desc="Search"' "$WORK/state.xml"; then echo home; return; fi
      fi
    fi
    sleep 1
  done
  echo unknown
}
search_open() { [ "$(screen_state)" = search ]; }

ime_shown() {
  # Captured first: `grep -q` stops at the first match, the writer upstream
  # dies of SIGPIPE, and under pipefail a match would read as "not shown".
  local state
  state="$(adb -s "$SERIAL" shell dumpsys input_method | tr -d '\r')"
  grep -q 'mInputShown=true' <<<"$state"
}

open_search() {
  tap_desc Search
  local i
  for i in $(seq 10); do search_open && break; sleep 1; done
  search_open || die "tapping the bar did not open search"
  adb -s "$SERIAL" shell input text c
  # The keyboard takes the first Back for itself; that is not under test. It
  # comes up a moment after the field is focused - a single look right away
  # missed it, and the Back under test then only closed the keyboard.
  for i in $(seq 5); do ime_shown && break; sleep 1; done
  if ime_shown; then
    adb -s "$SERIAL" shell input keyevent KEYCODE_BACK
    for i in $(seq 10); do ime_shown || break; sleep 1; done
    ime_shown && die "the keyboard did not close"
  fi
  search_open || die "search closed with the keyboard"
}

# Home must come back within 5 s and still be there 3 s later: the defect
# closed search and reopened it within 50 ms, so one early look would pass.
# Both looks must positively see home.
assert_home_stays() { # $1 = how search was left
  local _ state=unknown
  for _ in $(seq 5); do
    state="$(screen_state)"
    [ "$state" = home ] && break
    sleep 1
  done
  [ "$state" = home ] || die "$1: not back on the home screen (screen: $state)"
  sleep 3
  state="$(screen_state)"
  [ "$state" = home ] || die "$1: home did not stay (screen: $state)"
  ok "$1: back on the home screen, and it stays"
}

log "leaving search with the Back key"
open_search
adb -s "$SERIAL" shell input keyevent KEYCODE_BACK
assert_home_stays "Back key"

log "leaving search with a swipe down on the results"
open_search
read -r width height < <(adb -s "$SERIAL" shell wm size | tr -d '\r' | awk '/size/ {s=$NF} END {split(s, a, "x"); print a[1], a[2]}')
adb -s "$SERIAL" shell input swipe $((width / 2)) $((height / 4)) $((width / 2)) $((height * 3 / 4)) 250
assert_home_stays "swipe down"

log "leaving search with the Home button"
open_search
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
open_search
wait_text "Contacts permission is required to search your contacts" 15
wait_text "Turn off" 5
adb -s "$SERIAL" shell input keyevent KEYCODE_HOME
assert_home_stays "Home button after the contacts banner"
ok "without READ_CONTACTS: permission-missing reported, read-back true, the banner offers Grant and Turn off"

# Control: once the profile holds the permission, a reload reports nothing.
adb -s "$SERIAL" shell pm grant "$PKG" android.permission.READ_CONTACTS || die "could not grant READ_CONTACTS"
reload_broadcast
wait_report '.trigger == "broadcast"' 30 "the reload after the grant"
assert_jq "$(query_json diagnostics)" '([.diagnostics[]? | select(.code == "permission-missing")] | length) == 0' \
  "with READ_CONTACTS held nothing is reported"
ok "with READ_CONTACTS: the reload reports nothing"

ok "l4-search passed"
