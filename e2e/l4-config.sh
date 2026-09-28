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
#   2. first ingests a file with one favorite only (Settings, which every
#      Android has) and asserts that the very first reload, right after the
#      process starts, applies it (the startup race: nothing read yet read
#      as "not installed"); then writes a known JSONC config (icons, glass,
#      theme, search bar, favorites, widgets switch, grid, gestures)
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
#      glass values, another theme, search bar top, widgets off, other grid) and
#      asserts the read-back shows the new values and the report
#      lists every section as applied; then writes the first config again
#      and asserts the read-back is back to the first values - the
#      everyday case: an existing state is changed, not created
#   6b. uploads a generated image through the ingest (wallpapers/<name>),
#      writes a config with appearance.wallpaper, asserts the read-back names
#      the image and that the system wallpaper id changed; writes the same
#      config again and asserts no mutation and an unchanged id (idempotent)
#   6c. swipes left on the home screen and asserts Settings, the app
#      gestures.swipeLeft names, comes to the front
#   7. writes malformed JSON and asserts a failed report with a
#      "malformed-json" error diagnostic, and that the previous effective
#      config remains intact
#   8. writes unknown keys in an otherwise valid config and asserts warning
#      diagnostics with a successful apply
#  10. writes a schemaVersion 1 file in the old shape (dock.favorites, the
#      widgets list) and asserts it still applies: the launcher migrates it and
#      the read-back shows schemaVersion 2 with home.favorites; its leftover
#      appearance.transparency gets one inert-key warning and no effect
#  10c. names an app that is not there (Clock, disabled for user 0),
#      then enables it: the arrival reloads the file (trigger "apps-changed")
#      and the label and the favorite read back; then an app coming back while
#      nothing waits must not reload
#  10f. names an icon pack that is not there for an app's icon and a tag's,
#      with two tags: the tags apply at once, the pack icons are reported;
#      then installs a fixture pack and asserts each icon reads back as the
#      drawable the file named (not the pack's own match)
#   9. restores the valid config with plain `adb push` (user 0 only): the
#      interactive dotfile path, proving the file watcher reacts to a push
#      exactly like to an ingest
#
# Report correlation: a push's report is found by hash and freshness
# (push_config, wait_push_report). The trigger is asserted only where a step
# claims a cause: the broadcast reaching the receiver, the watcher picking up
# an adb push.
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
export LOCK_OWNER="l4-config@$SERIAL#$$"
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
    stop_and_release || STOP_FAILED=1  # prints why, and keeps the lock
  fi
  rm -rf "$WORK"
  [ "${STOP_FAILED:-0}" = 0 ] || exit 1  # a run that leaves its instance up is not green
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
    // All five away from their defaults (#3 slice 3, slice 4, #229).
    "theme": { "mode": "dark", "colors": "high-contrast", "shapes": "extra-round", "typography": "serif", "colorSource": "wallpaper" },
    // #3 slice 1: both bars away from their defaults.
    "systemBars": {
      "statusBar": { "hidden": true, "icons": "dark" },
      "navigationBar": { "hidden": false, "icons": "light" },
    },
  },
  // Four keys away from their defaults (#91), so applying them is a change.
  "search": { "favorites": false, "layout": "list", "reversed": true, "contacts": false, "barPosition": "bottom" },
  // #3 slice 2: six of seven away from their defaults, swipeRight left out
  // (unmanaged). Settings is on every Android, so the app assumes nothing.
  "gestures": {
    "swipeDown": "notifications",
    "swipeUp": "quick-settings",
    "swipeLeft": { "packageName": "com.android.settings" },
    "doubleTap": "none",
    "longPress": "launcher-settings",
    "homeButton": "search",
  },
  "home": {
    "searchBar": { "position": "bottom", "fixed": true },
    "lockRotation": true,
    // One app every Android has: the first reload after the process
    // starts must find it installed.
    "favorites": ["com.android.settings"],
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
    // All five away from their defaults (#3 slice 3, slice 4, #229).
    "theme": { "mode": "dark", "colors": "high-contrast", "shapes": "extra-round", "typography": "serif", "colorSource": "wallpaper" },
  },
  // Four keys away from their defaults (#91), so applying them is a change.
  "search": { "favorites": false, "layout": "list", "reversed": true, "contacts": false, "barPosition": "bottom" },
  "home": {
    "searchBar": { "position": "bottom" },
    "favorites": ["com.android.settings"],
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
    "theme": { "mode": "light", "colors": "black-and-white", "shapes": "cut", "typography": "monospace", "colorSource": "system" },
    "systemBars": {
      "statusBar": { "hidden": false, "icons": "light" },
      "navigationBar": { "hidden": true, "icons": "dark" },
    },
  },
  "search": { "favorites": true, "layout": "grid", "reversed": false, "contacts": true, "barPosition": "top" },
  // Three need the accessibility service, which is off on the clean snapshot.
  "gestures": {
    "swipeDown": "search",
    "swipeUp": "recents",
    "swipeLeft": "power-menu",
    "doubleTap": "screen-lock",
    "longPress": "none",
    "homeButton": "none",
  },
  "home": {
    "searchBar": { "position": "top", "fixed": false },
    "lockRotation": false,
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

# The first file the fresh install ever sees: one favorite and nothing
# else. This asserts the behaviour; it is not a reliable detector of the
# startup race it was added for. Ingested the moment the install returns, a
# file like this was skipped 5 of 5 times before the fix and 0 of 5 after,
# but within this scenario the pre-fix build passed twice: the window is
# under a second, and the steps between install and ingest close it. The
# unit tests (AppRepositoryTest, ProfileManagerTest, DefaultConfigStoreTest)
# are what guards the fix.
FIRST_CONFIG="$WORK/first.json"
printf '{ "schemaVersion": 2, "home": { "favorites": ["com.android.settings"] } }\n' > "$FIRST_CONFIG"
H_FIRST="$(sha256sum "$FIRST_CONFIG" | cut -d' ' -f1)"

H_VALID="$(sha256sum "$VALID_CONFIG" | cut -d' ' -f1)"
H_CHANGED="$(sha256sum "$CHANGED_CONFIG" | cut -d' ' -f1)"

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

# The /config read-back is fully populated (ConfigStateMapper), so these are
# the exact effective values after applying VALID_CONFIG. Whole objects on
# purpose: they pin that the read-back is complete, so a new key updates these
# filters in its own PR (a file made elsewhere is compared only on the keys it
# writes: #188, #200).
EFFECTIVE_FILTER='
  .schemaVersion == 2
  and .icons.themed == true
  and .icons.enforceThemed == true
  and .appearance.glass == {"blur":16.0,"tint":0.5,"radius":20.0,"contrast":"high","wallpaperBlur":false,"searchWallpaperBlur":false}
  and .appearance.theme == {"mode":"dark","colors":"high-contrast","shapes":"extra-round","typography":"serif","colorSource":"wallpaper"}
  and (.appearance | has("transparency") | not)
  and (.search | del(.actions)) == {"favorites":false,"allApps":true,"layout":"list","labels":true,"contacts":false,"shortcuts":true,"filterBar":true,"openKeyboard":true,"launchOnEnter":true,"reversed":true,"hiddenItemsButton":false,"barPosition":"bottom","listIcons":true,"appDetails":true,"contactsCallOnTap":false,"frequentlyUsed":true,"frequentlyUsedRows":1,"favoritesEditButton":true,"compactTags":false,"transliterator":"auto","defaultFilter":["apps","shortcuts","contacts"],"filterBarItems":["apps","shortcuts","contacts","hidden"],"shortcutsExcluded":[]}
  and .search.actions == [{"type":"call"},{"type":"message"},{"type":"email"},{"type":"contact"},{"type":"alarm"},{"type":"timer"},{"type":"calendar"},{"type":"website"},{"type":"websearch"}]
  and .home.searchBar.position == "bottom"
  and .home.searchBar.fixed == true
  and .home.lockRotation == true
  and .appearance.systemBars == {"statusBar":{"hidden":true,"icons":"dark"},"navigationBar":{"hidden":false,"icons":"light"}}
  and .home.favorites == [{"packageName":"com.android.settings"}]
  and .home.widgets.enabled == true
  and .home.grid.columns == 4
  and .home.grid.locked == false
  and .home.grid.labels == false
  and .home.grid.layouts.phone.items == [{"id":"dock","widget":"favorites","x":0,"y":5,"w":4,"h":1,"borderless":false,"background":true,"themeColors":true,"mute":false}]
  and .gestures == {"swipeDown":"notifications","swipeUp":"quick-settings","swipeLeft":{"packageName":"com.android.settings"},"swipeRight":"none","doubleTap":"none","longPress":"launcher-settings","homeButton":"search"}
'

CHANGED_FILTER='
  .schemaVersion == 2
  and .icons.themed == false
  and .icons.enforceThemed == false
  and .appearance.glass == {"blur":32.0,"tint":0.2,"radius":12.0,"contrast":"low","wallpaperBlur":true,"searchWallpaperBlur":true}
  and .appearance.theme == {"mode":"light","colors":"black-and-white","shapes":"cut","typography":"monospace","colorSource":"system"}
  and (.search | del(.actions)) == {"favorites":true,"allApps":true,"layout":"grid","labels":true,"contacts":true,"shortcuts":true,"filterBar":true,"openKeyboard":true,"launchOnEnter":true,"reversed":false,"hiddenItemsButton":false,"barPosition":"top","listIcons":true,"appDetails":true,"contactsCallOnTap":false,"frequentlyUsed":true,"frequentlyUsedRows":1,"favoritesEditButton":true,"compactTags":false,"transliterator":"auto","defaultFilter":["apps","shortcuts","contacts"],"filterBarItems":["apps","shortcuts","contacts","hidden"],"shortcutsExcluded":[]}
  and .home.searchBar.position == "top"
  and .home.searchBar.fixed == false
  and .home.lockRotation == false
  and .appearance.systemBars == {"statusBar":{"hidden":false,"icons":"light"},"navigationBar":{"hidden":true,"icons":"dark"}}
  and .home.favorites == []
  and .home.widgets.enabled == false
  and .home.grid.columns == 5
  and .home.grid.locked == true
  and .home.grid.labels == true
  and .home.grid.layouts.phone.items == [{"id":"dock","widget":"favorites","x":0,"y":0,"w":5,"h":2,"borderless":false,"background":true,"themeColors":true,"mute":false}]
  and .gestures == {"swipeDown":"search","swipeUp":"recents","swipeLeft":"power-menu","swipeRight":"none","doubleTap":"screen-lock","longPress":"none","homeButton":"none"}
'

# The sections a full VALID <-> CHANGED convergence must report as applied.
ALL_SECTIONS_FILTER='
  ((.appliedMutations // []) | sort) ==
  ["appearance.glass", "appearance.systemBars", "appearance.theme", "gestures", "home.favorites", "home.grid",
   "home.lockRotation", "home.searchBar", "home.widgets.enabled", "icons", "search"]
'

# --- 1. boot + install -------------------------------------------------

(cd "$GOS_REPO" && emulator/device-lock.sh acquire "$LOCK_OWNER" "$SERIAL")
HAVE_LOCK=1

log "booting $SERIAL from snapshot '$SNAPSHOT' (overlays: $OVERLAY_DIR)"
SNAPSHOT="$SNAPSHOT" gos_run start

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

write_config "$FIRST_CONFIG"

# The ingest starts the app process (the provider is exported), and the
# startup drift check or the watcher reloads the file. The install is fresh,
# so there is no report before this one: any report of the hash is the
# ingest's, whatever caused it. Generous timeout: cold process start plus
# Koin on the emulator.
log "waiting for the first reload of the ingested config (starts the app process)"
wait_push_report null "$H_FIRST" 90 "first report of the ingested config"
# The first reload runs right after the process starts. Before the startup
# race was fixed it read "no apps read yet" as "not installed" and skipped
# the favorite; a second reload applied it, which is what hid the defect.
assert_jq "$LAST_REPORT" \
  '[(.diagnostics // [])[] | select(.code == "favorite-unavailable" or .code == "profile-unavailable")] | length == 0' \
  "the first reload after the process starts finds the favorite installed"
assert_jq "$LAST_REPORT" '.success == true' "first ingested config applied"
ok "first reload after the process starts applied the favorite (trigger=$(jq -r .trigger <<<"$LAST_REPORT"))"

# Settled, so the broadcast below is unambiguous: its trigger must flip to
# "broadcast", which only the explicit receiver can cause.
push_config "$VALID_CONFIG" "valid"
assert_jq "$LAST_REPORT" '.success == true' "ingested config applied"
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

push_config "$VALID_CONFIG" "re-write"
assert_jq "$LAST_REPORT" \
  '.success == true and ((.appliedMutations // []) == [])' \
  "re-write of unchanged config yields success with no applied mutations"
ok "unchanged re-write: successful, no applied mutations"

# --- 6. change every section, then change it back ----------------------

push_config "$CHANGED_CONFIG" "change"
assert_jq "$LAST_REPORT" '.success == true' "changed config applied"
effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" "$CHANGED_FILTER" "effective config shows the changed values in every section"
ok "changed config: every section flipped (icons, glass, search, search bar, widgets, grid, gestures)"
# The three gestures that need the accessibility service are applied and
# reported: the clean snapshot has it off, and a file must not turn it on.
assert_jq "$LAST_REPORT" \
  '[(.diagnostics // [])[] | select(.code == "permission-missing" and .severity == "warning" and (.path | startswith("gestures."))) | .path] | sort == ["gestures.doubleTap","gestures.swipeLeft","gestures.swipeUp"]' \
  "screen-lock, power-menu and recents are reported while the accessibility service is off"
ok "gestures needing the accessibility service reported (permission-missing)"

push_config "$VALID_CONFIG" "change-back"
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
push_config "$VALID_CONFIG" "change-back-2"

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
# By the HOME intent, as the system does; by component the launcher would
# land in a task of its own that the first Home press replaces.
adb -s "$SERIAL" shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME "$PKG" >/dev/null \
  || die "am start of the HOME intent for $PKG failed"
ok "launcher in the foreground ($home_activity)"

id_before="$(wallpaper_id)"
write_wallpaper "$WALLPAPER_IMAGE" "l4.png"
push_config "$WALLPAPER_CONFIG" "wallpaper"
assert_jq "$LAST_REPORT" '.success == true' "wallpaper config applied"
effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" '.appearance.wallpaper.image == "l4.png" and .appearance.wallpaper.target == "both"' \
  "read-back names the applied wallpaper"
id_after="$(wallpaper_id)"
[ -n "$id_after" ] && [ "$id_after" != "0" ] && [ "$id_after" != "${id_before:-0}" ] \
  || die "system wallpaper id did not change (before='${id_before:-}', after='${id_after:-}')"
ok "wallpaper applied from config (system id ${id_before:-0} -> $id_after)"

push_config "$WALLPAPER_CONFIG" "wallpaper-rewrite"
assert_jq "$LAST_REPORT" '.success == true and ((.appliedMutations // []) == [])' \
  "re-write of the wallpaper config is a no-op"
[ "$(wallpaper_id)" = "$id_after" ] || die "wallpaper was set again although nothing changed"
ok "wallpaper re-write: no mutation, id unchanged ($id_after)"

# --- 6c. a configured gesture does what the file says (#3 slice 2) -------

# VALID_CONFIG's swipeLeft opens Settings; the wallpaper config above leaves
# gestures unmanaged, so it is still in effect. The launcher is in front
# (6b); a swipe across the empty middle of the home screen must bring
# Settings up, resolved from the package the file names.
log "swiping left on the home screen: the file says it opens Settings"
# By the HOME intent, as 6b does since #208: started by component the
# launcher lands in a task of its own, and a gesture is lost when the first
# Home press replaces it.
start_home() {
  adb -s "$SERIAL" shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME "$PKG" >/dev/null \
    || die "am start of the HOME intent for $PKG failed"
}
# Only when it is not in front already: 6b left it there, and a HOME intent
# to the resumed launcher is a Home-button press, which VALID_CONFIG's
# homeButton: search answers by opening search - the feature working, and
# the keyboard's caret then keeps every frame counter moving.
retry_for 10 on_top "$PKG" || start_home
read -r width height < <(screen_size)
# Across most of the width, over 300 ms: under load `input swipe` gets two
# to four samples through, Compose then computes a velocity of zero, and
# only the distance crosses the threshold (AGENTS.md, emulator section).
flick_left() {
  adb -s "$SERIAL" shell input swipe $((width * 9 / 10)) $((height / 2)) $((width / 10)) $((height / 2)) 300
}
# Each flick waits for touch_ready: focused, no window transition, frames at
# rest - glass's conditions, one flick in twenty lost after them instead of
# every other one. The one left is the `input` tool starved by host load,
# which nothing in the guest can wait away; the second attempt covers that,
# and the output says on every run which attempt worked, so a count that
# climbs is visible. The launcher never dropped a flick that reached it;
# whether the platform delivers such a touch on real hardware is untested
# (AGENTS.md, emulator section). This step proves the config's effect: a
# flick opens exactly the app the file names.
settings_attempt=""
for attempt in 1 2; do
  retry_for 15 touch_ready || die "the launcher never became ready for a touch before flick $attempt ($TOUCH_READY_WHY)"
  flick_left
  if retry_for 5 on_top com.android.settings; then settings_attempt=$attempt; break; fi
done
[ -n "$settings_attempt" ] || die "two swipes left did not open Settings, the app gestures.swipeLeft names"
ok "gestures.swipeLeft opened Settings on the device (flick $settings_attempt of 2)"
start_home
retry_for 10 on_top "$PKG" || die "the launcher did not come back after the gesture"

# --- 7. malformed JSON: failed report, state intact --------------------

push_config "$MALFORMED_CONFIG" "malformed"
assert_jq "$LAST_REPORT" \
  '.success == false and ([.diagnostics[] | select(.severity == "error" and .code == "malformed-json")] | length > 0)' \
  "malformed config yields a failed report with a malformed-json error"
ok "malformed config rejected with malformed-json diagnostic"

effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" "$EFFECTIVE_FILTER" "effective config intact after malformed push"
ok "previous effective config intact"

# --- 8. unknown keys: warnings, successful apply -----------------------

# The declaration is the assertion: exactly these three keys are reported
# unknown, no fewer and no more.
push_config "$UNKNOWN_KEYS_CONFIG" "unknown-keys" \
  --ignored "futureTopLevelKey icons.futureIconsKey home.grid.futureGridKey"
assert_jq "$LAST_REPORT" \
  '.success == true and ([.diagnostics[] | select(.severity == "warning" and .code == "unknown-key")] | length >= 3)' \
  "unknown keys yield warning diagnostics and a successful apply"
ok "unknown keys: warnings recorded, apply successful"

effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" "$EFFECTIVE_FILTER" "effective config unchanged by unknown keys"
ok "effective config unchanged (unknown keys ignored)"

# --- 10. schema version 1: migrated, not rejected ------------------------

push_config "$LEGACY_CONFIG" "legacy-v1" --ignored "appearance.transparency"
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

push_config "$FOLD_ROWS_CONFIG" "fold-rows"
assert_jq "$LAST_REPORT" \
  '.success == true and ([(.diagnostics // [])[] | select(.code | startswith("grid-"))] | length == 0)' \
  "a fold layout using the Fold's seventh row applies on a phone without a grid diagnostic"
effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" \
  '[.home.grid.layouts.fold.items[] | select(.id == "dock") | [.x, .y, .w, .h]] == [[0, 6, 8, 1]]' \
  "the phone stores the fold layout as written: the dock stays in row 6"
ok "fold layout kept as written on a phone (#90)"

# --- 10c. a widget whose package is missing stays reported ----------------
#
# A grid widget whose provider this device lacks is kept as written and
# reported. It was reported once: the next reload of the same file found the
# stored layout matching the file, looked nothing up, and said nothing - the
# report went silent while nothing had resolved. push_config reloads twice (the
# watcher, then the broadcast); the broadcast's report is the second. (A third
# would report the same bytes, and a report is found by being new.) The widget comes from a fixture built
# from e2e/fixtures/widget-only and never installed here. A layout refitted on
# every reload must also not make a write-back on every reload: the passes are
# counted (the write-back logs each, on every build) and none may write.
# The item leaves out its size, so 10d can see the provider's own size arrive.
FIXTURE_PKG=org.andashi.fixture.widgetonly
FIXTURE_APK="$WORK/widget-only.apk"
bash "$(dirname "$0")/fixtures/widget-only/build.sh" "$FIXTURE_APK" >/dev/null \
  || die "could not build the widget-only fixture (e2e/fixtures/widget-only/build.sh); its message above names what is missing"
WIDGET_CONFIG="$WORK/widget-only.json"
cat > "$WIDGET_CONFIG" <<EOF
{ "schemaVersion": 2,
  "home": { "widgets": { "enabled": true },
    "grid": { "layouts": { "phone": { "items": [
    { "id": "only", "widget": "$FIXTURE_PKG/.OnlyWidget", "x": 0, "y": 0 }
  ] } } } } }
EOF
write_back_passes() { # $1 = kind ("" for every pass)
  adb_out shell logcat -d -s ConfigWriteBack:D 2>/dev/null | grep -cF "write-back pass: $1" || true
}
written_before="$(write_back_passes Written)"
passes_before="$(write_back_passes "")"
push_config "$WIDGET_CONFIG" "widget-missing"
assert_jq "$LAST_REPORT" \
  '[.diagnostics[] | select(.code == "unknown-widget-provider")] | length == 1' \
  "the second reload of the file still reports the missing widget"
H_WIDGET="$(sha256sum "$WIDGET_CONFIG" | cut -d' ' -f1)"
[ "$(write_back_passes Written)" = "$written_before" ] \
  || die "a reload of an unchanged file wrote the file back: $(write_back_passes Written) passes wrote, $written_before before"
# Hashed on the device: output taken through $(...) loses its trailing newline,
# so a copy read back never hashes like the file it came from.
on_device="$(adb_out shell sha256sum "$REMOTE_CONFIG" | cut -d' ' -f1)"
[ "$on_device" = "$H_WIDGET" ] || die "the file on the device changed under two reloads"
passes="$(( $(write_back_passes "") - passes_before ))"
effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" \
  '[.home.grid.layouts.phone.items[] | select(.id == "only") | [.w, .h]] == [[1, 1]]' \
  "without its provider the widget holds the one-cell fallback"
ok "a missing widget is reported by the second reload too, and the refits write nothing back ($passes write-back passes over the two reloads, none wrote)"

# The watcher logs each arrival it decided, on a debuggable build only (which
# packages a person has is theirs). A step waits for that line: a silence is
# only evidence once the signal has provably reached the decision - an arrival
# that never arrived reloads nothing too (review on #213).
arrivals_decided() { # $1 = package, $2 = decision ("reloaded" or "nothing waits")
  adb_out shell logcat -d -s ConfigWatcher:D 2>/dev/null | grep -cF "arrival $1${2:+: $2}" || true
}
arrival_decided() { [ "$(arrivals_decided "$1" "$2")" -gt "$3" ]; }

# --- 10d. the widget's package arrives (review on #213 and #219) -----------
#
# The file from 10c waits for its widget. The app list is launcher entries, so
# a package with a widget and no launcher entry arrives unseen there; only the
# package signal reports it. (No stock app is such a package, and the shell may
# not disable one component of a system app - "Shell cannot change component
# state" - hence the fixture.) The arrival must reload with the provider there,
# fit the item to the provider's own size (the store already agreeing with the
# file would otherwise never look again), and bind it: the grid binds only when
# an item's id or host id changes, and an arrival changes neither (review on
# #219). The app list must not have seen the package, or the package signal
# was not what carried it.
HOST_ID=44203 # the launcher's AppWidgetHost id
fixture_bound() {
  adb -s "$SERIAL" shell dumpsys appwidget 2>/dev/null | tr -d '\r' | sed -n '/^Widgets:/,/^Hosts:/p' \
    | grep -F "hostId:$HOST_ID" -A3 | grep -qF "$FIXTURE_PKG/"
}
# Binding happens in the grid on screen, so the grid is shown before the
# install: its own pass then fails for the missing provider, and only the
# arrival can bind it. Shown after the install instead, the grid's first pass
# would bind it with or without the arrival pass, and this step would pass
# on a build that lacks it.
# The steps before never needed the home role, and without it the Home key
# goes to the system's launcher; with it, as on a real device, the Home key
# returns this launcher to its grid from whatever was left open.
grant_home_role
# The HOME role does not carry the bind-widget grant (l4-grid measured it:
# every bind refused). A user gives it once through the system's dialog; the
# shell tool stands in for that tap.
adb -s "$SERIAL" shell appwidget grantbind --package "$PKG" --user 0 >/dev/null 2>&1 \
  || die "appwidget grantbind failed for $PKG"
adb -s "$SERIAL" shell dumpsys appwidget 2>/dev/null | tr -d '\r' | sed -n '/^Grants:/,$p' | grep -q "package=$PKG" \
  || die "$PKG has no bind-widget grant after grantbind"
show_home
adb_t shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
retry_for 30 shows id_bounds "grid-item:only" || {
  adb -s "$SERIAL" shell dumpsys activity activities 2>/dev/null | tr -d '\r' | grep -m3 -E "mResumedActivity|topResumedActivity" >&2 || true
  { wc -c < "$WORK/dump.xml"; grep -oE '(resource-id|text)="[^"]+"' "$WORK/dump.xml" | sort -u | head -40; } >&2 2>/dev/null || true
  die "the waiting widget's cell 'grid-item:only' never showed on the home grid (what was on screen is above)"
}
fixture_bound && die "the fixture's widget is bound before its package is installed; the check is blind"
seen="$(arrivals_decided "$FIXTURE_PKG" "reloaded")"
install_out="$(adb -s "$SERIAL" install "$FIXTURE_APK" 2>&1)" \
  || { printf '%s\n' "$install_out" >&2; die "could not install the widget-only fixture"; }
case "$install_out" in *Success*) ;; *) printf '%s\n' "$install_out" >&2; die "could not install the widget-only fixture" ;; esac
retry_for 30 arrival_decided "$FIXTURE_PKG" "reloaded" "$seen" \
  || die "the widget-only package's arrival never reloaded the file"
wait_report '.trigger == "apps-changed" and ([.diagnostics[]? | select(.code == "unknown-widget-provider")] | length == 0)' \
  30 "the arrival reload found the provider"
[ "$(arrivals_decided "app://$FIXTURE_PKG" "")" = 0 ] \
  || die "the app list reported the widget-only package; the package signal was not what carried it"
effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" \
  '[.home.grid.layouts.phone.items[] | select(.id == "only") | [.w, .h]] == [[2, 1]]' \
  "with its provider there the widget takes the provider's own size (2x1)"
retry_for 30 fixture_bound || {
  adb -s "$SERIAL" shell dumpsys appwidget 2>/dev/null | tr -d '\r' | sed -n '/^Widgets:/,$p' >&2
  die "the arrived widget was never bound to the launcher's host $HOST_ID (the widget service's list is above)"
}
ok "a package that brings only a widget is applied when it arrives: reloaded, sized 2x1 and bound, carried by the package signal alone"

# --- 10d2. mute: the fixture's widget grey, next to itself in colour (#78) --
#
# Two copies of the fixture's widget, one muted. Its layout is opaque and
# saturated (red ground, white text), so the hosted view covers its cell and no
# glass shows through - mute does not touch the glass. The prediction, written
# before the first run: the unmuted copy is mostly strongly coloured, the muted
# one is not, and the muted copy's dominant grey is the grey of the unmuted
# copy's dominant colour's own luminance (WidgetMute.grey: #E53935 -> 123),
# within a few levels. A matrix in gamma space would give 93 instead, so the
# luminance check tells the shader from the obvious wrong implementation.
MUTE_CONFIG="$WORK/mute.json"
cat > "$MUTE_CONFIG" <<EOF
{ "schemaVersion": 2,
  "home": { "widgets": { "enabled": true },
    "grid": { "layouts": { "phone": { "items": [
    { "id": "only", "widget": "$FIXTURE_PKG/.OnlyWidget", "x": 0, "y": 0, "w": 2, "h": 1 },
    { "id": "muted", "widget": "$FIXTURE_PKG/.OnlyWidget", "x": 2, "y": 0, "w": 2, "h": 1, "mute": true }
  ] } } } } }
EOF
push_config "$MUTE_CONFIG" "mute"
effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" \
  '[.home.grid.layouts.phone.items[] | {id, mute}] == [{"id":"only","mute":false},{"id":"muted","mute":true}]' \
  "mute reads back as written, and false where it is left out"
fixture_widgets_bound() {
  # Deadline-aware (adb_out): it runs inside retry_for, whose budget a hung
  # dumpsys must not overrun (review on #231).
  adb_out shell dumpsys appwidget 2>/dev/null | sed -n '/^Widgets:/,/^Hosts:/p' \
    | grep -F "hostId:$HOST_ID" -A3 | grep -cF "$FIXTURE_PKG/" || true
}
show_home
adb_t shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
retry_for 30 shows id_bounds "grid-item:muted" || die "the muted copy's cell never showed on the home grid"
retry_for 30 eval '[ "$(fixture_widgets_bound)" -ge 2 ]' || die "both copies of the fixture's widget were never bound"
mute_measure() { # prints: sat_only sat_muted grey_muted predicted
  local only muted
  only="$(id_bounds "grid-item:only")" && muted="$(id_bounds "grid-item:muted")" || return 1
  screenshot "$WORK/mute.png"
  python3 - "$WORK/mute.png" $only $muted <<'PY'
import sys
from collections import Counter
from PIL import Image
img = Image.open(sys.argv[1]).convert("RGB")
def inner(x1, y1, x2, y2):
    # The middle half of the cell: clear of the card's padding and corners.
    w, h = x2 - x1, y2 - y1
    return img.crop((x1 + w // 4, y1 + h // 4, x2 - w // 4, y2 - h // 4)).getdata()
def saturated(px): return sum(1 for r, g, b in px if max(r, g, b) - min(r, g, b) > 24) / len(px)
def lin(c):
    c /= 255
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
def grey(rgb):
    # WCAG relative luminance, re-encoded: the reference WidgetMute.grey implements.
    y = 0.2126 * lin(rgb[0]) + 0.7152 * lin(rgb[1]) + 0.0722 * lin(rgb[2])
    c = y * 12.92 if y <= 0.0031308 else 1.055 * y ** (1 / 2.4) - 0.055
    return round(c * 255)
b = [int(v) for v in sys.argv[2:]]
only, muted = list(inner(*b[:4])), list(inner(*b[4:]))
dominant_only = Counter(only).most_common(1)[0][0]
dominant_muted = Counter(muted).most_common(1)[0][0]
print(f"{saturated(only):.3f} {saturated(muted):.3f} {dominant_muted[0]} {grey(dominant_only)} {dominant_only} {dominant_muted}")
PY
}
mute_ok() {
  local line
  line="$(mute_measure)" || return 1
  MUTE_LINE="$line"
  read -r sat_only sat_muted grey_muted predicted _ <<<"$line"
  python3 -c "import sys; sys.exit(0 if $sat_only > 0.5 and $sat_muted < 0.02 and abs($grey_muted - $predicted) <= 4 else 1)"
}
retry_for 20 mute_ok || die "mute did not turn the widget into the grey of its own luminance (saturated only/muted, grey muted, predicted, colours): ${MUTE_LINE:-no measurement}"
cp "$WORK/mute.png" "$(dirname "$0")/screenshots/mute-side-by-side.png" 2>/dev/null || true
ok "mute: the muted copy is grey, and its grey is its colour's own luminance (saturated only/muted, grey, predicted, colours: $MUTE_LINE)"
adb -s "$SERIAL" uninstall "$FIXTURE_PKG" >/dev/null 2>&1 || true

# --- 10e. an app the file names is installed after the file (#207 review) ---
#
# A file naming an app this device lacks applies without it: the app's label
# and its favorite are skipped and reported. When the app arrives the file has
# not changed, so no watcher event and no startup drift would apply it; the
# arrival itself reloads. Clock is disabled for this user and enabled again:
# a disabled app is not in the launcher's list, and enabling it is an arrival
# like an install, with no outside APK. (Uninstalling a system app for one
# user needs root on GrapheneOS: "only root can delete system app for a
# particular user".) The control enables an app while nothing waits: a trigger that
# reloaded on every install would pass the first half and fail the second.

LATE_APP=com.android.deskclock
OTHER_APP=app.grapheneos.camera
pm_for_user0() { # $1 = disable-user|enable, $2 = package
  local out want
  case "$1" in disable-user) want="new state: disabled-user" ;; enable) want="new state: enabled" ;; *) return 2 ;; esac
  out="$(adb_t shell pm "$1" --user 0 "$2" 2>&1 | tr -d '\r')" || { printf '%s\n' "$out" >&2; return 1; }
  case "$out" in
    *"$want") return 0 ;;
    *) printf 'pm %s %s: %s\n' "$1" "$2" "$out" >&2; return 1 ;;
  esac
}
favorite_packages='[.home.favorites[]? | if type == "object" then .packageName else . end]'

