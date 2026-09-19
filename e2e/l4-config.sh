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
#   2. writes a known JSONC config (icons, transparency, search bar, dock,
#      widgets, clock; empty favorites so no installed-package assumptions)
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
#      transparency values, search bar top, dock off, other widgets, other
#      clock) and asserts the read-back shows the new values and the report
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

GOS_REPO="${GOS_REPO:-$HOME/Development/GrapheneOS}"
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
RECEIVER="$PKG/de.mm20.launcher2.config.service.ReloadConfigReceiver"
ACTION="$PKG.action.RELOAD_CONFIG"
STATE_URI="content://$PKG.state"
INGEST_URI="content://$PKG.config-ingest/launcher.json"
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

# --- adb helpers -------------------------------------------------------

# Prints the `json` column of the single row returned by the state provider.
# The payload is pretty-printed (multi-line) JSON, so everything after the
# "Row: 0 json=" prefix is the value. No grep -q anywhere in this script:
# under pipefail, -q exits after the first match and the resulting SIGPIPE
# makes the producer side of the pipeline fail despite the match.
query_json() { # $1 = provider path (config|diagnostics)
  local out
  out="$(adb -s "$SERIAL" shell content query --uri "$STATE_URI/$1" 2>&1 | tr -d '\r')" \
    || { printf 'content query failed: %s\n' "$out" >&2; return 1; }
  case "$out" in
    "Row: 0 json="*) printf '%s' "${out#Row: 0 json=}" ;;
    *) printf 'unexpected provider output: %s\n' "$out" >&2; return 1 ;;
  esac
}

# Polls /diagnostics until the latest report matches the jq filter.
# Sets LAST_REPORT on success; fails loudly and prints the last seen report
# on timeout.
LAST_REPORT=""
wait_report() { # $1 = jq filter, $2 = timeout (s), $3 = description
  local filter="$1" timeout="$2" what="$3" elapsed=0 report=""
  while [ "$elapsed" -lt "$timeout" ]; do
    if report="$(query_json diagnostics 2>/dev/null)" && [ -n "$report" ]; then
      if jq -e "$filter" >/dev/null 2>&1 <<<"$report"; then
        LAST_REPORT="$report"
        return 0
      fi
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  printf 'last /diagnostics report:\n%s\n' "$report" >&2
  die "timed out (${timeout}s) waiting for report: $what"
}

assert_jq() { # $1 = json, $2 = jq filter, $3 = description
  if ! jq -e "$2" >/dev/null 2>&1 <<<"$1"; then
    printf 'offending json:\n%s\n' "$1" >&2
    die "assertion failed: $3"
  fi
}

# The provisioning transport: stream the file into the ingest provider.
# `content write` exits 0 even when the provider throws (it only prints the
# exception), so any output at all is a failure. adb shell v2 is binary-safe.
write_config() { # $1 = local file
  local out
  out="$(adb -s "$SERIAL" shell content write --uri "$INGEST_URI" < "$1" 2>&1 | tr -d '\r')" \
    || { printf '%s\n' "$out" >&2; die "content write failed"; }
  [ -z "$out" ] || { printf '%s\n' "$out" >&2; die "content write reported an error"; }
}

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
# not reachable for the shell, see ADR 0003 §1a).
push_config() { # $1 = local file
  adb -s "$SERIAL" shell "mkdir -p '$REMOTE_DIR'" >/dev/null
  adb -s "$SERIAL" push "$1" "$REMOTE_CONFIG" >/dev/null
}

reload_broadcast() {
  local out
  out="$(adb -s "$SERIAL" shell am broadcast -n "$RECEIVER" -a "$ACTION" 2>&1 | tr -d '\r')" \
    || { printf '%s\n' "$out" >&2; die "am broadcast failed"; }
  case "$out" in
    *"Broadcast completed"*) ;;
    *) printf '%s\n' "$out" >&2; die "am broadcast did not complete" ;;
  esac
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
  "schemaVersion": 1,
  "icons": {
    "themed": true,
    "enforceThemed": true,
  },
  "appearance": {
    "transparency": {
      "background": 0.5,
      "surface": 0.7,
      "elevatedSurface": 0.9,
    },
  },
  "home": {
    "searchBar": { "position": "bottom" },
    "dock": {
      "enabled": true,
      // Empty on purpose: favorites reference installed packages.
      "favorites": [],
    },
    "widgets": { "enabled": true, "widgets": ["weather", "music"] },
    "clock": { "style": "analog", "fillHeight": true },
  },
}
EOF

UNKNOWN_KEYS_CONFIG="$WORK/unknown-keys.jsonc"
cat > "$UNKNOWN_KEYS_CONFIG" <<'EOF'
{
  "schemaVersion": 1,
  "futureTopLevelKey": { "anything": 1 },
  "icons": {
    "themed": true,
    "enforceThemed": true,
    "futureIconsKey": "ignored",
  },
  "appearance": {
    "transparency": {
      "background": 0.5,
      "surface": 0.7,
      "elevatedSurface": 0.9,
    },
  },
  "home": {
    "searchBar": { "position": "bottom" },
    "dock": {
      "enabled": true,
      "favorites": [],
      "futureDockKey": true,
    },
    "widgets": { "enabled": true, "widgets": ["weather", "music"] },
    "clock": { "style": "analog", "fillHeight": true },
  },
}
EOF

