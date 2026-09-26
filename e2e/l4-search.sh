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
# 3. contacts without READ_CONTACTS: reported, the key kept (#140);
# 4. #3 slice 1: the icons and search keys read back as pushed, an icon size
#    the settings do not offer fails the file and keeps the last good state,
#    call on tap without CALL_PHONE is reported, and a tap on a contact's
#    number then opens the dialer instead of doing nothing;
# 5. stops the instance and releases the lock.
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
# The command's own output goes into the failure: on 2026-09-26 11:33 this
# failed with the reason sent to /dev/null, and it could not be told apart
# afterwards from the emulator being slow under load.
out="$(adb -s "$SERIAL" shell cmd role add-role-holder android.app.role.HOME "$PKG" 2>&1)" \
  || die "could not grant the HOME role to $PKG: ${out:-no output}"
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

# --- #3 slice 1: icons and search keys ------------------------------------
# A. Every key away from its default reads back as pushed.
log "slice 1: icon size, adaptify, badges, list icons, app details, call on tap"
cat > "$WORK/slice1.json" <<'EOF'
{ "schemaVersion": 2,
  "icons": { "size": 56, "adaptify": true,
             "badges": { "notifications": false, "shortcuts": false, "suspendedApps": false } },
  "search": { "contacts": true, "listIcons": false, "appDetails": false, "contactsCallOnTap": true } }
EOF
adb -s "$SERIAL" shell pm grant "$PKG" android.permission.CALL_PHONE || die "could not grant CALL_PHONE"
push_config "$WORK/slice1.json" "slice 1"
assert_jq "$(query_json diagnostics)" '.success == true and ((.diagnostics // []) | length) == 0' \
  "slice 1 applies with nothing to report while CALL_PHONE is held"
assert_jq "$(query_json config)" \
  '.icons.size == 56 and .icons.adaptify == true
   and .icons.badges == {"notifications": false, "shortcuts": false, "suspendedApps": false}
   and .search.listIcons == false and .search.appDetails == false and .search.contactsCallOnTap == true' \
  "the read-back serves every slice-1 key as pushed"
ok "slice 1 keys: applied and read back"

# B. A size the settings do not offer fails the file; the last good state stays.
printf '{ "schemaVersion": 2, "icons": { "size": 50 } }\n' > "$WORK/size50.json"
h50="$(sha256sum "$WORK/size50.json" | cut -d' ' -f1)"
write_config "$WORK/size50.json"
wait_report ".configSha256 == \"$h50\"" 60 "the reload of icons.size 50"
assert_jq "$LAST_REPORT" \
  '.success == false and ([.diagnostics[] | select(.code == "invalid-icons" and .path == "icons.size" and .severity == "error")] | length) == 1' \
  "icons.size 50 fails with invalid-icons at its path"
assert_jq "$(query_json config)" '.icons.size == 56' "the last good size stays"
ok "icons.size 50: rejected, last good state kept"

# C. Call on tap without CALL_PHONE: applied as written, reported.
adb -s "$SERIAL" shell pm revoke "$PKG" android.permission.CALL_PHONE || die "could not revoke CALL_PHONE"
push_config "$WORK/slice1.json" "call on tap without CALL_PHONE"
assert_jq "$(query_json diagnostics)" \
  '.success == true and ([.diagnostics[]? | select(.code == "permission-missing" and .path == "search.contactsCallOnTap" and .severity == "warning")] | length) == 1' \
  "the report says a tap dials instead of calling"
assert_jq "$(query_json config)" '.search.contactsCallOnTap == true' "the read-back keeps what the file asked for"
ok "without CALL_PHONE: permission-missing reported, read-back true"

# D. And the tap itself: without CALL_PHONE it reaches the dial pad. Before
# the fix the call was refused and the tap did nothing at all.
CONTACT="Slice Onetest"
NUMBER="5550100"
adb -s "$SERIAL" shell content insert --uri content://com.android.contacts/raw_contacts \
  --bind account_type:n: --bind account_name:n: >/dev/null || die "could not insert a contact"
raw_id="$(adb -s "$SERIAL" shell content query --uri content://com.android.contacts/raw_contacts --projection _id \
  | tr -d '\r' | sed -n 's/.*_id=\([0-9]*\).*/\1/p' | tail -1)"
[ -n "$raw_id" ] || die "could not read the new contact's id"
adb -s "$SERIAL" shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:"$raw_id" \
  --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:"'$CONTACT'" >/dev/null
adb -s "$SERIAL" shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:"$raw_id" \
  --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:"$NUMBER" --bind data2:i:2 >/dev/null
open_search_field "Onetest"
wait_text "$CONTACT" 20
tap_text "$CONTACT" || die "the contact is not where a tap reaches it"
wait_text "$NUMBER" 10
tap_text "$NUMBER" || die "the number is not where a tap reaches it"
TOP=""
dialer_on_top() {
  TOP="$(adb_t shell dumpsys activity activities | tr -d '\r' \
    | sed -n 's/.*topResumedActivity=ActivityRecord{[^ ]* [^ ]* \([^ ]*\) .*/\1/p' | head -1)"
  case "$TOP" in *dialer*|*Dialer*) return 0 ;; *) return 1 ;; esac
}
retry_for 10 dialer_on_top || true
top="$TOP"
log "top activity after the tap: $top"
case "$top" in
  *InCall*) die "the tap placed a call without CALL_PHONE: $top" ;;
  *dialer*|*Dialer*) ok "without CALL_PHONE: the tap on the number opened the dialer ($top)" ;;
  *) die "the tap on the number did not reach the dialer: top is '$top'" ;;
esac
adb -s "$SERIAL" shell input keyevent KEYCODE_HOME

ok "l4-search passed"
