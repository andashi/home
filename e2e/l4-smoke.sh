#!/usr/bin/env bash
# L4 smoke test: validates the end-to-end harness (ADR 0005) without any
# Phase-2 dependencies.
#
#   e2e/l4-smoke.sh [path/to/kvaesitso.apk]
#
# What it does:
#   1. acquires the device lock (as "l4-smoke")
#   2. boots the dedicated test instance (emulator-5556, own qcow2 overlays
#      under <gos-repo>/emulator/instances/test) from the `clean` snapshot
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
SERIAL="emulator-5556"
export SERIAL
export OVERLAY_DIR="$GOS_REPO/emulator/instances/test"
# Second instance alongside the working one requires -read-only (see run.sh).
# All writes are discarded on exit; the run starts from the `clean` snapshot.
export READ_ONLY=1
SNAPSHOT="${SNAPSHOT:-clean}"
APK="${1:-$(dirname "$0")/../app/app/build/outputs/apk/default/debug/app-default-debug.apk}"
# Overridable: PKG=org.andashi.home APK=... runs the scenario against the release build.
PKG="${PKG:-org.andashi.home.debug}"

c(){ [ -t 1 ] && printf '\033[%sm%s\033[0m\n' "$1" "$2" || printf '%s\n' "$2"; }
log(){ c '1;34' ":: $*"; }; ok(){ c '1;32' " + $*"; }
die(){ c '1;31' " x $*" >&2; exit 1; }

[ -d "$GOS_REPO/emulator" ] || die "provisioning repo not found at $GOS_REPO (set GOS_REPO)"
[ -f "$APK" ] || die "APK not found: $APK (build it or pass a path)"

cleanup() {
  (cd "$GOS_REPO" && SERIAL="$SERIAL" emulator/run.sh stop) >/dev/null 2>&1 || true
  (cd "$GOS_REPO" && emulator/device-lock.sh release l4-smoke) >/dev/null 2>&1 || true
}
trap cleanup EXIT

(cd "$GOS_REPO" && emulator/device-lock.sh acquire l4-smoke)

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
