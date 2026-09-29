#!/usr/bin/env bash
# L4 scenario: a contact's customizations follow the person, not the row id
# (#237). On the GrapheneOS emulator, in a zone user (not user 0), through the
# launcher's public surface:
#
#   SERIAL=emulator-5556 OVERLAY_DIR=<gos-repo>/emulator/instances/test \
#     e2e/l4-contact-keys.sh [path/to/app-default-debug.apk]
#
# 1. takes the instance's device lock, boots it from `profiles-ready`, installs
#    the launcher for the first full secondary user, grants it the contacts
#    permission and makes it that user's home app;
# 2. creates Alice, Bob and Carol in that user's contacts provider;
# 3. through the customize sheet, as a person would: tags Alice "family" and
#    sets Bob's "Show in" to "Never";
# 4. asserts who carries the tag (a search for "family"), that Bob is hidden
#    and Alice and Carol are not;
# 5. merges Alice and Bob (an aggregation exception), brings the launcher back
#    to the front - which refreshes stored keys - and asserts that the merged
#    contact is hidden, because Bob's row hides it, and that Carol is not;
# 6. splits them again, brings the launcher back, and asserts step 4 again:
#    the tag on Alice and on nobody else, Bob hidden, Alice and Carol shown.
#
# On a build that keys contacts by row id, step 5 fails: the merge keeps
# Alice's id, Bob's row names an id that no longer exists, and the merged
# contact shows. Emulator measurement, 2026-09-28: a merge and a split
# reassign row ids (Alice id 1 became Bob's, Alice id 4). The scenario asserts
# the outcome - who is tagged, who is hidden - not a key format, so it is also
# the guard of the launcher's assumption that a merge joins lookup keys with a
# dot (isMergeOf): if a platform joins them differently, step 6 finds the
# customizations on the wrong person.
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
export LOCK_OWNER="l4-contact-keys@$SERIAL#$$"
SNAPSHOT="${SNAPSHOT:-profiles-ready}"
HERE="$(cd "$(dirname "$0")" && pwd)"
APK="${1:-$HERE/../app/app/build/outputs/apk/default/debug/app-default-debug.apk}"
PKG="${PKG:-org.andashi.home.debug}"
CONTACTS=content://com.android.contacts

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
    adb -s "$SERIAL" shell am switch-user 0 </dev/null >/dev/null 2>&1 || true
    stop_and_release || STOP_FAILED=1
  fi
  rm -rf "$WORK"
  [ "${STOP_FAILED:-0}" = 0 ] || exit 1
}
trap cleanup EXIT

# --- helpers ------------------------------------------------------------------

# The first full secondary user: a zone, not user 0 and not a managed profile
# (flag 0x20). `pm list users` prints UserInfo{<id>:<name>:<hex flags>}.
zone_uid() {
  adb_out shell pm list users </dev/null 2>/dev/null | tr -d '\r' | python3 -c '
import re, sys
for m in re.finditer(r"UserInfo\{(\d+):[^:]*:([0-9a-fA-F]+)\}", sys.stdin.read()):
    uid, flags = int(m.group(1)), int(m.group(2), 16)
    if uid != 0 and not flags & 0x20:
        print(uid); break'
}

# Creates a local contact in the zone and prints its raw contact id. `content
# insert` does not return the row, so it carries a marker in sync1 (#181).
new_contact() { # $1 = display name
  local mark="l4-contact-keys-$$-$1" raw
  adb -s "$SERIAL" shell content insert --user "$ZONE" --uri "$CONTACTS/raw_contacts" \
    --bind account_type:n: --bind account_name:n: --bind sync1:s:"$mark" </dev/null >/dev/null \
    || die "could not create the raw contact for $1"
  raw="$(adb -s "$SERIAL" shell content query --user "$ZONE" --uri "$CONTACTS/raw_contacts" --projection _id \
    --where "\"sync1='$mark'\"" </dev/null | tr -d '\r' | sed -n 's/.*_id=\([0-9]*\).*/\1/p')"
  [ "$(wc -w <<<"$raw")" = 1 ] || die "expected one raw contact marked $mark, got '$raw'"
  adb -s "$SERIAL" shell content insert --user "$ZONE" --uri "$CONTACTS/data" --bind raw_contact_id:i:"$raw" \
    --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:"$1" </dev/null >/dev/null \
    || die "could not name the contact $1"
  printf '%s\n' "$raw"
}

