#!/usr/bin/env bash
# L4 provisioning-config test: proves the REAL provisioning step
# provision/45-launcher-config.sh from the GrapheneOS provisioning repo works
# end to end on the GrapheneOS emulator, against the same harness as
# e2e/l4-smoke.sh and e2e/l4-config.sh (ADR 0005).
#
#   e2e/l4-provisioning-config.sh [path/to/kvaesitso.apk]
#
# What it does:
#   1. acquires the instance's device lock (as
#      "l4-provisioning-config@<serial>#<pid>"), boots the test instance (default
#      emulator-5556 with its own qcow2 overlays under
#      <gos-repo>/emulator/instances/test; SERIAL and OVERLAY_DIR pick another
#      one) from SNAPSHOT:
#        profiles-ready (default, the everyday run): `clean` plus
#          00-profiles.sh, saved once per instance, nothing launcher-related
#        clean (the release gate): near-first-boot, the only honest base
#   2. runs provision/00-profiles.sh against the test instance so all
#      configured zones/profiles exist (stopped profiles are temporarily
#      started by that step - expected, see 00-profiles.sh). From
#      profiles-ready this takes seconds and still catches drift between
#      config/profiles.json and the snapshot; from clean it creates them all
#   3. drops root (`adb unroot`): release GrapheneOS has no adb root, so the
#      provisioning transport must work as the plain shell user; then
#      installs the Kvaesitso fork debug APK into user 0
#   4. for every non-managed profile from config/profiles.json: resolves the
#      uid, starts evicted users, and makes sure the debug package is
#      installed for that user (pm install-existing --user)
#   5. runs LAUNCHER_CONFIG_KEY=andashi-home-debug provision/45-launcher-config.sh
#      and requires exit 0 - the step itself writes the generated
#      config/launcher/<profile>.json files through the ingest provider
#      (`content write --user`; adb push cannot reach a secondary user's
#      storage, ADR 0003 §1a), broadcasts RELOAD_CONFIG, polls diagnostics
#      and verifies read-back per profile
#   6. per-profile read-back check: for each non-managed profile, queries
#      content://<pkg>.state/config with --user <uid> and verifies it
#      semantically against the generated config file, then queries
#      /diagnostics and requires success with the sha256 of the generated
#      file
#   7. per-user isolation check: after the real provisioning step, writes a
#      deliberately distinct config into ONE profile and asserts that this
#      profile changes while another profile keeps the generated value
#   8. stops the instance and releases the lock
#
# The managed work profile is deliberately skipped (no home screen of its
# own, no config file is generated for it - same skip as the provisioning
# step itself).
#
# The emulator harness (run.sh, device-lock.sh, snapshots, overlays) lives in
# the provisioning repo — see docs/architecture/adr/0005-testing-strategy.md.
# The provisioning repo is used READ-ONLY; nothing there is modified.
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
LOCK_OWNER="l4-provisioning-config@$SERIAL#$$"
SNAPSHOT="${SNAPSHOT:-profiles-ready}"
APK="${1:-$(dirname "$0")/../app/app/build/outputs/apk/default/debug/app-default-debug.apk}"
# Overridable: PKG=org.andashi.home APK=... runs the scenario against the release build.
PKG="${PKG:-org.andashi.home.debug}"
# The theming.json launcher entry whose pkg matches PKG.
LAUNCHER_CONFIG_KEY="${LAUNCHER_CONFIG_KEY:-andashi-home-debug}"
PROFILES_JSON="$GOS_REPO/config/profiles.json"
WORK="$(mktemp -d)"
# The configs the provisioning step pushes are generated here for the launcher
# entry under test, so the tracked config/launcher/*.json (generated for the
# shipped release entry) stay untouched and a debug-only capability such as
# wallpaper support can be exercised.
LAUNCHER_CFG_DIR="$WORK/launcher"
export LAUNCHER_CFG_DIR

c(){ [ -t 1 ] && printf '\033[%sm%s\033[0m\n' "$1" "$2" || printf '%s\n' "$2"; }
log(){ c '1;34' ":: $*"; }; ok(){ c '1;32' " + $*"; }
die(){ c '1;31' " x $*" >&2; exit 1; }

