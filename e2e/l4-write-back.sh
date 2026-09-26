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
#   5. push a file with widgets on but no home.grid, move the dock in edit
#      mode, tap Done: the snackbar says the file does not manage the home
#      grid and how to make it, the report carries
#      write-back-skipped:grid-unmanaged, the file is byte for byte as pushed
#      (W1, (a): the file never grows a section)
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

# The settings row with this text, scrolled until tap_text can reach it
# (it refuses a row under the app bar or the navigation bar, #155): towards
# the middle when the row is on screen, else further down, then back up.
tap_setting() { # $1 = visible text
  local tries=0 bounds
  # not a wait: up to 8 scroll attempts, each lookup bounded through adb_t
  until tap_text "$1"; do
    tries=$((tries + 1))
    [ "$tries" -le 8 ] || die "the setting '$1' is not where a tap reaches it"
    bounds="$(node_bounds text "$1")" || bounds=""   # a failed dump: scroll and retry
    if [ -n "$bounds" ] && [ "$(awk '{ print int(($2 + $4) / 2) }' <<<"$bounds")" -lt 960 ]; then
      adb -s "$SERIAL" shell input swipe 540 700 540 1100 300   # high up: bring it down
    elif [ -n "$bounds" ] || [ "$tries" -le 3 ]; then
      adb -s "$SERIAL" shell input swipe 540 1500 540 700 300
    else
      adb -s "$SERIAL" shell input swipe 540 700 540 1500 300
    fi
    sleep 1
  done
}

open_grid_settings() {
  adb -s "$SERIAL" shell am start -W -n "$SETTINGS_ACTIVITY" >/dev/null
  wait_text "Grid and icons" 30
  tap_text "Grid and icons"
  wait_text "Show apps in a list" 30
}

# Wall-clock time through retry_for, on the library's dump_screen (#164).
screen_contains() { dump_screen && grep -qF "$1" "$WORK/dump.xml"; }
wait_text_containing() { # $1 = part of a visible text, $2 = timeout (s)
  retry_for "$2" screen_contains "$1" || die "timed out (${2}s) waiting for a text with '$1' on screen"
}

(cd "$GOS_REPO" && emulator/device-lock.sh acquire "$LOCK_OWNER" "$SERIAL")
HAVE_LOCK=1
log "booting $SERIAL from snapshot '$SNAPSHOT' (overlays: $OVERLAY_DIR)"
(cd "$GOS_REPO" && SNAPSHOT="$SNAPSHOT" emulator/run.sh start)
unrooted_shell
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
[ -z "${SHOTS:-}" ] || screenshot "$SHOTS/step4.png"
if ! (wait_report ".trigger == \"self-write\" and .configSha256 == \"$PUSHED_SHA\"" 30 "the write-back of the list switched off"); then
  printf 'read-back search.layout: %s\n' "$(query_json config | jq -c '.search.layout')" >&2
  printf 'last report: %s\n' "$(query_json diagnostics | jq -c '{trigger, configSha256, appliedMutations, diagnostics}')" >&2
  die "the write-back of the list switched off did not arrive"
fi
pull_config "$WORK/back.jsonc"
cmp -s "$WORK/pushed.jsonc" "$WORK/back.jsonc" \
  || { diff "$WORK/pushed.jsonc" "$WORK/back.jsonc" >&2 || true; die "the file is not the pushed one again"; }
config="$(query_json config)" || die "could not read back the config"
jq -e '.icons.enforceThemed == true' <<<"$config" >/dev/null || die "the device did not keep enforce themed icons"
ok "the file is the pushed one byte for byte; enforceThemed is on on the device and not in the file"

# --- 5. an edit on a file that does not manage the grid is kept, and said --
log "pushing a file without home.grid, then moving the dock in edit mode"
# Widgets on, so the grid and its default dock are on screen; the grid
# itself is left out, so the file does not manage it.
cat > "$WORK/nogrid.jsonc" <<'EOF'
{
  // this zone does not manage the home grid
  "schemaVersion": 2,
  "home": { "widgets": { "enabled": true } }
}
EOF
NOGRID_SHA="$(sha256sum "$WORK/nogrid.jsonc" | cut -d' ' -f1)"
push_config "$WORK/nogrid.jsonc" "no-grid"
show_home
wait_id grid-item:dock 60 "the default dock"
wake_screen
enter_edit_mode 4
drag_cell dock 0 -1 4
sleep 1
tap_id grid-edit-done
wait_text_containing "does not manage the home grid" 15
[ -z "${SHOTS:-}" ] || screenshot "$SHOTS/grid-unmanaged.png"
pull_config "$WORK/nogrid-after.jsonc"
cmp -s "$WORK/nogrid.jsonc" "$WORK/nogrid-after.jsonc" || die "the file without home.grid was changed"
assert_jq "$(query_json diagnostics)" \
  '.configSha256 == "'"$NOGRID_SHA"'" and (.diagnostics | any(.code == "write-back-skipped:grid-unmanaged"))' \
  "the report carries the grid-unmanaged skip for this file"
ok "grid-unmanaged: said on screen and in the report, the file byte for byte as pushed"

ok "l4-write-back passed"