pm_for_user0 disable-user "$LATE_APP" || die "could not disable $LATE_APP for user 0"
LATE_CONFIG="$WORK/late-app.json"
cat > "$LATE_CONFIG" <<EOF
{ "schemaVersion": 2,
  "apps": [ { "packageName": "$LATE_APP", "label": "Later Clock" } ],
  "home": { "favorites": ["com.android.settings", "$LATE_APP"] } }
EOF
push_config "$LATE_CONFIG" "late-app"
# Both are warnings, and the push succeeds: the file is fine, this device
# cannot honour it yet, and it heals when the app arrives (#215). A favorite
# for an absent app was an error before, which failed every such push.
assert_jq "$LAST_REPORT" \
  '.success == true
   and ([.diagnostics[] | select(.code == "app-unavailable" and .path == "apps[0]" and .severity == "warning")] | length == 1)
   and ([.diagnostics[] | select(.code == "favorite-unavailable" and .path == "home.favorites[1]" and .severity == "warning")] | length == 1)' \
  "the absent app's label and favorite are reported as warnings, not applied, and the push succeeds"
effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" ".apps == [] and ($favorite_packages | index(\"$LATE_APP\") == null)" \
  "neither the label nor the favorite reads back while the app is absent"

t0=$SECONDS
pm_for_user0 enable "$LATE_APP" || die "could not enable $LATE_APP for user 0"
wait_report '.trigger == "apps-changed" and .success == true
  and ([.diagnostics[]? | select(.code == "app-unavailable" or .code == "favorite-unavailable")] | length == 0)' \
  30 "the arrival reloads the file, and nothing is absent any more"
arrival=$((SECONDS - t0))
effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" \
  ".apps == [{\"packageName\":\"$LATE_APP\",\"label\":\"Later Clock\"}] and ($favorite_packages | index(\"$LATE_APP\") != null)" \
  "the label and the favorite read back once the app is there"
ok "an app arriving after the file gets its label and its favorite (${arrival} s after it was enabled)"

# Control: nothing waits now. Another app leaving and coming back must not
# reload - and it must have been decided, not missed.
before="$(report_now)"
# The comparison must work before its silence means anything: a filter that
# does not parse fails every time, and would read as "no reload".
jq -e ". == $before" <<<"$before" >/dev/null || die "the control cannot compare reports: $before"
seen="$(arrivals_decided "$OTHER_APP" "nothing waits")"
pm_for_user0 disable-user "$OTHER_APP" || die "could not disable $OTHER_APP for user 0"
pm_for_user0 enable "$OTHER_APP" || die "could not enable $OTHER_APP for user 0"
retry_for 30 arrival_decided "$OTHER_APP" "nothing waits" "$seen" \
  || die "the watcher never decided $OTHER_APP's arrival; the control would be blind"
report_matches ". != $before" && die "an app arriving with nothing waiting reloaded the file: $(jq -c '{trigger, configSha256}' <<<"$LAST_SEEN_REPORT")"
ok "an app arriving while nothing waits is decided and reloads nothing"

# --- 10f. icons and tags: the pack arrives after the file (#3 slice 4) ------
#
# An app's icon and a tag's icon from an icon pack this device lacks are
# reported and wait; the tags themselves apply at once. The pack is a fixture
# (e2e/fixtures/icon-pack), not Lawnicons, which no snapshot has. It maps
# Settings to fixture_gear and the file names fixture_star for Settings, so
# an icon that read back as fixture_star is the file's pick, not the pack's
# own match. The pack's package event races the launcher indexing it; the
# index's growth is a signal of its own, and the step logs which signal
# reloaded - it may be either, and one of them must.
PACK_PKG=org.andashi.fixture.iconpack
PACK_APK="$WORK/icon-pack.apk"
bash "$(dirname "$0")/fixtures/icon-pack/build.sh" "$PACK_APK" >/dev/null 2>&1 \
  || die "could not build the icon-pack fixture (e2e/fixtures/icon-pack/build.sh); run it by hand to see what is missing"
adb_out shell pm list packages "$PACK_PKG" | grep -qx "package:$PACK_PKG" \
  && die "$PACK_PKG is already installed; the arrival below would prove nothing"
TAGS_CONFIG="$WORK/icons-tags.json"
cat > "$TAGS_CONFIG" <<EOF
{ "schemaVersion": 2,
  "apps": [ { "packageName": "com.android.settings", "icon": { "pack": "$PACK_PKG", "drawable": "fixture_star" } } ],
  "tags": [
    { "name": "Fixture", "icon": { "pack": "$PACK_PKG", "drawable": "fixture_gear" }, "apps": ["com.android.settings"] },
    { "name": "Letters", "icon": { "text": "AB" }, "apps": ["com.android.settings", "$LATE_APP"] } ] }
EOF
push_config "$TAGS_CONFIG" "icons-tags"
assert_jq "$LAST_REPORT" \
  '.success == true
   and ([.diagnostics[] | select(.code == "icon-pack-unavailable" and .severity == "warning") | .path] | sort == ["apps[0].icon", "tags[0].icon"])' \
  "both pack icons are reported as warnings while the pack is absent, and the push succeeds"
effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" \
  ".apps == []
   and .tags == [ {\"name\":\"Fixture\",\"apps\":[\"com.android.settings\"]},
                  {\"name\":\"Letters\",\"icon\":{\"text\":\"AB\"},\"apps\":[\"$LATE_APP\",\"com.android.settings\"]} ]" \
  "the tags and the text icon apply at once; neither pack icon reads back while the pack is absent"
ok "tags apply at once, and pack icons wait for their pack"

event_seen="$(arrivals_decided "$PACK_PKG" "reloaded")"
index_seen="$(arrivals_decided "$PACK_PKG:1 (1)" "reloaded")"
t0=$SECONDS
install_out="$(adb -s "$SERIAL" install "$PACK_APK" 2>&1)" \
  || { printf '%s\n' "$install_out" >&2; die "could not install the icon-pack fixture"; }
case "$install_out" in *Success*) ;; *) printf '%s\n' "$install_out" >&2; die "could not install the icon-pack fixture" ;; esac
wait_report '.trigger == "apps-changed" and .success == true
  and ([.diagnostics[]? | select(.code == "icon-pack-unavailable")] | length == 0)' \
  30 "an arrival reload finds the pack's drawables"
arrival=$((SECONDS - t0))
effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" \
  ".apps == [{\"packageName\":\"com.android.settings\",\"icon\":{\"pack\":\"$PACK_PKG\",\"drawable\":\"fixture_star\"}}]
   and ([.tags[] | select(.name == \"Fixture\") | .icon] == [{\"pack\":\"$PACK_PKG\",\"drawable\":\"fixture_gear\"}])" \
  "each icon reads back as the drawable the file named, not the pack's own match"
# Which signal carried it: counted, not required - they race by design.
by_event=$(( $(arrivals_decided "$PACK_PKG" "reloaded") - event_seen ))
by_index=$(( $(arrivals_decided "$PACK_PKG:1 (1)" "reloaded") - index_seen ))
# The order they came in, for the record: which one looked first is the race.
adb_out shell logcat -d -s ConfigWatcher:D 2>/dev/null | grep -F "arrival $PACK_PKG" | tr -d '\r' | sed 's/^/   /' || true
ok "a pack arriving after the file gives the app and the tag the drawables it named (${arrival} s after the install; reloads by the package event ${by_event}, by the index ${by_index})"
adb -s "$SERIAL" uninstall "$PACK_PKG" >/dev/null 2>&1 || true

# --- 9. restore a valid config via adb push (interactive dotfile path) ---

adb_push_config "$VALID_CONFIG"
log "restore: waiting for file-watcher reload of the pushed file (hash ${H_VALID:0:12}...)"
wait_report ".success == true and .configSha256 == \"$H_VALID\" and .trigger == \"file-watcher\"" 30 \
  "restore: file-watcher report after adb push"
effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" "$EFFECTIVE_FILTER" "effective config restored via adb push"
ok "valid config restored via adb push (file watcher)"

ok "L4 config passed"
