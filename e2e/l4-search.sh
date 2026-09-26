#!/usr/bin/env bash
# L4 scenario: leaving search (#95). On the GrapheneOS emulator, through the
# launcher's public surface only (input, uiautomator dump):
#
#   SERIAL=emulator-5556 OVERLAY_DIR=<gos-repo>/emulator/instances/test \
#     e2e/l4-search.sh [path/to/app-default-debug.apk]
#
# 1. takes the instance's device lock, boots it from `clean`, installs the
#    launcher and makes it the home app;
# 1b. pins two apps and launches a third from search: search's favorites row
#    shows it as frequently used, the dock shows the two pins and nothing else;
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
#    number then opens the dialer instead of doing nothing; search's
#    favorites row keys and its transliterator read back, and one this
#    device's ICU lacks is reported and kept;
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
grant_home_role
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

# --- the dock shows the pins, nothing else --------------------------------
# With "frequently used" on (the default), the frequently-used apps filled
# the dock's empty slots after the pins: a provisioned dock showed apps the
# file never listed. Two pins in a dock four wide, then one launch from
# search, which is all "frequently used" takes (launchCount > 0).
log "the dock: two pins, then an app launched from search"
on_top() { # $1 = package
  local top
  top="$(adb_t shell dumpsys activity activities | tr -d '\r' \
    | sed -n 's/.*topResumedActivity=ActivityRecord{[^ ]* [^ ]* \([^ ]*\) .*/\1/p' | head -1)"
  case "$top" in "$1"/*) return 0 ;; *) return 1 ;; esac
}
dock_descs() { # the content descriptions inside the dock's cell, sorted, one per line
  dump_screen || return 1
  python3 - "$WORK/dump.xml" <<'PY'
import re, sys
try:
    import defusedxml.ElementTree as ET
except ImportError:
    import xml.etree.ElementTree as ET
def box(n):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
    return tuple(map(int, m.groups())) if m else None
nodes = list(ET.parse(sys.argv[1]).getroot().iter("node"))
dock = next((box(n) for n in nodes if n.get("resource-id") == "grid-item:dock"), None)
if dock is None:
    sys.exit(1)
inside = sorted({n.get("content-desc") for n in nodes if n.get("content-desc") and (b := box(n))
                 and b[0] >= dock[0] and b[1] >= dock[1] and b[2] <= dock[2] and b[3] <= dock[3]})
print("\n".join(inside))
PY
}
DOCK=""
# The last reading that saw the dock is kept: on a failing build the wait
# runs to its deadline, and the attempt the deadline cuts off reads nothing,
# which would overwrite what the dock showed (the sixth run lost it that way).
dock_is_the_pins() {
  local d
  d="$(dock_descs | paste -sd, -)" || true
  [ -z "$d" ] || DOCK="$d"
  [ "$d" = "Contacts,Settings" ]
}
# For a failure that has to say what was there: every grid cell, and every
# content description with its bounds.
dock_diagnosis() {
  dump_screen || { echo "(no dump)"; return; }
  python3 - "$WORK/dump.xml" <<'PY'
import sys
try:
    import defusedxml.ElementTree as ET
except ImportError:
    import xml.etree.ElementTree as ET
nodes = list(ET.parse(sys.argv[1]).getroot().iter("node"))
cells = [f'{n.get("resource-id")}{n.get("bounds")}' for n in nodes if n.get("resource-id", "").startswith("grid-item:")]
descs = [f'{n.get("content-desc")}{n.get("bounds")}' for n in nodes if n.get("content-desc")]
print("cells: " + (" ".join(cells) or "none") + " | descriptions: " + (" ".join(descs) or "none"))
PY
}
# home.widgets.enabled is the master switch for the grid, and so for the
# dock in it: without it the home screen shows no grid at all (the first run
# of this step found "cells: none").
cat > "$WORK/pins.json" <<'EOF'
{ "schemaVersion": 2,
  "home": { "widgets": { "enabled": true }, "favorites": ["com.android.settings", "com.android.contacts"] } }
EOF
push_config "$WORK/pins.json" "two pins"
# "Cloc", so the only "Clock" on screen is the result, not the typed text.
# Twice: the first launch of an app with no row yet inserts it with
# launchCount 0 (SearchableDao.touch increments before it inserts), so only
# the second makes it frequently used. The first version of this step
# launched once and the control below found Clock missing from the row.
# Search keeps its last query when it is opened again (the fifth run of this
# step found "Cloc" still in the field at the control), so each use empties it.
clear_search_query() {
  adb_t shell input keyevent KEYCODE_MOVE_END $(printf 'KEYCODE_DEL %.0s' $(seq 1 12)) >/dev/null
}
launch_clock_from_search() {
  open_search_field
  clear_search_query
  adb_t shell input text "Cloc"
  wait_text "Clock" 20
  tap_text "Clock" || die "the Clock result is not where a tap reaches it"
  retry_for 15 on_top com.android.deskclock || die "Clock did not open from search"
  adb -s "$SERIAL" shell input keyevent KEYCODE_HOME
}
launch_clock_from_search
launch_clock_from_search
# Control: the launch counted. Search's favorites row, the same flow as the
# dock's, shows Clock after the pins now. Not just "Clock on screen": with an
# empty query search lists all apps too, Clock among them, so the first
# version of this control saw Clock whether the launch counted or not. The
# favorites row is the top row that holds a pin; Clock has to be in it.
FAV_ROWS=""
clock_in_favorites_row() {
  dump_screen || return 1
  FAV_ROWS="$(python3 - "$WORK/dump.xml" <<'PY'
import re, sys
try:
    import defusedxml.ElementTree as ET
except ImportError:
    import xml.etree.ElementTree as ET
rows = {}
for n in ET.parse(sys.argv[1]).getroot().iter("node"):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
    if n.get("text") and m:
        rows.setdefault(int(m.group(2)) // 20, []).append(n.get("text"))
tops = sorted(k for k, texts in rows.items() if "Settings" in texts or "Contacts" in texts)
print(" / ".join(",".join(rows[k]) for k in sorted(rows)))
sys.exit(0 if tops and "Clock" in rows[tops[0]] else 3)
PY
)"
}
open_search_field
clear_search_query
retry_for 15 clock_in_favorites_row || die "Clock is not in search's favorites row after the launch; rows: $FAV_ROWS"
log "search's rows after the launch: $FAV_ROWS"
adb -s "$SERIAL" shell input keyevent KEYCODE_HOME
assert_home_stays "Home button after the favorites row"
retry_for 15 dock_is_the_pins || die "the dock shows '$DOCK', not only the two pins; on screen: $(dock_diagnosis)"
ok "the dock shows the two pins and not the frequently used Clock ($DOCK)"

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
# `content insert` does not return the new row, so the raw contact carries a
# marker of this run in its free sync1 column and is found by it (#181
# review). account_name cannot carry it: a local contact has neither an
# account name nor a type, and the provider wants both or neither.
MARK="l4-search-$$"
adb -s "$SERIAL" shell content insert --uri content://com.android.contacts/raw_contacts \
  --bind account_type:n: --bind account_name:n: --bind sync1:s:"$MARK" >/dev/null || die "could not insert a contact"
raw_id="$(adb -s "$SERIAL" shell content query --uri content://com.android.contacts/raw_contacts --projection _id \
  --where "\"sync1='$MARK'\"" | tr -d '\r' | sed -n 's/.*_id=\([0-9]*\).*/\1/p')"
[ "$(wc -w <<<"$raw_id")" = 1 ] || die "expected exactly one raw contact marked $MARK, got: '${raw_id}'"
adb -s "$SERIAL" shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:"$raw_id" \
  --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:"'$CONTACT'" >/dev/null
adb -s "$SERIAL" shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:"$raw_id" \
  --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:"$NUMBER" --bind data2:i:2 >/dev/null
# The launcher shows a number formatted for the device's region
# (PhoneNumberUtils.formatNumber): 5550100 reads "555-0100" on a US image.
# So the number is found by its digits, not by the text as inserted; the
# first run of this step waited for "5550100" and timed out on exactly that.
number_bounds() { # $1 = the number's digits
  dump_screen || return 1
  python3 - "$WORK/dump.xml" "$1" <<'PY'
import re, sys
try:
    import defusedxml.ElementTree as ET
except ImportError:
    import xml.etree.ElementTree as ET
for node in ET.parse(sys.argv[1]).getroot().iter("node"):
    if re.sub(r"\D", "", node.get("text", "")) == sys.argv[2]:
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if m:
            print(*m.groups()); break
PY
}
screen_texts() { # every visible text, for a failure that has to say what was there
  dump_screen || { echo "(no dump)"; return; }
  python3 - "$WORK/dump.xml" <<'PY'
import sys
try:
    import defusedxml.ElementTree as ET
except ImportError:
    import xml.etree.ElementTree as ET
print(" | ".join(n.get("text") for n in ET.parse(sys.argv[1]).getroot().iter("node") if n.get("text")))
PY
}
open_search_field "Onetest"
wait_text "$CONTACT" 20
tap_text "$CONTACT" || die "the contact is not where a tap reaches it"
retry_for 15 shows number_bounds "$NUMBER" \
  || die "the expanded contact shows no number with the digits $NUMBER; on screen: $(screen_texts)"
tap_bounds "$(number_bounds "$NUMBER")" || die "could not tap the number"
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

# E. Search's favorites row and its transliterator (#3 slice 1, PR C).
log "slice 1: frequently used, its rows, the edit button, compact tags, the transliterator"
cat > "$WORK/favorites-row.json" <<'EOF'
{ "schemaVersion": 2,
  "search": { "frequentlyUsed": false, "frequentlyUsedRows": 3, "favoritesEditButton": false,
              "compactTags": true, "transliterator": "Any-Latin" } }
EOF
push_config "$WORK/favorites-row.json" "favorites row"
assert_jq "$(query_json diagnostics)" \
  '.success == true and ([.diagnostics[]? | select(.code == "transliterator-unavailable")] | length) == 0' \
  "Any-Latin, which this device's ICU has, applies with nothing to report"
assert_jq "$(query_json config)" \
  '.search.frequentlyUsed == false and .search.frequentlyUsedRows == 3 and .search.favoritesEditButton == false
   and .search.compactTags == true and .search.transliterator == "Any-Latin"' \
  "the read-back serves the favorites row keys and the transliterator as pushed"
ok "favorites row keys and transliterator: applied and read back"

# A transliterator this ICU lacks is a device condition, not a parse error:
# applied as written, reported, kept in the read-back.
printf '{ "schemaVersion": 2, "search": { "transliterator": "No-Such-Transliterator" } }\n' > "$WORK/no-such.json"
push_config "$WORK/no-such.json" "unavailable transliterator"
assert_jq "$(query_json diagnostics)" \
  '.success == true and ([.diagnostics[]? | select(.code == "transliterator-unavailable" and .path == "search.transliterator" and .severity == "warning")] | length) == 1' \
  "the report says this device's ICU does not have the transliterator"
assert_jq "$(query_json config)" '.search.transliterator == "No-Such-Transliterator"' \
  "the read-back keeps what the file asked for"
ok "unavailable transliterator: reported, kept"

ok "l4-search passed"