[ "$INSTANCE_OVERRIDE" = "" ] || [ "$INSTANCE_OVERRIDE" = "so" ] \
  || die "SERIAL and OVERLAY_DIR name ONE instance - set both or neither (provisioning README, \"Emulator instances\"). Overriding only one runs one instance's disk under another instance's lock, because the lock is keyed by serial"
[ -d "$GOS_REPO/emulator" ] || die "provisioning repo not found at $GOS_REPO (set GOS_REPO)"
[ -f "$APK" ] || die "APK not found: $APK (build it or pass a path)"
[ -f "$PROFILES_JSON" ] || die "config/profiles.json missing in $GOS_REPO"
[ -f "$GOS_REPO/config/features.json" ] || die "config/features.json missing in $GOS_REPO"
[ -f "$GOS_REPO/provision/00-profiles.sh" ] || die "provision/00-profiles.sh missing in $GOS_REPO"
[ -f "$GOS_REPO/provision/45-launcher-config.sh" ] || die "provision/45-launcher-config.sh missing in $GOS_REPO"
command -v jq >/dev/null || die "jq not found (required for config assertions)"
# Fail before the lock and the boot when the snapshot is missing on THIS
# instance - every instance keeps its own. ram.bin is the check, not the
# directory: a failed load leaves an empty snapshots/<name>/ stub behind.
[ -f "$OVERLAY_DIR/snapshots/$SNAPSHOT/ram.bin" ] \
  || die "snapshot '$SNAPSHOT' missing on $OVERLAY_DIR - create it (provisioning README, \"Refreshing profiles-ready\") or run with SNAPSHOT=clean"

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

# The device helpers and their constants (STATE_URI, INGEST_URI, ...) live
# in the shared library (#126). This script's per-user reads stay its own,
# as query_json_as_user.
# shellcheck source=lib/grid-device.sh
. "$(dirname "$0")/lib/grid-device.sh"

# --- helpers -------------------------------------------------------------

# No grep -q anywhere in this script: under pipefail, -q exits after the
# first match and the resulting SIGPIPE makes the producer side of the
# pipeline fail despite the match.

# Keys of all non-managed, feature-enabled profiles from the provisioning
# config. Managed profiles (type "managed") have no home screen and no
# generated config file - skipping them is deliberate, not a failure.
# Feature-gated profiles with the feature off are never created by
# 00-profiles.sh, so they must not be looked up here either.
non_managed_profile_keys() {
  jq -r -n '
    (input | .profiles) as $profiles
    | (input | .features // {}) as $features
    | $profiles[]
    | select(.type != "managed")
    | select((.feature // "") as $f
        | ($f == "") or ($features[$f].enabled == true))
    | .key
  ' "$PROFILES_JSON" "$GOS_REPO/config/features.json"
}

# Resolves a profile key to a user id. For create=false profiles (Home) the
# id is fixed in profiles.json; the owner is called "Owner" in the system, so
# a label search would never find it. Otherwise search pm list users by label.
resolve_uid() { # $1 = profile key
  local key="$1" create label
  create="$(jq -r --arg k "$key" '.profiles[] | select(.key == $k) | if has("create") then .create else true end' "$PROFILES_JSON")"
  if [ "$create" = "false" ]; then
    jq -r --arg k "$key" '.profiles[] | select(.key == $k) | .user_id' "$PROFILES_JSON"
    return
  fi
  label="$(jq -r --arg k "$key" '.profiles[] | select(.key == $k) | .label' "$PROFILES_JSON")"
  adb -s "$SERIAL" shell pm list users 2>/dev/null | tr -d '\r' \
    | sed -n "s/.*UserInfo{\([0-9]\+\):${label}:.*/\1/p" | head -1
}

user_running_uid() { # $1 = uid
  [ "$1" = "0" ] && return 0
  local out
  out="$(adb -s "$SERIAL" shell pm list users 2>/dev/null | tr -d '\r')" || return 1
  case "$out" in
    *"UserInfo{$1:"*"} running"*) return 0 ;;
    *) return 1 ;;
  esac
}

