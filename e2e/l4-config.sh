#!/usr/bin/env bash
# L4 config test: validates the Phase-2 dotfiles config system (ADR 0003)
# end to end on the GrapheneOS emulator, against the same harness as
# e2e/l4-smoke.sh (ADR 0005).
#
#   e2e/l4-config.sh [path/to/kvaesitso.apk]
#
# What it does:
#   1. acquires the instance's device lock (as "l4-config@<serial>#<pid>"), boots
#      the test instance (default emulator-5556 with its own qcow2 overlays
#      under <gos-repo>/emulator/instances/test; SERIAL and OVERLAY_DIR pick
#      another one) from the `clean` snapshot, installs the debug APK
#   2. writes a known JSONC config (icons, glass, search bar,
#      favorites, widgets switch, grid; empty favorites so no
#      installed-package assumptions)
#      through the shell-gated ingest provider (`content write`), the
#      provisioning transport (ADR 0003 §1a)
#   3. proves the explicit, shell-gated ReloadConfigReceiver is reachable
#      from the unrooted shell: the watcher startup-check is allowed to settle first
#      (trigger "startup-check"), then the broadcast must produce a report
#      with trigger "broadcast" for the same config hash
#   4. reads back content://<pkg>.state/config and asserts the effective
#      fields with jq
#   5. re-writes the unchanged config and asserts the follow-up broadcast
#      report is successful with no applied mutations (watcher settled first)
#   6. writes a second config that changes EVERY section (icons off, other
#      glass values, search bar top, widgets off, other grid) and
#      asserts the read-back shows the new values and the report
#      lists every section as applied; then writes the first config again
#      and asserts the read-back is back to the first values - the
#      everyday case: an existing state is changed, not created
#   6b. uploads a generated image through the ingest (wallpapers/<name>),
#      writes a config with appearance.wallpaper, asserts the read-back names
#      the image and that the system wallpaper id changed; writes the same
#      config again and asserts no mutation and an unchanged id (idempotent)
#   7. writes malformed JSON and asserts a failed report with a
#      "malformed-json" error diagnostic, and that the previous effective
#      config remains intact
#   8. writes unknown keys in an otherwise valid config and asserts warning
#      diagnostics with a successful apply
#  10. writes a schemaVersion 1 file in the old shape (dock.favorites, the
#      widgets list) and asserts it still applies: the launcher migrates it and
#      the read-back shows schemaVersion 2 with home.favorites; its leftover
#      appearance.transparency gets one inert-key warning and no effect
#   9. restores the valid config with plain `adb push` (user 0 only): the
#      interactive dotfile path, proving the file watcher reacts to a push
#      exactly like to an ingest
#
# Report correlation: ReloadReport has no id/timestamp, so a new reload is
# detected via trigger and/or configSha256 transitions. Before every explicit
# broadcast the script waits for the file-watcher report of the write (trigger
# "file-watcher"), which both settles the watcher and validates it.
#
# Everything here runs as the unrooted shell (`adb unroot` after boot), so
# the result holds for release GrapheneOS, which has no `adb root`.
#
# Per-user isolation is covered by e2e/l4-provisioning-config.sh, which
# drives the real provisioning step against every profile.
#
# The emulator harness (run.sh, device-lock.sh, snapshots, overlays) lives in
# the provisioning repo — see docs/architecture/adr/0005-testing-strategy.md.
# The default APK path assumes a prior
#   ./gradlew :app:app:assembleDefaultDebug
set -euo pipefail