aggregate() { # $1 = 1 (together) | 2 (keep separate), $2 $3 = raw contact ids
  adb -s "$SERIAL" shell content update --user "$ZONE" --uri "$CONTACTS/aggregation_exceptions" \
    --bind type:i:"$1" --bind raw_contact_id1:i:"$2" --bind raw_contact_id2:i:"$3" </dev/null >/dev/null \
    || die "could not write the aggregation exception ($1)"
}

contacts_now() {
  adb -s "$SERIAL" shell content query --user "$ZONE" --uri "$CONTACTS/contacts" \
    --projection _id:lookup:display_name </dev/null | tr -d '\r' | sed 's/^/     /'
}

zone_home() {
  adb -s "$SERIAL" shell am start --user "$ZONE" -a android.intent.action.MAIN \
    -c android.intent.category.HOME "$PKG" </dev/null >/dev/null 2>&1 || die "could not start the launcher for user $ZONE"
}

# Takes the launcher to the background and back, the way returning from
# Settings does: onResume refreshes the stored keys (#237).
resume_launcher() {
  adb -s "$SERIAL" shell am start --user "$ZONE" -a android.settings.SETTINGS </dev/null >/dev/null 2>&1 || true
  sleep 3
  zone_home
  wait_desc Search 30 "the launcher's search bar after the resume"
  sleep 3
}

# Search from the home screen, the keyboard closed.
search_for() { # $1 = query
  zone_home
  wait_desc Search 30 "the launcher's search bar"
  open_search_field "$1"
  dismiss_keyboard
  sleep 3
}

# Whether a search result reads $1: a TextView with that text. The query
# itself sits in the search field, an EditText, and must not count.
result_shown() { # $1 = text
  dump_screen || return 2
  python3 - "$WORK/dump.xml" "$1" <<'PY'
import sys
try:
    import defusedxml.ElementTree as ET
except ImportError:
    import xml.etree.ElementTree as ET
hit = any(n.get("text") == sys.argv[2] and not n.get("class", "").endswith("EditText")
          for n in ET.parse(sys.argv[1]).getroot().iter("node"))
sys.exit(0 if hit else 1)
PY
}

expect_shown() { # $1 = query, $2 = result text, $3 = what it means
  search_for "$1"
  retry_for 10 result_shown "$2" || { screenshot "$WORK/fail.png" >/dev/null 2>&1 || true; die "$3: a search for '$1' shows no '$2'"; }
  ok "$3: '$1' shows '$2'"
}

expect_absent() { # $1 = query, $2 = result text, $3 = what it means
  search_for "$1"
  # A positive look first, so "absent" is not the answer of an empty screen:
  # the search must be open and showing its field.
  search_is_open || die "$3: search did not open for '$1'"
  local rc=0; result_shown "$2" || rc=$?
  [ "$rc" = 2 ] && die "$3: no readable screen for '$1'"
  [ "$rc" = 1 ] || { screenshot "$WORK/fail.png" >/dev/null 2>&1 || true; die "$3: a search for '$1' shows '$2'"; }
  ok "$3: '$1' does not show '$2'"
}

open_customize() { # $1 = name
  search_for "$1"
  retry_for 10 result_shown "$1" || die "no search result '$1' to customize"
  tap_text "$1" || die "the result '$1' is not where a tap reaches it"
  wait_desc Customize 15 "the expanded result's Customize action"
  tap_desc Customize || die "could not tap Customize for $1"
  wait_text Label 15
}

close_sheet() {
  adb -s "$SERIAL" shell input keyevent KEYCODE_BACK </dev/null
  sleep 1
  adb -s "$SERIAL" shell input keyevent KEYCODE_BACK </dev/null
  sleep 2
}