# The authoritative user state. `pm list users` still says "running" for a
# user that is being evicted, so it cannot decide whether a provider is
# reachable; only RUNNING_UNLOCKED can.
user_state() { # $1 = uid
  adb -s "$SERIAL" shell am get-started-user-state "$1" </dev/null 2>/dev/null | tr -d '\r'
}

# Background-starts a user that is not RUNNING_UNLOCKED; a stopped or locked
# user has no provider to write to or query. The emulator keeps at most three
# users running, so starting one zone evicts another - every per-user step
# has to re-check right before it talks to the provider.
ensure_user_running() { # $1 = uid
  local state
  state="$(user_state "$1")"
  case "$state" in *RUNNING_UNLOCKED*) return 0 ;; esac
  log "user $1 is '${state:-stopped}' - starting in background"
  adb -s "$SERIAL" shell am start-user -w "$1" </dev/null >/dev/null \
    || die "user $1 could not be started"
  state="$(user_state "$1")"
  case "$state" in
    *RUNNING_UNLOCKED*) ;;
    *) die "user $1 is '$state' after start-user, expected RUNNING_UNLOCKED" ;;
  esac
}

# The wallpaper id of one user in one section of `dumpsys wallpaper`
# ("System wallpaper state:" or "Lock wallpaper state:"). Empty when the
# section has no record for that user; 0 means default/none.
wallpaper_id() { # $1 = uid, $2 = System|Lock
  adb -s "$SERIAL" shell dumpsys wallpaper 2>/dev/null | tr -d '\r' \
    | awk -v sec="$2 wallpaper state:" -v u="User $1:" '
        /wallpaper state:/ { in_sec = (index($0, sec) > 0); next }
        in_sec && index($0, u) { if (match($0, /id=[0-9]+/)) { print substr($0, RSTART+3, RLENGTH-3); exit } }'
}

pkg_installed_for_user() { # $1 = pkg, $2 = uid
  local out
  out="$(adb -s "$SERIAL" shell pm list packages --user "$2" 2>/dev/null | tr -d '\r')" || return 1
  case $'\n'"$out"$'\n' in *$'\n'"package:$1"$'\n'*) return 0 ;; *) return 1 ;; esac
}

# Prints the `json` column of the single row returned by the state provider
# for the given user. The payload is pretty-printed (multi-line) JSON, so
# everything after the "Row: 0 json=" prefix is the value.
# Right after `am start-user -w` a user's package resolution and external
# storage lag behind its RUNNING_UNLOCKED state for a moment (measured on
# 2026-09-19 from the provisioning chain and here). Exactly these two
# transient messages are retried, bounded; anything else fails at once.
# Not the library's query_json: it asks one user's provider (--user) and
# waits for it to come up, since a zone's launcher process starts on demand.
query_json_as_user() { # $1 = provider path (config|diagnostics), $2 = uid
  local out attempt=0
  while :; do
    out="$(adb -s "$SERIAL" shell content query --uri "$STATE_URI/$1" --user "$2" 2>&1 | tr -d '\r')" || true
    case "$out" in
      "Row: 0 json="*) printf '%s' "${out#Row: 0 json=}"; return 0 ;;
      *"Could not find provider"*|*"External files directory unavailable"*)
        attempt=$((attempt + 1))
        [ "$attempt" -lt 15 ] || { printf 'provider for user %s did not come up within 30s: %s\n' "$2" "$out" >&2; return 1; }
        sleep 2 ;;
      *) printf 'unexpected provider output (user %s): %s\n' "$2" "$out" >&2; return 1 ;;
    esac
  done
}

# Streams the file into the target user's ingest provider. `content write`
# exits 0 even when the provider throws (it only prints the exception), so
# any output at all is a failure. adb shell v2 is binary-safe.
write_config_user() { # $1 = local file, $2 = uid
  local out
  out="$(adb -s "$SERIAL" shell content write --user "$2" --uri "$INGEST_URI" < "$1" 2>&1 | tr -d '\r')" \
    || { printf '%s\n' "$out" >&2; die "content write failed for user $2"; }
  [ -z "$out" ] || { printf '%s\n' "$out" >&2; die "content write reported an error for user $2"; }
}