# The provisioning repo is a sibling checkout (<parent>/provisioning). Walking
# up from this script finds it from a git worktree too, where the script sits
# deeper than the checkout root. GOS_REPO overrides it for another layout, and
# $HOME/Development/GrapheneOS is the location before the 2026-09-20 move, kept
# as a fallback while it exists so runs work before and after that move.
gos_repo_default() {
  local d; d="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  while [ "$d" != "/" ]; do
    [ -d "$d/provisioning/emulator" ] && { printf '%s\n' "$d/provisioning"; return; }
    d="$(dirname "$d")"
  done
  printf '%s\n' "$HOME/Development/GrapheneOS"
}
GOS_REPO="${GOS_REPO:-$(gos_repo_default)}"
# One instance per session (README of the provisioning repo, "Emulator
# instances"): SERIAL and OVERLAY_DIR name the instance and always go together.
# Which of the two the caller set: both or neither, never one (checked below).
INSTANCE_OVERRIDE="${SERIAL:+s}${OVERLAY_DIR:+o}"
SERIAL="${SERIAL:-emulator-5556}"
export SERIAL
export OVERLAY_DIR="${OVERLAY_DIR:-$GOS_REPO/emulator/instances/test}"
# The instance runs writable: -read-only disables snapshots entirely, load
# included (provisioning repo, run.sh, READ_ONLY). Nothing carries over
# anyway, because run.sh start loads SNAPSHOT first, which resets RAM and
# disks, and nothing is ever saved back.

# Unique per run: acquire is re-entrant for the same owner, so two runs of
# this script on one instance must not share a name, or the second gets in
# and its cleanup stops the first one's emulator (#27).
LOCK_OWNER="l4-config@$SERIAL#$$"
SNAPSHOT="${SNAPSHOT:-clean}"
APK="${1:-$(dirname "$0")/../app/app/build/outputs/apk/default/debug/app-default-debug.apk}"
# Overridable: PKG=org.andashi.home APK=... runs the scenario against the release build.
PKG="${PKG:-org.andashi.home.debug}"
WALLPAPER_URI="content://$PKG.config-ingest/wallpapers"
REMOTE_DIR="/storage/emulated/0/Android/data/$PKG/files/config"
REMOTE_CONFIG="$REMOTE_DIR/launcher.json"

c(){ [ -t 1 ] && printf '\033[%sm%s\033[0m\n' "$1" "$2" || printf '%s\n' "$2"; }
log(){ c '1;34' ":: $*"; }; ok(){ c '1;32' " + $*"; }
die(){ c '1;31' " x $*" >&2; exit 1; }

[ "$INSTANCE_OVERRIDE" = "" ] || [ "$INSTANCE_OVERRIDE" = "so" ] \
  || die "SERIAL and OVERLAY_DIR name ONE instance - set both or neither (provisioning README, \"Emulator instances\"). Overriding only one runs one instance's disk under another instance's lock, because the lock is keyed by serial"
[ -d "$GOS_REPO/emulator" ] || die "provisioning repo not found at $GOS_REPO (set GOS_REPO)"
[ -f "$APK" ] || die "APK not found: $APK (build it or pass a path)"
command -v jq >/dev/null || die "jq not found (required for config assertions)"
command -v magick >/dev/null || die "ImageMagick (magick) not found (required to generate the wallpaper fixture)"

WORK="$(mktemp -d)"

