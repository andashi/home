#!/usr/bin/env bash
# L4 scenario: search.actions configures the search-action chips (#106).
# On the GrapheneOS emulator, through the launcher's public surface only:
#
#   SERIAL=emulator-5556 OVERLAY_DIR=<gos-repo>/emulator/instances/test \
#     e2e/l4-search-actions.sh [path/to/app-default-debug.apk]
#
# 1. push three actions: the neutral web search, a URL search pinned to
#    Vanadium ("Pinned") and one pinned to Settings, which opens no URL
#    ("Nowhere"); the read-back serves the list;
# 2. query: exactly these chips are shown (no YouTube, no Google Play);
# 3. tap "Pinned": Vanadium comes to the front;
# 4. tap "Nowhere": nothing opens, the launcher stays in front - a pinned
#    search never falls back to the default browser;
# 5. push `[]`: no chip is shown.
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
LOCK_OWNER="l4-search-actions@$SERIAL#$$"
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

# The package of the activity in front.
front_package() {
  adb -s "$SERIAL" shell dumpsys activity activities | tr -d '\r' \
    | awk '/topResumedActivity|mResumedActivity/ { for (i = 1; i <= NF; i++) if ($i ~ /\//) { split($i, a, "/"); print a[1]; exit } }'
}

# Opens search from the home screen and types $1.
query() {
  show_home
  wait_desc Search 60 "the launcher's search bar"
  tap_desc Search
  sleep 2
  adb -s "$SERIAL" shell input text "$1"
  sleep 3
}

# The chips on screen that this scenario knows, sorted, on one line.
chips() {
  adb -s "$SERIAL" shell uiautomator dump /sdcard/a.xml >/dev/null 2>&1 || die "uiautomator dump failed"
  adb -s "$SERIAL" shell cat /sdcard/a.xml | tr -d '\r' > "$WORK/a.xml"
  [ -s "$WORK/a.xml" ] || die "empty uiautomator dump"
  python3 - "$WORK/a.xml" <<'PY'
import sys
try:
    import defusedxml.ElementTree as ET
except ImportError:
    import xml.etree.ElementTree as ET
names = {"Web search", "Pinned", "Nowhere", "YouTube", "Google Play"}
found = sorted({n.get("text") for n in ET.parse(sys.argv[1]).getroot().iter("node") if n.get("text") in names})
print(" | ".join(found))
PY
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

log "pushing three actions"
cat > "$WORK/actions.json" <<'JSON'
{
  "schemaVersion": 2,
  "search": { "openKeyboard": false, "actions": [
    { "type": "websearch" },
    { "type": "url", "label": "Pinned", "url": "https://example.org/?q=${1}", "package": "app.vanadium.browser" },
    { "type": "url", "label": "Nowhere", "url": "https://example.org/?q=${1}", "package": "com.android.settings" }
  ] }
}
JSON
push_config "$WORK/actions.json" "actions"
config="$(query_json config)" || die "could not read back the config"
jq -e '.search.actions == [
  {"type":"websearch"},
  {"type":"url","label":"Pinned","url":"https://example.org/?q=${1}","package":"app.vanadium.browser","encoding":"url"},
  {"type":"url","label":"Nowhere","url":"https://example.org/?q=${1}","package":"com.android.settings","encoding":"url"}
]' <<<"$config" >/dev/null || { jq -c '.search.actions' <<<"$config" >&2; die "the read-back does not serve the pushed actions"; }
ok "read-back: the three actions, in order"

query andashi
shown="$(chips)"
[ "$shown" = "Nowhere | Pinned | Web search" ] || die "chips on screen: '$shown'"
ok "chips: $shown"

tap_text Pinned || die "no Pinned chip"
sleep 4
front="$(front_package)"
[ "$front" = "app.vanadium.browser" ] || die "the pinned chip brought '$front' to the front, not Vanadium"
ok "Pinned opened Vanadium"

query andashi
tap_text Nowhere || die "no Nowhere chip"
sleep 4
front="$(front_package)"
[ "$front" = "$PKG" ] || die "a chip pinned to Settings brought '$front' to the front: it fell back to another app"
ok "Nowhere opened nothing: the launcher is still in front"

log "pushing no actions"
cat > "$WORK/none.json" <<'JSON'
{ "schemaVersion": 2, "search": { "openKeyboard": false, "actions": [] } }
JSON
push_config "$WORK/none.json" "no-actions"
jq -e '.search.actions == []' <<<"$(query_json config)" >/dev/null || die "the read-back is not an empty list"
query andashi
shown="$(chips)"
[ -z "$shown" ] || die "chips still on screen: '$shown'"
ok "no chips"

ok "l4-search-actions passed"