broadcast_user() { # $1 = uid
  local out
  out="$(adb -s "$SERIAL" shell am broadcast \
    -n "$PKG/de.mm20.launcher2.config.service.ReloadConfigReceiver" \
    -a "$PKG.action.RELOAD_CONFIG" --user "$1" 2>&1 | tr -d '\r')" \
    || { printf '%s\n' "$out" >&2; die "am broadcast failed for user $1"; }
  case "$out" in
    *"Broadcast completed"*) ;;
    *) printf '%s\n' "$out" >&2; die "am broadcast did not complete for user $1" ;;
  esac
}

wait_diagnostics_sha() { # $1 = uid, $2 = sha256, $3 = timeout seconds
  local elapsed=0 diag=""
  while [ "$elapsed" -lt "$3" ]; do
    if diag="$(query_json_as_user diagnostics "$1" 2>/dev/null)" \
       && jq -e ".success == true and .configSha256 == \"$2\"" >/dev/null 2>&1 <<<"$diag"; then
      LAST_DIAG="$diag"
      return 0
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  printf 'last diagnostics for user %s:\n%s\n' "$1" "$diag" >&2
  die "timed out waiting for diagnostics sha256 $2 for user $1"
}

# --- 1. lock + boot ------------------------------------------------------

(cd "$GOS_REPO" && emulator/device-lock.sh acquire "$LOCK_OWNER" "$SERIAL")
HAVE_LOCK=1

log "booting $SERIAL from snapshot '$SNAPSHOT' (overlays: $OVERLAY_DIR)"
(cd "$GOS_REPO" && SNAPSHOT="$SNAPSHOT" emulator/run.sh start)

# --- 2. create profiles via the real provisioning step -------------------

log "running provision/00-profiles.sh against $SERIAL"
(cd "$GOS_REPO" && ADB_SERIAL="$SERIAL" bash provision/00-profiles.sh) \
  || die "00-profiles.sh failed"
ok "profiles reconciled"

# --- 3. drop root, install the debug APK into user 0 ---------------------

# run.sh start leaves adbd rooted (userdebug). Release GrapheneOS has no adb
# root, so from here on everything must work as the plain shell user.
adb -s "$SERIAL" unroot >/dev/null 2>&1 || true
adb -s "$SERIAL" wait-for-device
[ "$(adb -s "$SERIAL" shell id -u | tr -d '\r')" = "2000" ] || die "adb is not running as shell after unroot"
ok "adb running as unrooted shell"

log "installing $(basename "$APK") into user 0"
install_out="$(adb -s "$SERIAL" install -r "$APK" 2>&1)" || { printf '%s\n' "$install_out" >&2; die "adb install failed"; }
case "$install_out" in
  *Success*) ;;
  *) printf '%s\n' "$install_out" >&2; die "adb install failed" ;;
esac

adb -s "$SERIAL" shell pm list packages | tr -d '\r' | grep -x "package:$PKG" >/dev/null \
  || die "$PKG not installed"
ok "package installed: $PKG"

# --- 4. make the package available in every non-managed profile ----------

# Profile keys as an array: adb shell inside a `while read` loop would eat the
# loop's remaining stdin and silently end it after the first profile.
mapfile -t PROFILE_KEYS < <(non_managed_profile_keys)
[ "${#PROFILE_KEYS[@]}" -ge 2 ] || die "need at least two non-managed profiles (isolation check)"