# Only a run that holds the lock may stop the instance: a run whose acquire
# failed must not take down the one that holds it (#27).
HAVE_LOCK=0
cleanup() {
  local rc=$?
  if [ "$HAVE_LOCK" = 1 ]; then
    # A failed run leaves its evidence in logcat and nowhere else: dump the
    # launcher's config tags before the instance is stopped (the next run
    # starts from the snapshot again).
    if [ "$rc" -ne 0 ]; then
      printf '\n--- launcher logcat (config tags, last 80 lines) ---\n' >&2
      adb -s "$SERIAL" logcat -d -s ReloadConfigReceiver:* ConfigWatcher:* ConfigIngestProvider:* ConfigReloader:* AndroidRuntime:E ActivityManager:W 2>/dev/null \
        | tr -d '\r' | tail -n 80 >&2 || true
    fi
    (cd "$GOS_REPO" && SERIAL="$SERIAL" emulator/run.sh stop) >/dev/null 2>&1 || true
    (cd "$GOS_REPO" && emulator/device-lock.sh release "$LOCK_OWNER" "$SERIAL") >/dev/null 2>&1 || true
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

# The device helpers, their constants (STATE_URI, INGEST_URI, ...) and
# LAST_REPORT live in the shared library (#126).
# shellcheck source=lib/grid-device.sh
. "$(dirname "$0")/lib/grid-device.sh"

# --- adb helpers -------------------------------------------------------

write_wallpaper() { # $1 = local image, $2 = upload name
  local out
  out="$(adb -s "$SERIAL" shell content write --uri "$WALLPAPER_URI/$2" < "$1" 2>&1 | tr -d '\r')" \
    || { printf '%s\n' "$out" >&2; die "wallpaper content write failed"; }
  [ -z "$out" ] || { printf '%s\n' "$out" >&2; die "wallpaper content write reported an error"; }
}

# The system wallpaper id of user 0 from the "System wallpaper state:" section
# of `dumpsys wallpaper` (0 = default/none). Changes on every set; the fixture
# targets both, so the system id is the one that must move.
wallpaper_id() {
  adb -s "$SERIAL" shell dumpsys wallpaper 2>/dev/null | tr -d '\r' \
    | awk '/wallpaper state:/ { in_sec = (index($0, "System wallpaper state:") > 0); next }
           in_sec && index($0, "User 0:") { if (match($0, /id=[0-9]+/)) { print substr($0, RSTART+3, RLENGTH-3); exit } }'
}

# The interactive dotfile path (owner only - a secondary user's storage is
# not reachable for the shell, see ADR 0003 §1a). Not the library's
# push_config, which goes through the ingest provider and waits for the
# report: this one is `adb push` onto the file, and step 9 waits itself.
adb_push_config() { # $1 = local file
  adb -s "$SERIAL" shell "mkdir -p '$REMOTE_DIR'" >/dev/null
  adb -s "$SERIAL" push "$1" "$REMOTE_CONFIG" >/dev/null
}

# Pushes a config, waits for the file-watcher to settle (validates the
# watcher and avoids watcher/broadcast report races), then sends the explicit
# broadcast and waits for the resulting report.
settle_then_broadcast() { # $1 = local config file, $2 = sha256, $3 = stage name
  write_config "$1"
  log "$3: waiting for file-watcher reload (hash ${2:0:12}...)"
  wait_report ".configSha256 == \"$2\" and .trigger == \"file-watcher\"" 30 \
    "$3: file-watcher report"
  log "$3: broadcasting explicit reload"
  reload_broadcast
  wait_report ".configSha256 == \"$2\" and .trigger == \"broadcast\"" 30 \
    "$3: broadcast report"
}

# --- fixtures ----------------------------------------------------------

# JSONC on purpose (comments + trailing commas): ConfigParser must accept
# both. Empty dock favorites: the fixture must not assume any specific
# packages are installed.
VALID_CONFIG="$WORK/valid.jsonc"
cat > "$VALID_CONFIG" <<'EOF'
{
  // L4 test fixture: covers every config section.
  "schemaVersion": 2,
  "icons": {
    "themed": true,
    "enforceThemed": true,
  },
  "appearance": {
    // Not the defaults, so applying it is a real change.
    "glass": { "blur": 16, "tint": 0.5, "radius": 20, "contrast": "high", "wallpaperBlur": false, "searchWallpaperBlur": false },
  },
  // Four keys away from their defaults (#91), so applying them is a change.
  "search": { "favorites": false, "layout": "list", "reversed": true, "contacts": false, "barPosition": "bottom" },
  "home": {
    "searchBar": { "position": "bottom" },
    // Empty on purpose: favorites reference installed packages.
    "favorites": [],
    "widgets": { "enabled": true },
    "grid": {
      "columns": 4,
      "locked": false,
      "labels": false,
      "layouts": {
        "phone": { "items": [
          { "id": "dock", "widget": "favorites", "x": 0, "y": 5, "w": 4, "h": 1 },
        ] },
      },
    },
  },
}
EOF

UNKNOWN_KEYS_CONFIG="$WORK/unknown-keys.jsonc"
cat > "$UNKNOWN_KEYS_CONFIG" <<'EOF'
{
  "schemaVersion": 2,
  "futureTopLevelKey": { "anything": 1 },
  "icons": {
    "themed": true,
    "enforceThemed": true,
    "futureIconsKey": "ignored",
  },
  "appearance": {
    // Not the defaults, so applying it is a real change.
    "glass": { "blur": 16, "tint": 0.5, "radius": 20, "contrast": "high", "wallpaperBlur": false, "searchWallpaperBlur": false },
  },
  // Four keys away from their defaults (#91), so applying them is a change.
  "search": { "favorites": false, "layout": "list", "reversed": true, "contacts": false, "barPosition": "bottom" },
  "home": {
    "searchBar": { "position": "bottom" },
    "favorites": [],
    "widgets": { "enabled": true },
    "grid": {
      "columns": 4,
      "locked": false,
      "labels": false,
      "futureGridKey": true,
      "layouts": {
        "phone": { "items": [
          { "id": "dock", "widget": "favorites", "x": 0, "y": 5, "w": 4, "h": 1 },
        ] },
      },
    },
  },
}
EOF

# Every section differs from VALID_CONFIG, so converging from one to the
# other must touch all of them and the read-back must flip completely.
CHANGED_CONFIG="$WORK/changed.jsonc"
cat > "$CHANGED_CONFIG" <<'EOF'
{
  "schemaVersion": 2,
  "icons": {
    "themed": false,
    "enforceThemed": false,
  },
  "appearance": {
    "glass": { "blur": 32, "tint": 0.2, "radius": 12, "contrast": "low", "wallpaperBlur": true, "searchWallpaperBlur": true },
  },
  "search": { "favorites": true, "layout": "grid", "reversed": false, "contacts": true, "barPosition": "top" },
  "home": {
    "searchBar": { "position": "top" },
    "favorites": [],
    "widgets": { "enabled": false },
    "grid": {
      "columns": 5,
      "locked": true,
      "labels": true,
      "layouts": {
        "phone": { "items": [
          { "id": "dock", "widget": "favorites", "x": 0, "y": 0, "w": 5, "h": 2 },
        ] },
      },
    },
  },
}
EOF

# Wallpaper stage: VALID_CONFIG plus appearance.wallpaper, against a generated image.
WALLPAPER_IMAGE="$WORK/l4-wallpaper.png"
magick -size 320x640 gradient:navy-teal "$WALLPAPER_IMAGE"
# VALID_CONFIG is JSONC (comments, trailing commas), which jq cannot read, so
# the wallpaper variant is spelled out instead of derived.
WALLPAPER_CONFIG="$WORK/wallpaper.jsonc"
cat > "$WALLPAPER_CONFIG" <<'EOF'
{
  "schemaVersion": 2,
  "icons": { "themed": true, "enforceThemed": true },
  "appearance": {
    "glass": { "blur": 16, "tint": 0.5, "radius": 20, "contrast": "high", "wallpaperBlur": false, "searchWallpaperBlur": false },
    "wallpaper": { "image": "l4.png", "target": "both" },
  },
  "home": {
    "searchBar": { "position": "bottom" },
    "favorites": [],
    "widgets": { "enabled": true },
    "grid": {
      "columns": 4,
      "locked": false,
      "labels": false,
      "layouts": {
        "phone": { "items": [
          { "id": "dock", "widget": "favorites", "x": 0, "y": 5, "w": 4, "h": 1 },
        ] },
      },
    },
  },
}
EOF

# Schema version 1, the shape every provisioning zone pushed before #23: the
# launcher must migrate it rather than reject it (andashi/provisioning#2).
LEGACY_CONFIG="$WORK/legacy-v1.jsonc"
cat > "$LEGACY_CONFIG" <<'EOF'
{
  "schemaVersion": 1,
  "icons": { "themed": true, "enforceThemed": true },
  "appearance": {
    "transparency": { "background": 0.5, "surface": 0.7, "elevatedSurface": 0.9 },
  },
  "home": {
    "searchBar": { "position": "bottom" },
    "dock": { "enabled": true, "favorites": [] },
    "widgets": { "enabled": true, "widgets": ["apps"] },
  },
}
EOF

MALFORMED_CONFIG="$WORK/malformed.jsonc"
printf '{ "schemaVersion": 2, "icons": { not json at all\n' > "$MALFORMED_CONFIG"

H_VALID="$(sha256sum "$VALID_CONFIG" | cut -d' ' -f1)"
H_UNKNOWN="$(sha256sum "$UNKNOWN_KEYS_CONFIG" | cut -d' ' -f1)"
H_MALFORMED="$(sha256sum "$MALFORMED_CONFIG" | cut -d' ' -f1)"
H_CHANGED="$(sha256sum "$CHANGED_CONFIG" | cut -d' ' -f1)"
H_WALLPAPER="$(sha256sum "$WALLPAPER_CONFIG" | cut -d' ' -f1)"
H_LEGACY="$(sha256sum "$LEGACY_CONFIG" | cut -d' ' -f1)"

# #90: one file for phones and Folds. The Fold has seven rows, so its layout
# puts the dock in row 6 with row 5 taken; this phone has six. A phone must
# store the fold layout as written - no clamping, no grid-overflow.
FOLD_ROWS_CONFIG="$WORK/fold-rows.jsonc"
cat > "$FOLD_ROWS_CONFIG" <<'EOF'
{
  "schemaVersion": 2,
  "home": {
    "grid": {
      "columns": 4,
      "layouts": {
        "phone": { "items": [
          { "id": "dock", "widget": "favorites", "x": 0, "y": 5, "w": 4, "h": 1 },
        ] },
        "fold": { "items": [
          { "id": "clock", "widget": "com.android.deskclock/com.android.alarmclock.DigitalAppWidgetProvider", "x": 0, "y": 5, "w": 2, "h": 1 },
          { "id": "dock", "widget": "favorites", "x": 0, "y": 6, "w": 8, "h": 1 },
        ] },
      },
    },
  },
}
EOF
H_FOLD_ROWS="$(sha256sum "$FOLD_ROWS_CONFIG" | cut -d' ' -f1)"

# The /config read-back is fully populated (ConfigStateMapper), so these are
# the exact effective values after applying VALID_CONFIG.
EFFECTIVE_FILTER='
  .schemaVersion == 2
  and .icons.themed == true
  and .icons.enforceThemed == true
  and .appearance.glass == {"blur":16.0,"tint":0.5,"radius":20.0,"contrast":"high","wallpaperBlur":false,"searchWallpaperBlur":false}
  and (.appearance | has("transparency") | not)
  and (.search | del(.actions)) == {"favorites":false,"allApps":true,"layout":"list","labels":true,"contacts":false,"shortcuts":true,"filterBar":true,"openKeyboard":true,"launchOnEnter":true,"reversed":true,"hiddenItemsButton":false,"barPosition":"bottom","listIcons":true,"appDetails":true,"contactsCallOnTap":false}
  and .search.actions == [{"type":"call"},{"type":"message"},{"type":"email"},{"type":"contact"},{"type":"alarm"},{"type":"timer"},{"type":"calendar"},{"type":"website"},{"type":"websearch"}]
  and .home.searchBar.position == "bottom"
  and .home.favorites == []
  and .home.widgets.enabled == true
  and .home.grid.columns == 4
  and .home.grid.locked == false
  and .home.grid.labels == false
  and .home.grid.layouts.phone.items == [{"id":"dock","widget":"favorites","x":0,"y":5,"w":4,"h":1,"borderless":false,"background":true,"themeColors":true}]
'

CHANGED_FILTER='
  .schemaVersion == 2
  and .icons.themed == false
  and .icons.enforceThemed == false
  and .appearance.glass == {"blur":32.0,"tint":0.2,"radius":12.0,"contrast":"low","wallpaperBlur":true,"searchWallpaperBlur":true}
  and (.search | del(.actions)) == {"favorites":true,"allApps":true,"layout":"grid","labels":true,"contacts":true,"shortcuts":true,"filterBar":true,"openKeyboard":true,"launchOnEnter":true,"reversed":false,"hiddenItemsButton":false,"barPosition":"top","listIcons":true,"appDetails":true,"contactsCallOnTap":false}
  and .home.searchBar.position == "top"
  and .home.favorites == []
  and .home.widgets.enabled == false
  and .home.grid.columns == 5
  and .home.grid.locked == true
  and .home.grid.labels == true
  and .home.grid.layouts.phone.items == [{"id":"dock","widget":"favorites","x":0,"y":0,"w":5,"h":2,"borderless":false,"background":true,"themeColors":true}]
'

# The sections a full VALID <-> CHANGED convergence must report as applied.
ALL_SECTIONS_FILTER='
  ((.appliedMutations // []) | sort) ==
  ["appearance.glass", "home.grid", "home.searchBar",
   "home.widgets.enabled", "icons", "search"]
'

# --- 1. boot + install -------------------------------------------------

(cd "$GOS_REPO" && emulator/device-lock.sh acquire "$LOCK_OWNER" "$SERIAL")
HAVE_LOCK=1

log "booting $SERIAL from snapshot '$SNAPSHOT' (overlays: $OVERLAY_DIR)"
(cd "$GOS_REPO" && SNAPSHOT="$SNAPSHOT" emulator/run.sh start)

# Release GrapheneOS has no adb root; everything below must work as shell.
unrooted_shell

log "installing $(basename "$APK")"
install_out="$(adb -s "$SERIAL" install -r "$APK" 2>&1)" || { printf '%s\n' "$install_out" >&2; die "adb install failed"; }
case "$install_out" in
  *Success*) ;;
  *) printf '%s\n' "$install_out" >&2; die "adb install failed" ;;
esac

adb -s "$SERIAL" shell pm list packages | tr -d '\r' | grep -x "package:$PKG" >/dev/null \
  || die "$PKG not installed"
ok "package installed: $PKG"

# --- 2./3. write known config, prove the explicit broadcast works ------

write_config "$VALID_CONFIG"

# The first provider query starts the app process (the provider is exported);
# the watcher's startup drift check then reloads on its own. Letting it settle
# first makes the subsequent broadcast unambiguous: trigger must flip from
# "startup-check" to "broadcast", which only the explicit receiver can cause.
# Generous timeout: cold process start plus Koin on the emulator.
# The ingest itself already started the process (the provider is exported),
# so the watcher may have caught the rename: accept either trigger.
log "waiting for the first reload of the ingested config (starts the app process)"
wait_report ".success == true and .configSha256 == \"$H_VALID\" and (.trigger == \"startup-check\" or .trigger == \"file-watcher\")" 90 \
  "startup-check or file-watcher report for valid config"
ok "ingested config applied (trigger=$(jq -r .trigger <<<"$LAST_REPORT"))"

log "broadcasting explicit reload to the shell-gated receiver"
reload_broadcast
wait_report ".success == true and .configSha256 == \"$H_VALID\" and .trigger == \"broadcast\"" 30 \
  "broadcast report for valid config"
ok "explicit broadcast reached the receiver as unrooted shell (trigger=broadcast)"

# --- 4. assert the effective config ------------------------------------

log "asserting effective config via $STATE_URI/config"
effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" "$EFFECTIVE_FILTER" "effective config matches pushed fixture"
ok "effective config matches (icons, glass, search bar, favorites, widgets, grid incl. labels)"

# Glass and labels are rendered since #75: a valid config carrying them has
# nothing to report.
assert_jq "$LAST_REPORT" \
  '[(.diagnostics // [])[] | select(.code == "inert-key")] | length == 0' \
  "glass and labels are applied, not inert"
ok "glass and labels applied (no inert-key)"

# --- 5. re-write unchanged config: no mutations -------------------------

settle_then_broadcast "$VALID_CONFIG" "$H_VALID" "re-write"
assert_jq "$LAST_REPORT" \
  '.success == true and ((.appliedMutations // []) == [])' \
  "re-write of unchanged config yields success with no applied mutations"
ok "unchanged re-write: successful, no applied mutations"

# --- 6. change every section, then change it back ----------------------

settle_then_broadcast "$CHANGED_CONFIG" "$H_CHANGED" "change"
assert_jq "$LAST_REPORT" '.success == true' "changed config applied"
effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" "$CHANGED_FILTER" "effective config shows the changed values in every section"
ok "changed config: every section flipped (icons, glass, search, search bar, widgets, grid)"

settle_then_broadcast "$VALID_CONFIG" "$H_VALID" "change-back"
assert_jq "$LAST_REPORT" '.success == true' "original config re-applied"
effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" "$EFFECTIVE_FILTER" "effective config is back to the original values"
ok "change back: every section restored"

# Which sections a change touches is only visible on the reload that did the
# work, and the broadcast after settle is a no-op by design. So write the
# changed config once more and read the watcher's own report, the one reload
# that applied it.
write_config "$CHANGED_CONFIG"
wait_report ".success == true and .configSha256 == \"$H_CHANGED\" and ((.appliedMutations // []) | length) > 0" 30 \
  "the reload that applied the changed config"
assert_jq "$LAST_REPORT" "$ALL_SECTIONS_FILTER" "the applying reload lists every changed section"
ok "applied sections reported: $(jq -c '.appliedMutations' <<<"$LAST_REPORT")"
settle_then_broadcast "$VALID_CONFIG" "$H_VALID" "change-back-2"

# --- 6b. wallpaper: upload, apply, verify, idempotent -------------------

# Since ba37d7009 the launcher sets a wallpaper only while one of its
# activities is resumed (the system crops for the current user only, so a
# background set is deferred and reported as wallpaper-pending-foreground).
# On the clean snapshot the process runs but no activity does; bring the
# launcher to the front first, as a user unlocking the phone would.
log "starting the launcher activity (wallpapers apply only in the foreground)"
home_activity="$(adb -s "$SERIAL" shell cmd package resolve-activity --brief \
  -a android.intent.action.MAIN -c android.intent.category.HOME "$PKG" | tr -d '\r' | tail -1)"
case "$home_activity" in
  "$PKG"/*) ;;
  *) die "could not resolve the launcher's HOME activity (got '$home_activity')" ;;
esac
adb -s "$SERIAL" shell am start -W -n "$home_activity" >/dev/null || die "am start $home_activity failed"
ok "launcher in the foreground ($home_activity)"

id_before="$(wallpaper_id)"
write_wallpaper "$WALLPAPER_IMAGE" "l4.png"
settle_then_broadcast "$WALLPAPER_CONFIG" "$H_WALLPAPER" "wallpaper"
assert_jq "$LAST_REPORT" '.success == true' "wallpaper config applied"
effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" '.appearance.wallpaper.image == "l4.png" and .appearance.wallpaper.target == "both"' \
  "read-back names the applied wallpaper"
id_after="$(wallpaper_id)"
[ -n "$id_after" ] && [ "$id_after" != "0" ] && [ "$id_after" != "${id_before:-0}" ] \
  || die "system wallpaper id did not change (before='${id_before:-}', after='${id_after:-}')"
ok "wallpaper applied from config (system id ${id_before:-0} -> $id_after)"

settle_then_broadcast "$WALLPAPER_CONFIG" "$H_WALLPAPER" "wallpaper-rewrite"
assert_jq "$LAST_REPORT" '.success == true and ((.appliedMutations // []) == [])' \
  "re-write of the wallpaper config is a no-op"
[ "$(wallpaper_id)" = "$id_after" ] || die "wallpaper was set again although nothing changed"
ok "wallpaper re-write: no mutation, id unchanged ($id_after)"

# --- 7. malformed JSON: failed report, state intact --------------------

settle_then_broadcast "$MALFORMED_CONFIG" "$H_MALFORMED" "malformed"
assert_jq "$LAST_REPORT" \
  '.success == false and ([.diagnostics[] | select(.severity == "error" and .code == "malformed-json")] | length > 0)' \
  "malformed config yields a failed report with a malformed-json error"
ok "malformed config rejected with malformed-json diagnostic"

effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" "$EFFECTIVE_FILTER" "effective config intact after malformed push"
ok "previous effective config intact"

# --- 8. unknown keys: warnings, successful apply -----------------------

settle_then_broadcast "$UNKNOWN_KEYS_CONFIG" "$H_UNKNOWN" "unknown-keys"
assert_jq "$LAST_REPORT" \
  '.success == true and ([.diagnostics[] | select(.severity == "warning" and .code == "unknown-key")] | length >= 3)' \
  "unknown keys yield warning diagnostics and a successful apply"
ok "unknown keys: warnings recorded, apply successful"

effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" "$EFFECTIVE_FILTER" "effective config unchanged by unknown keys"
ok "effective config unchanged (unknown keys ignored)"

# --- 10. schema version 1: migrated, not rejected ------------------------

settle_then_broadcast "$LEGACY_CONFIG" "$H_LEGACY" "legacy-v1"
assert_jq "$LAST_REPORT" \
  '.success == true and ([.diagnostics[] | select(.severity == "error")] | length == 0)' \
  "a schemaVersion 1 file applies without errors"
effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" \
  '.schemaVersion == 2 and .home.favorites == [] and .home.widgets.enabled == true and (.home | has("dock") | not)' \
  "read-back of a migrated v1 file is schema version 2 with home.favorites"
ok "schemaVersion 1 file migrated (dock.favorites -> favorites, no dock in the read-back)"

# The legacy fixture still carries appearance.transparency, which left the
# contract with #73: one inert-key warning for the section, and the glass
# values in effect are untouched by it.
assert_jq "$LAST_REPORT" \
  '[.diagnostics[] | select(.code == "inert-key" and .path == "appearance.transparency")] | length == 1' \
  "a file that still carries transparency gets exactly one inert-key diagnostic"
assert_jq "$effective" \
  '.appearance.glass == {"blur":16.0,"tint":0.5,"radius":20.0,"contrast":"high","wallpaperBlur":false,"searchWallpaperBlur":false} and (.appearance | has("transparency") | not)' \
  "transparency has no effect: glass unchanged, transparency not served"
ok "transparency: reported inert, no effect, not served back"

# --- 10b. a fold layout with the Fold's seventh row, on a phone (#90) -----

settle_then_broadcast "$FOLD_ROWS_CONFIG" "$H_FOLD_ROWS" "fold-rows"
assert_jq "$LAST_REPORT" \
  '.success == true and ([(.diagnostics // [])[] | select(.code | startswith("grid-"))] | length == 0)' \
  "a fold layout using the Fold's seventh row applies on a phone without a grid diagnostic"
effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" \
  '[.home.grid.layouts.fold.items[] | select(.id == "dock") | [.x, .y, .w, .h]] == [[0, 6, 8, 1]]' \
  "the phone stores the fold layout as written: the dock stays in row 6"
ok "fold layout kept as written on a phone (#90)"

# --- 9. restore a valid config via adb push (interactive dotfile path) ---

adb_push_config "$VALID_CONFIG"
log "restore: waiting for file-watcher reload of the pushed file (hash ${H_VALID:0:12}...)"
wait_report ".success == true and .configSha256 == \"$H_VALID\" and .trigger == \"file-watcher\"" 30 \
  "restore: file-watcher report after adb push"
effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" "$EFFECTIVE_FILTER" "effective config restored via adb push"
ok "valid config restored via adb push (file watcher)"

ok "L4 config passed"