# Every section differs from VALID_CONFIG, so converging from one to the
# other must touch all of them and the read-back must flip completely.
CHANGED_CONFIG="$WORK/changed.jsonc"
cat > "$CHANGED_CONFIG" <<'EOF'
{
  "schemaVersion": 1,
  "icons": {
    "themed": false,
    "enforceThemed": false,
  },
  "appearance": {
    "transparency": {
      "background": 0.2,
      "surface": 0.3,
      "elevatedSurface": 0.4,
    },
  },
  "home": {
    "searchBar": { "position": "top" },
    "dock": { "enabled": false, "favorites": [] },
    "widgets": { "enabled": true, "widgets": ["calendar", "notes", "apps"] },
    "clock": { "style": "digital2", "fillHeight": false },
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
  "schemaVersion": 1,
  "icons": { "themed": true, "enforceThemed": true },
  "appearance": {
    "transparency": { "background": 0.5, "surface": 0.7, "elevatedSurface": 0.9 },
    "wallpaper": { "image": "l4.png", "target": "both" },
  },
  "home": {
    "searchBar": { "position": "bottom" },
    "dock": { "enabled": true, "favorites": [] },
    "widgets": { "enabled": true, "widgets": ["weather", "music"] },
    "clock": { "style": "analog", "fillHeight": true },
  },
}
EOF

MALFORMED_CONFIG="$WORK/malformed.jsonc"
printf '{ "schemaVersion": 1, "icons": { not json at all\n' > "$MALFORMED_CONFIG"

H_VALID="$(sha256sum "$VALID_CONFIG" | cut -d' ' -f1)"
H_UNKNOWN="$(sha256sum "$UNKNOWN_KEYS_CONFIG" | cut -d' ' -f1)"
H_MALFORMED="$(sha256sum "$MALFORMED_CONFIG" | cut -d' ' -f1)"
H_CHANGED="$(sha256sum "$CHANGED_CONFIG" | cut -d' ' -f1)"
H_WALLPAPER="$(sha256sum "$WALLPAPER_CONFIG" | cut -d' ' -f1)"

# The /config read-back is fully populated (ConfigStateMapper), so these are
# the exact effective values after applying VALID_CONFIG.
EFFECTIVE_FILTER='
  .schemaVersion == 1
  and .icons.themed == true
  and .icons.enforceThemed == true
  and .appearance.transparency.background == 0.5
  and .appearance.transparency.surface == 0.7
  and .appearance.transparency.elevatedSurface == 0.9
  and .home.searchBar.position == "bottom"
  and .home.dock.enabled == true
  and .home.dock.favorites == []
  and .home.widgets.enabled == true
  and (.home.widgets.widgets | sort) == ["music", "weather"]
  and .home.clock.style == "analog"
  and .home.clock.fillHeight == true
'

CHANGED_FILTER='
  .schemaVersion == 1
  and .icons.themed == false
  and .icons.enforceThemed == false
  and .appearance.transparency.background == 0.2
  and .appearance.transparency.surface == 0.3
  and .appearance.transparency.elevatedSurface == 0.4
  and .home.searchBar.position == "top"
  and .home.dock.enabled == false
  and .home.dock.favorites == []
  and .home.widgets.enabled == true
  and (.home.widgets.widgets | sort) == ["apps", "calendar", "notes"]
  and .home.clock.style == "digital2"
  and .home.clock.fillHeight == false
'

# The sections a full VALID <-> CHANGED convergence must report as applied.
ALL_SECTIONS_FILTER='
  ((.appliedMutations // []) | sort) ==
  ["appearance.transparency", "home.clock", "home.dock.enabled", "home.searchBar",
   "home.widgets.widgets", "icons"]
'

# --- 1. boot + install -------------------------------------------------

(cd "$GOS_REPO" && emulator/device-lock.sh acquire "$LOCK_OWNER" "$SERIAL")
HAVE_LOCK=1

log "booting $SERIAL from snapshot '$SNAPSHOT' (overlays: $OVERLAY_DIR)"
(cd "$GOS_REPO" && SNAPSHOT="$SNAPSHOT" emulator/run.sh start)

# Release GrapheneOS has no adb root; everything below must work as shell.
adb -s "$SERIAL" unroot >/dev/null 2>&1 || true
adb -s "$SERIAL" wait-for-device
[ "$(adb -s "$SERIAL" shell id -u | tr -d '\r')" = "2000" ] || die "adb is not running as shell after unroot"
ok "adb running as unrooted shell"

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
ok "effective config matches (icons, transparency, search bar, dock, widgets, clock)"

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
ok "changed config: every section flipped (icons, transparency, search bar, dock, widgets, clock)"

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

# --- 9. restore a valid config via adb push (interactive dotfile path) ---

push_config "$VALID_CONFIG"
log "restore: waiting for file-watcher reload of the pushed file (hash ${H_VALID:0:12}...)"
wait_report ".success == true and .configSha256 == \"$H_VALID\" and .trigger == \"file-watcher\"" 30 \
  "restore: file-watcher report after adb push"
effective="$(query_json config)" || die "could not query /config"
assert_jq "$effective" "$EFFECTIVE_FILTER" "effective config restored via adb push"
ok "valid config restored via adb push (file watcher)"

ok "L4 config passed"