log "ensuring $PKG is installed for every non-managed profile"
for key in "${PROFILE_KEYS[@]}"; do
  uid="$(resolve_uid "$key")"
  [ -n "$uid" ] || die "profile '$key': could not resolve uid (00-profiles.sh should have created it)"
  ensure_user_running "$uid"

  if pkg_installed_for_user "$PKG" "$uid"; then
    ok "profile '$key' (user $uid): $PKG already installed"
    continue
  fi
  ie_out="$(adb -s "$SERIAL" shell pm install-existing --user "$uid" "$PKG" 2>&1 | tr -d '\r')" \
    || { printf '%s\n' "$ie_out" >&2; die "pm install-existing failed for profile '$key' (user $uid)"; }
  case "$ie_out" in
    *installed*) ;;
    *) printf '%s\n' "$ie_out" >&2; die "pm install-existing failed for profile '$key' (user $uid)" ;;
  esac
  pkg_installed_for_user "$PKG" "$uid" \
    || die "profile '$key' (user $uid): $PKG still not installed after install-existing"
  ok "profile '$key' (user $uid): $PKG installed via install-existing"
done

# --- 5. run the real provisioning step ------------------------------------

log "generating launcher configs for entry '$LAUNCHER_CONFIG_KEY' into $LAUNCHER_CFG_DIR"
(cd "$GOS_REPO/config" && OUT_DIR="$LAUNCHER_CFG_DIR" GEN_LAUNCHER_KEY="$LAUNCHER_CONFIG_KEY" ./gen-launcher.sh) \
  || die "gen-launcher.sh failed for entry '$LAUNCHER_CONFIG_KEY'"

# Favorites are the one corner of the config contract no other layer fills
# for every zone (#35). gen-launcher.sh declares them for Home only, and
# emits `"favorites": []` for the others. So a zone that declares none gets
# one here, under `home.favorites`. Until 2026-09-26 this wrote
# `home.dock.favorites` and `home.dock.enabled` into the schema-2 files:
# keys schema 2 had removed, which the launcher ignored with an unknown-key
# warning while provisioning's read-back check failed on all six profiles.
# A zone that declares favorites keeps its own. $PKG is the single package
# this scenario installs into every profile, which makes it the safe entry.
#
# Both legal spellings are used, in different profiles on purpose: the object
# form, which is what a round trip writes back, and the bare package name, which
# is what a config generator naturally emits and what once failed the decode and
# took a whole zone's configuration with it (#45, andashi/provisioning#1).
#
# The short form needs the build under test to carry #45, i.e. v0.3.1 or later.
# Against 0.3.0 it is not normalised but REJECTED - "decode-failed: Expected
# JsonObject, but had JsonLiteral" - and the previously applied configuration
# stays in force, so a read-back can look like success when nothing arrived
# (measured by the provisioning session on emulator-5558, 2026-09-22). This
# scenario installs the APK it was given, so that only matters when someone
# points PKG/APK at an older release; the diagnostics assert in section 6
# compares configSha256 against the pushed file and fails loudly in that case
# rather than quietly comparing a stale document.
log "injecting favorites into the generated configs that declare none ($PKG)"
for i in "${!PROFILE_KEYS[@]}"; do
  key="${PROFILE_KEYS[$i]}"
  cfgfile="$LAUNCHER_CFG_DIR/$key.json"
  [ -f "$cfgfile" ] || die "generated config missing: $cfgfile (run config/gen-launcher.sh)"
  if [ "$(jq '.home.favorites // [] | length' "$cfgfile")" -gt 0 ]; then
    ok "profile '$key': declares its own favorites $(jq -c '[.home.favorites[] | .packageName? // .]' "$cfgfile")"
    continue
  fi
  if [ "$i" -eq 1 ]; then
    favorites="$(jq -n --arg p "$PKG" '[$p]')"
  else
    favorites="$(jq -n --arg p "$PKG" '[{packageName: $p, profile: "personal"}]')"
  fi
  jq --argjson fav "$favorites" '.home.favorites = $fav' \
    "$cfgfile" > "$cfgfile.tmp" \
    || die "could not inject favorites into $cfgfile"
  mv "$cfgfile.tmp" "$cfgfile"
  ok "profile '$key': favorites := $(jq -c '.home.favorites' "$cfgfile")"
done