# The sheet's text fields below its drag handle, top to bottom: the label, the
# tags, and "Show in". The tags field is empty - "Tags" is its placeholder and
# reaches no dump - so it is found by its place, the second field.
sheet_field() { # $1 = index from the top
  dump_screen || return 1
  python3 - "$WORK/dump.xml" "$1" <<'PY'
import re, sys
try:
    import defusedxml.ElementTree as ET
except ImportError:
    import xml.etree.ElementTree as ET
nodes = list(ET.parse(sys.argv[1]).getroot().iter("node"))
def rect(n):
    return tuple(map(int, re.findall(r"\d+", n.get("bounds", ""))))
handle = next((rect(n) for n in nodes if n.get("content-desc") == "Drag handle"), None)
if handle is None:
    sys.exit(1)
fields = sorted((rect(n) for n in nodes if n.get("class", "").endswith("EditText") and rect(n)[1] > handle[3]), key=lambda r: r[1])
i = int(sys.argv[2])
if i >= len(fields):
    sys.exit(1)
l, t, r, b = fields[i]
print((l + r) // 2, (t + b) // 2)
PY
}

tag_contact() { # $1 = name, $2 = tag
  open_customize "$1"
  local point; point="$(sheet_field 1)" || die "no tags field in the customize sheet of $1"
  adb_t shell input tap $point
  adb_t shell input text "$2"
  adb_t shell input keyevent KEYCODE_ENTER
  sleep 2
  close_sheet
}

hide_contact() { # $1 = name
  open_customize "$1"
  local w h; read -r w h <<<"$(screen_size)"
  adb_t shell input swipe $((w / 2)) $((h * 85 / 100)) $((w / 2)) $((h / 2)) 300
  sleep 2
  tap_text "Search results" || die "no 'Show in' field in the customize sheet of $1"
  wait_text Never 10
  tap_text Never || die "could not choose Never for $1"
  sleep 2
  close_sheet
}

# The rows as the launcher wrote them, for the log: evidence, not an assertion.
rows() {
  adb -s "$SERIAL" shell "run-as $PKG --user $ZONE sqlite3 databases/room \"SELECT key, hidden FROM Searchable; SELECT key, type, value FROM CustomAttributes;\"" \
    </dev/null 2>&1 | tr -d '\r' | sed 's/^/     /' || true
}

# --- 1. boot, install for the zone ------------------------------------------------

(cd "$GOS_REPO" && emulator/device-lock.sh acquire "$LOCK_OWNER" "$SERIAL")
HAVE_LOCK=1
log "booting $SERIAL from snapshot '$SNAPSHOT' (overlays: $OVERLAY_DIR); host load $(cut -d' ' -f1-3 /proc/loadavg)"
SNAPSHOT="$SNAPSHOT" gos_run start
unrooted_shell
require_apk_package "$PKG" "$APK"
log "apk sha256 $(sha256sum "$APK" | cut -c1-12)"
ZONE="$(zone_uid)"
[ -n "$ZONE" ] || die "no full secondary user on this snapshot"
log "zone user $ZONE"
adb -s "$SERIAL" shell am start-user -w "$ZONE" </dev/null >/dev/null || die "could not start user $ZONE"
adb -s "$SERIAL" install -r "$APK" | grep -q Success || die "launcher install failed"
out="$(adb -s "$SERIAL" shell pm install-existing --user "$ZONE" "$PKG" </dev/null 2>&1 | tr -d '\r')"
case "$out" in *installed*) ;; *) die "pm install-existing --user $ZONE: $out" ;; esac
adb -s "$SERIAL" shell pm grant --user "$ZONE" "$PKG" android.permission.READ_CONTACTS </dev/null \
  || die "could not grant READ_CONTACTS in user $ZONE"
grant_home_role "$ZONE"

# --- 2. contacts ------------------------------------------------------------------

ALICE="$(new_contact Alice)"
BOB="$(new_contact Bob)"
new_contact Carol >/dev/null
log "contacts in user $ZONE (Alice raw $ALICE, Bob raw $BOB):"
contacts_now

# --- 3. customize, as a person would ------------------------------------------------

adb -s "$SERIAL" shell am switch-user "$ZONE" </dev/null >/dev/null || die "am switch-user $ZONE failed"
user_is_current() { [ "$(adb_t shell am get-current-user </dev/null 2>/dev/null | tr -d '\r')" = "$ZONE" ]; }
retry_for 30 user_is_current || die "user $ZONE did not become current"
wake_screen
zone_home
wait_desc Search 60 "the zone's launcher"

tag_contact Alice family
hide_contact Bob
log "rows as written:"
rows

# --- 4. the start -----------------------------------------------------------------------

check_split() { # $1 = phase
  expect_shown family Alice "$1: the tag is on Alice"
  expect_absent family Bob "$1: the tag is on nobody else"
  expect_absent Bob Bob "$1: Bob is hidden"
  expect_shown Alice Alice "$1: Alice is not hidden (control)"
  expect_shown Carol Carol "$1: Carol is not hidden (control)"
}
check_split "before the merge"

# --- 5. merged --------------------------------------------------------------------------

aggregate 1 "$ALICE" "$BOB"
log "merged:"
contacts_now
resume_launcher
log "rows after the resume:"
rows
expect_absent Bob Bob "merged: the merged contact is hidden, Bob's row hides it"
expect_shown Carol Carol "merged: Carol is not hidden (control)"

# --- 6. split again ---------------------------------------------------------------------

aggregate 2 "$ALICE" "$BOB"
log "split:"
contacts_now
resume_launcher
log "rows after the resume:"
rows
check_split "after the split"

ok "L4 contact keys passed"
