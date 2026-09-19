#!/usr/bin/env bash
# L4 smoke test: validates the end-to-end harness (ADR 0005) without any
# Phase-2 dependencies.
#
#   e2e/l4-smoke.sh [path/to/kvaesitso.apk]
#
# What it does:
#   1. acquires the instance's device lock (as "l4-smoke@<serial>#<pid>")
#   2. boots the test instance (default emulator-5556 with its own qcow2
#      overlays under <gos-repo>/emulator/instances/test; SERIAL and
#      OVERLAY_DIR pick another one) from the `clean` snapshot
#   3. installs the Kvaesitso debug APK
#   4. asserts the package is installed and the launcher activity resolves
#   5. stops the instance and releases the lock
#
# The emulator harness (run.sh, device-lock.sh, snapshots, overlays) lives in
# the provisioning repo — see docs/architecture/adr/0005-testing-strategy.md.
# The default APK path assumes a prior
#   ./gradlew :app:app:assembleDefaultDebug
set -euo pipefail

GOS_REPO="${GOS_REPO:-$HOME/Development/GrapheneOS}"
# One instance per session (README of the provisioning repo, "Emulator
# instances"): SERIAL and OVERLAY_DIR name the instance and always go together.
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
LOCK_OWNER="l4-smoke@$SERIAL#$$"
SNAPSHOT="${SNAPSHOT:-clean}"
APK="${1:-$(dirname "$0")/../app/app/build/outputs/apk/default/debug/app-default-debug.apk}"
# Overridable: PKG=org.andashi.home APK=... runs the scenario against the release build.
PKG="${PKG:-org.andashi.home.debug}"

c(){ [ -t 1 ] && printf '\033[%sm%s\033[0m\n' "$1" "$2" || printf '%s\n' "$2"; }
log(){ c '1;34' ":: $*"; }; ok(){ c '1;32' " + $*"; }
die(){ c '1;31' " x $*" >&2; exit 1; }

[ -d "$GOS_REPO/emulator" ] || die "provisioning repo not found at $GOS_REPO (set GOS_REPO)"
[ -f "$APK" ] || die "APK not found: $APK (build it or pass a path)"

# Only a run that holds the lock may stop the instance: a run whose acquire
# failed must not take down the one that holds it (#27).
HAVE_LOCK=0
cleanup() {
  [ "$HAVE_LOCK" = 1 ] || return 0
  (cd "$GOS_REPO" && SERIAL="$SERIAL" emulator/run.sh stop) >/dev/null 2>&1 || true
  (cd "$GOS_REPO" && emulator/device-lock.sh release "$LOCK_OWNER" "$SERIAL") >/dev/null 2>&1 || true
}
trap cleanup EXIT

(cd "$GOS_REPO" && emulator/device-lock.sh acquire "$LOCK_OWNER" "$SERIAL")
HAVE_LOCK=1

log "booting $SERIAL from snapshot '$SNAPSHOT' (overlays: $OVERLAY_DIR)"
(cd "$GOS_REPO" && SNAPSHOT="$SNAPSHOT" emulator/run.sh start)

log "installing $(basename "$APK")"
install_out="$(adb -s "$SERIAL" install -r "$APK" 2>&1)" || { printf '%s\n' "$install_out" >&2; die "adb install failed"; }
grep -q '^Success' <<<"$install_out" || { printf '%s\n' "$install_out" >&2; die "adb install failed"; }

# No grep -q here: under pipefail, -q exits after the first match and the
# SIGPIPE to adb makes the pipeline fail despite the match.
adb -s "$SERIAL" shell pm list packages | tr -d '\r' | grep -x "package:$PKG" >/dev/null \
  || die "$PKG not installed"
ok "package installed: $PKG"

launcher="$(adb -s "$SERIAL" shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME "$PKG" | tr -d '\r' || true)"
grep -q "$PKG" <<<"$launcher" || die "no HOME activity for $PKG"
ok "launcher activity resolves: $(grep -oP 'name=\K\S+' <<<"$launcher" | head -1)"

ok "L4 smoke passed"