log "running provision/45-launcher-config.sh (LAUNCHER_CONFIG_KEY=$LAUNCHER_CONFIG_KEY)"
(cd "$GOS_REPO" && LAUNCHER_CONFIG_KEY="$LAUNCHER_CONFIG_KEY" ADB_SERIAL="$SERIAL" bash provision/45-launcher-config.sh) \
  || die "45-launcher-config.sh exited non-zero - provisioning step FAILED"
ok "45-launcher-config.sh converged all profiles (exit 0)"

# --- 6. per-profile read-back verification --------------------------------
# Each user's provider instance must serve the config that provisioning wrote
# for that user. The checked-in files are currently identical, so this section
# proves every profile converged; the distinct-value isolation check is below.

log "verifying per-user read-back"
for key in "${PROFILE_KEYS[@]}"; do
  uid="$(resolve_uid "$key")"
  [ -n "$uid" ] || die "profile '$key': could not resolve uid"
  ensure_user_running "$uid"
  cfgfile="$LAUNCHER_CFG_DIR/$key.json"
  [ -f "$cfgfile" ] || die "generated config missing: $cfgfile (run config/gen-launcher.sh)"
  want_sha="$(sha256sum "$cfgfile" | cut -d' ' -f1)"

  diag="$(query_json_as_user diagnostics "$uid")" || die "profile '$key' (user $uid): /diagnostics not served"
  assert_jq "$diag" \
    ".success == true and .configSha256 == \"$want_sha\"" \
    "profile '$key' (user $uid): diagnostics success + sha256 of config/launcher/$key.json"

  eff="$(query_json_as_user config "$uid")" || die "profile '$key' (user $uid): /config not served"
  # /config serves the *effective* document, re-serialised from the decoded
  # model. Two consequences for favorites: a bare package name comes back as an
  # object, and `profile` is absent when it is the default, because the config
  # Json does not set encodeDefaults. Both spellings mean the same favorite
  # (#45), so canonicalise both sides instead of calling that a mismatch. The
  # exact serialisation is L1's job; this level asks whether the favorite
  # arrived at the right profile.
  mism="$(jq -r -n --argjson eff "$eff" --slurpfile want "$cfgfile" '
    def canon:
      if ((.home.favorites // null) | type) == "array" then
        .home.favorites |= map(
          if type == "string" then { packageName: ., profile: "personal" }
          else { packageName: .packageName, profile: (.profile // "personal") }
          end)
      else . end;
    ($want[0] | canon) as $w
    | ($eff | canon) as $e
    | [ "schemaVersion", "icons", "appearance", "home" ]
    | map(select($e[.] != $w[.]))
    | join(", ")')"
  [ -z "$mism" ] || { printf 'effective config for user %s:\n%s\n' "$uid" "$eff" >&2; \
    die "profile '$key' (user $uid): /config differs from generated file in: $mism"; }

  # Named explicitly, because the canonicalising comparison above would also be
  # satisfied by an empty list on both sides - which is exactly the state that
  # let this corner go untested for so long. This asserts the config path
  # only. On screen the favorites are drawn only where a `favorites` grid item
  # is declared, and only Home declares one; a rendering assert across six
  # profiles is a scenario of its own, and l4-grid.sh covers the widget.
  assert_jq "$eff" \
    "(.home.favorites // [] | length) > 0" \
    "profile '$key' (user $uid): its favorites survived the round trip"
  ok "profile '$key' (user $uid): favorites $(jq -c '[.home.favorites[]?|.packageName]' <<<"$eff") served back"

  # The generated config names a wallpaper; the system must show a non-default
  # wallpaper id for that user (dumpsys is independent evidence of the read-back).
  want_wp="$(jq -r '.appearance.wallpaper.image // empty' "$cfgfile")"
  if [ -n "$want_wp" ]; then
    # Since #37 a wallpaper is not set while nobody is looking, and a
    # provisioning run looks at nothing - not even in user 0, where the
    # launcher is HOME but never resumed during the run. So the contract is no
    # longer "an id exists"; it is "an id exists OR the profile says the
    # wallpaper is still outstanding". Deliberately not relaxed to "either is
    # fine": one of the two has to be true, and a profile that reports neither
    # is a failure exactly as before. Section 8 remains the proof that the
    # deferred set actually happens, because it foregrounds the profile first.
    pending_wp="$(printf '%s' "$diag" \
      | jq -r '[(.diagnostics // [])[] | select(.code == "wallpaper-pending-foreground")] | length')"
    want_target="$(jq -r '.appearance.wallpaper.target // "both"' "$cfgfile")"
    sys_id="$(wallpaper_id "$uid" System)"; lock_id="$(wallpaper_id "$uid" Lock)"
    if [ "${pending_wp:-0}" -gt 0 ]; then
      ok "profile '$key' (user $uid): wallpaper '$want_wp' deferred until the profile is looked at (reported)"
    else
    case "$want_target" in
      home) [ -n "$sys_id" ] && [ "$sys_id" != "0" ] || die "profile '$key' (user $uid): home wallpaper '$want_wp' configured but system id is '${sys_id:-}'" ;;
      lock) [ -n "$lock_id" ] && [ "$lock_id" != "0" ] || die "profile '$key' (user $uid): lock wallpaper '$want_wp' configured but lock id is '${lock_id:-}'" ;;
      both) [ -n "$sys_id" ] && [ "$sys_id" != "0" ] || die "profile '$key' (user $uid): wallpaper '$want_wp' configured but system id is '${sys_id:-}'"
            # With both flags Android may keep no separate lock record; only a
            # present-but-zero lock id is a failure.
            [ -z "$lock_id" ] || [ "$lock_id" != "0" ] || die "profile '$key' (user $uid): lock wallpaper id is 0 although target is both" ;;
    esac
    ok "profile '$key' (user $uid): wallpaper '$want_wp' set for $want_target (system id ${sys_id:-none}, lock id ${lock_id:-none})"
    fi
  fi

  ok "profile '$key' (user $uid): /config matches generated file, diagnostics sha256 ${want_sha:0:12}..."
done

# --- 7. per-user isolation with a deliberately distinct override ----------
# The generated profile files are currently identical, so the previous section
# cannot distinguish a cross-user leak. Write one changed config into exactly
# one profile and verify that only that profile's provider sees it.

BASE_KEY="${PROFILE_KEYS[0]}"
OVERRIDE_KEY="${PROFILE_KEYS[1]}"
BASE_UID="$(resolve_uid "$BASE_KEY")"
OVERRIDE_UID="$(resolve_uid "$OVERRIDE_KEY")"
[ -n "$BASE_UID" ] && [ -n "$OVERRIDE_UID" ] || die "could not resolve isolation profile uids"

# A key the launcher serves back: appearance.transparency was accepted but no
# longer served once appearance.glass replaced it (#24), so an override
# written there never showed in /config and the check failed on all runs
# since (found 2026-09-26, the first run to get this far from `clean`).
OVERRIDE_CONFIG="$WORK/$OVERRIDE_KEY-isolation.json"
jq '.appearance.glass.tint = 0.42' \
  "$LAUNCHER_CFG_DIR/$OVERRIDE_KEY.json" > "$OVERRIDE_CONFIG"
OVERRIDE_SHA="$(sha256sum "$OVERRIDE_CONFIG" | cut -d' ' -f1)"

log "writing distinct isolation config to profile '$OVERRIDE_KEY' (user $OVERRIDE_UID)"
ensure_user_running "$OVERRIDE_UID"
ensure_user_running "$BASE_UID"
write_config_user "$OVERRIDE_CONFIG" "$OVERRIDE_UID"
broadcast_user "$OVERRIDE_UID"
wait_diagnostics_sha "$OVERRIDE_UID" "$OVERRIDE_SHA" 30

override_eff="$(query_json_as_user config "$OVERRIDE_UID")" \
  || die "profile '$OVERRIDE_KEY': /config not served after isolation override"
assert_jq "$override_eff" \
  '.appearance.glass.tint == 0.42' \
  "profile '$OVERRIDE_KEY' sees the isolation override"

BASE_TINT="$(jq -e '.appearance.glass.tint' "$LAUNCHER_CFG_DIR/$BASE_KEY.json")" \
  || die "could not read appearance.glass.tint from $LAUNCHER_CFG_DIR/$BASE_KEY.json"
[ "$BASE_TINT" != "0.42" ] || die "isolation check needs a base value other than the override (0.42)"
base_eff="$(query_json_as_user config "$BASE_UID")" \
  || die "profile '$BASE_KEY': /config not served during isolation check"
assert_jq "$base_eff" \
  ".appearance.glass.tint == $BASE_TINT" \
  "profile '$BASE_KEY' keeps the generated value ($BASE_TINT) while '$OVERRIDE_KEY' is overridden"
ok "per-user isolation: '$OVERRIDE_KEY' changed, '$BASE_KEY' unchanged"

# --- 8. background-set wallpapers render once the profile is foreground ---
# WallpaperManagerService crops a static wallpaper only for the current user
# (measured 2026-09-19: background profiles kept mCropHint=Rect(0,0-0,0) and a
# black screen although ids were assigned). The launcher re-applies on its
# first foreground resume; switch to one secondary profile and expect a crop.
FG_KEY="$OVERRIDE_KEY"; FG_UID="$OVERRIDE_UID"
log "switching to profile '$FG_KEY' (user $FG_UID) so its wallpaper gets rendered"
adb -s "$SERIAL" shell am switch-user "$FG_UID" </dev/null >/dev/null || die "am switch-user $FG_UID failed"
for _i in $(seq 1 30); do
  [ "$(adb -s "$SERIAL" shell am get-current-user </dev/null 2>/dev/null | tr -d '\r')" = "$FG_UID" ] && break
  sleep 1
done
[ "$(adb -s "$SERIAL" shell am get-current-user </dev/null 2>/dev/null | tr -d '\r')" = "$FG_UID" ] \
  || die "user $FG_UID did not become the current user within 30 s"
# The launcher under test is not the HOME role holder here (40-theming.sh is
# not part of this scenario) and a fresh profile may still show the setup
# wizard, so its activity would never resume on its own: start it explicitly.
# That is what fires the foreground hook.
adb -s "$SERIAL" shell am start --user "$FG_UID" -n "$PKG/de.mm20.launcher2.ui.launcher.LauncherActivity" </dev/null >/dev/null 2>&1 \
  || die "could not start the launcher activity for user $FG_UID"
cropped=""
for _i in $(seq 1 30); do
  crop="$(adb -s "$SERIAL" shell dumpsys wallpaper 2>/dev/null | tr -d '\r' \
    | awk -v u="User $FG_UID:" '/wallpaper state:/ { in_sys = (index($0, "System wallpaper state:") > 0); next }
        in_sys && index($0, u) { f = 1; next } f && /mCropHint=/ { print; exit }')"
  # dumpsys prints "Rect(0, 0 - 0, 0)" with spaces; compare without them.
  case "${crop//[[:space:]]/}" in *"Rect(0,0-0,0)"*|"") sleep 1 ;; *) cropped="${crop//[[:space:]]/}"; break ;; esac
done
[ -n "$cropped" ] || die "profile '$FG_KEY' (user $FG_UID): wallpaper still has no crop 30 s after foregrounding (dumpsys: '${crop:-none}')"
ok "profile '$FG_KEY' (user $FG_UID): wallpaper rendered after foregrounding ($cropped)"
hook="$(adb -s "$SERIAL" logcat -d -s WallpaperForegroundFix:* 2>/dev/null | tr -d '\r' | grep -c "Re-applied the config wallpaper" || true)"
[ "${hook:-0}" -ge 1 ] && ok "foreground hook re-applied the wallpaper ($hook time(s) in logcat)" \
  || warn_line="no WallpaperForegroundFix line in logcat (crop may have come from elsewhere)"
[ -z "${warn_line:-}" ] || printf ' ! %s\n' "$warn_line" >&2
adb -s "$SERIAL" shell am switch-user 0 </dev/null >/dev/null || true

ok "L4 provisioning-config passed"
