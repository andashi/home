#!/usr/bin/env bash
# L4 write-back test (#3 slice 4, ADR 0003 section 5): a change made in the
# launcher's own settings goes back into launcher.json on the GrapheneOS
# emulator, through the public surface only - the ingest provider, the
# reload broadcast, the read-back provider, `adb shell input` and
# `uiautomator dump`. Same harness as e2e/l4-grid.sh (ADR 0005).
#
#   e2e/l4-write-back.sh [path/to/app-default-debug.apk]
#
# Steps:
#   1. push a commented file that manages search.layout and icons.themed
#   2. switch "Show apps in a list" on in the settings: the report says
#      self-write, and the pulled file is the pushed one with exactly
#      `"layout": "grid"` turned into `"layout": "list"` - every other byte,
#      the comments included, as it was
#   3. idempotence: reloading the pulled file applies nothing
#   4. switch "Enforce themed icons" on, a key the file leaves out, then "Show
#      apps in a list" off again: the next self-write is the pushed file byte
#      for byte - the key the file left out was not added (W1)
#
# Everything runs as the unrooted shell, so the result holds for release
# GrapheneOS.
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
LOCK_OWNER="l4-write-back@$SERIAL#$$"
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

SETTINGS_ACTIVITY="$PKG/de.mm20.launcher2.ui.settings.SettingsActivity"

assert_jq() { # $1 = json, $2 = jq filter, $3 = description
  if ! jq -e "$2" >/dev/null 2>&1 <<<"$1"; then
    printf 'offending json:\n%s\n' "$1" >&2
    die "assertion failed: $3"
  fi
}

# The settings row with this text, scrolled into view: first further down,
# then back up, since an earlier tap may have left the list scrolled.
tap_setting() { # $1 = visible text
  local tries=0
  until tap_text "$1"; do
    tries=$((tries + 1))
    [ "$tries" -le 8 ] || die "the setting '$1' is not on screen"
    if [ "$tries" -le 3 ]; then
      adb -s "$SERIAL" shell input swipe 540 1500 540 700 300
    else
      adb -s "$SERIAL" shell input swipe 540 700 540 1500 300
    fi
  done
}

open_grid_settings() {
  adb -s "$SERIAL" shell am start -W -n "$SETTINGS_ACTIVITY" >/dev/null
  wait_text "Grid and icons" 30
  tap_text "Grid and icons"
  wait_text "Show apps in a list" 30
}

wait_text() { # $1 = visible text, $2 = timeout (s)
  local elapsed=0
  until [ -n "$(node_bounds text "$1")" ]; do
    [ "$elapsed" -lt "$2" ] || die "timed out (${2}s) waiting for '$1' on screen"
    sleep 1; elapsed=$((elapsed + 1))
  done
}

pull_config() { # $1 = local file
  adb -s "$SERIAL" pull "$DEVICE_CONFIG" "$1" >/dev/null 2>&1 || die "adb pull of $DEVICE_CONFIG failed"
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

# --- 1. a commented file -------------------------------------------------
log "pushing a commented file"
cat > "$WORK/pushed.jsonc" <<'EOF'
{
  // zone: home - managed by provisioning
  "schemaVersion": 2,
  "icons": { "themed": true }, /* the Clear look */
  "search": {
    "layout": "grid" // what this zone starts with
  }
}
EOF
PUSHED_SHA="$(sha256sum "$WORK/pushed.jsonc" | cut -d' ' -f1)"
push_config "$WORK/pushed.jsonc" "write-back"
ok "pushed and applied"

# --- 2. a setting changed in the settings goes into the file --------------
log "switching 'Show apps in a list' on in the settings"
open_grid_settings
tap_setting "Show apps in a list"
wait_report '.trigger == "self-write" and .success == true' 30 "the launcher's write-back of the setting"
assert_jq "$(query_json diagnostics)" '.appliedMutations == ["search.layout"]' "the self-write names search.layout"
pull_config "$WORK/written.jsonc"
sed 's/"layout": "grid"/"layout": "list"/' "$WORK/pushed.jsonc" > "$WORK/expected.jsonc"
cmp -s "$WORK/expected.jsonc" "$WORK/written.jsonc" \
  || { diff "$WORK/expected.jsonc" "$WORK/written.jsonc" >&2 || true; die "the file is not the pushed one with only the layout changed"; }
ok "the file says list, every other byte - comments included - as pushed"

# --- 3. the written file reloads as a no-op -------------------------------
log "reloading the written file"
WRITTEN_SHA="$(sha256sum "$WORK/written.jsonc" | cut -d' ' -f1)"
reload_broadcast
wait_report ".configSha256 == \"$WRITTEN_SHA\" and .trigger == \"broadcast\"" 30 "reload of the written file"
assert_jq "$(query_json diagnostics)" '.success == true and ((.appliedMutations // []) == [])' "the written file applies nothing"
ok "idempotence: the written file reloads as a no-op"

# --- 4. a key the file leaves out stays out --------------------------------
log "switching 'Enforce themed icons' on (not in the file), then the list off again"
tap_setting "Enforce themed icons"
tap_setting "Show apps in a list"
wait_report ".trigger == \"self-write\" and .configSha256 == \"$PUSHED_SHA\"" 30 "the write-back of the list switched off"
pull_config "$WORK/back.jsonc"
cmp -s "$WORK/pushed.jsonc" "$WORK/back.jsonc" \
  || { diff "$WORK/pushed.jsonc" "$WORK/back.jsonc" >&2 || true; die "the file is not the pushed one again"; }
config="$(query_json config)" || die "could not read back the config"
jq -e '.icons.enforceThemed == true' <<<"$config" >/dev/null || die "the device did not keep enforce themed icons"
ok "the file is the pushed one byte for byte; enforceThemed is on on the device and not in the file"

ok "l4-write-back passed"
