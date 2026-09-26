#!/usr/bin/env bash
# The launcher's cold start, build against build (#167).
#
#   e2e/measure-coldstart.sh a.apk b.apk [...]
#
# Measured, recorded, not asserted. For each APK the script prepares one
# snapshot of the same state - installed, HOME, provisioning's mauritius
# wallpaper managed, a zone file with favorites, a grid layout and search
# settings - and then cold-starts the launcher from those snapshots in turn,
# RUNS rounds, so host drift hits every build alike. Every round loads the
# build's snapshot (RAM and disks reset, the page cache included) and makes
# STARTS cold starts in it: `am force-stop`, then `am start -W`, whose
# TotalTime is the time to the launcher's first frame. The first start of a
# round and the later ones are kept apart (start 1 vs start > 1), since the
# first one after a snapshot load can differ. The builds' order within a
# round alternates, so a load trend inside a round lands on each alike.
#
# Output: e2e/measurements/coldstart-<revs>.tsv (one line per start, headed by
# the builds' revisions and SHA-256s) unless OUT is set. Each APK's revision
# comes from REVS (space-separated, in APK order), else "unknown".
#
# Instance: SERIAL + OVERLAY_DIR (default emulator-5562, instances/test-fold-gpu),
# snapshot `clean`, under the instance's device lock, as the unrooted shell
# (uid 2000, asserted; `run.sh start` ends with `adb root`, so run
# `adb -s $SERIAL unroot` after starting the instance). Robust by construction
# to prior state (the snapshot) and to host load (the interleaving); not to a
# concurrent workload on the same instance - pin ANDROID_SERIAL on every
# Gradle device task elsewhere (AGENTS.md, "Emulator").
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
GOS_REPO="${GOS_REPO:-$HOME/Development/andashi/provisioning}"
export SERIAL="${SERIAL:-emulator-5562}"
export OVERLAY_DIR="${OVERLAY_DIR:-$GOS_REPO/emulator/instances/test-fold-gpu}"
export GPU="${GPU:-host}"
PKG=org.andashi.home
RUNS="${RUNS:-10}"
STARTS="${STARTS:-3}"
# The host's 1-minute load average may not exceed this when a round starts;
# a round above it ends the run. Set before the first sample, never judged
# afterwards: a series on a busy host cannot resolve tens of milliseconds.
# The harness loads the host itself (a booted emulator, adb), so the ceiling
# is meaningful only against that floor: LOAD_FLOOR is the load measured with
# the instance booted and idle, recorded next to the ceiling.
MAX_LOAD="${MAX_LOAD:-}"
LOAD_FLOOR="${LOAD_FLOOR:-unmeasured}"
LOCK_OWNER="${LOCK_OWNER:-measure-coldstart@$SERIAL#$$}"
WORK="$(mktemp -d)"
RUN="$GOS_REPO/emulator/run.sh"
LOCK="$GOS_REPO/emulator/device-lock.sh"
HELD_BEFORE=0
"$LOCK" status 2>/dev/null | grep -qF "device $SERIAL held by: $LOCK_OWNER " && HELD_BEFORE=1

log() { printf ':: %s\n' "$*"; }
die() { printf 'x %s\n' "$*" >&2; exit 1; }
names=()
cleanup() {
  rm -rf "$WORK"
  # Back to `clean` before the lock goes, also after a failure: the next
  # user of the instance must not find a build of ours installed. If that
  # fails, the lock stays held, and says so, rather than hand on a dirty
  # instance.
  local keep_lock=0
  if [ "${#names[@]}" -gt 0 ] && ! "$RUN" restore clean >/dev/null 2>&1; then
    printf 'x could not restore clean on %s; keeping the lock (%s) so nobody inherits this run\n' \
      "$SERIAL" "$LOCK_OWNER" >&2
    keep_lock=1
  fi
  for n in "${names[@]}"; do adb -s "$SERIAL" emu avd snapshot delete "$n" >/dev/null 2>&1 || true; done
  [ "$HELD_BEFORE" = 1 ] || [ "$keep_lock" = 1 ] || "$LOCK" release "$LOCK_OWNER" "$SERIAL" >/dev/null 2>&1 || true
}
trap cleanup EXIT
# shellcheck source=lib/grid-device.sh
. "$HERE/lib/grid-device.sh"

[ $# -ge 1 ] || die "usage: $0 a.apk [b.apk ...]"
"$LOCK" acquire "$LOCK_OWNER" "$SERIAL" >/dev/null || die "$SERIAL is locked by someone else"
[ "$(adb -s "$SERIAL" shell id -u | tr -d '\r')" = 2000 ] || die "adb is not the unrooted shell"
read -r -a revs <<<"${REVS:-}"

sh_() { adb -s "$SERIAL" shell "$@"; }
rev() { printf '%s' "${revs[$1]:-unknown}"; }
restore() {
  "$RUN" restore "$1" >/dev/null
  timeout 20 adb -s "$SERIAL" wait-for-device \
    || { adb reconnect offline >/dev/null; timeout 30 adb -s "$SERIAL" wait-for-device; } \
    || die "$SERIAL stayed offline after loading $1"
}
cold_start() {
  # Only a start from no process is a cold start: a failed force-stop, or a
  # process still there after it, would measure a warm one.
  sh_ am force-stop "$PKG" >/dev/null 2>&1 || die "am force-stop $PKG failed"
  local gone=0
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    if ! sh_ pidof "$PKG" >/dev/null 2>&1; then gone=1; break; fi
    sleep 0.5
  done
  [ "$gone" = 1 ] || die "$PKG still running 5 s after force-stop"
  sleep 1
  sh_ am start -W -n "$LAUNCHER_ACTIVITY" 2>/dev/null | tr -d '\r' | awk -F': *' '/^TotalTime/ { print $2; exit }'
}

# Every section write-back follows (#155): settings, favorites, search, grid.
cat > "$WORK/zone.jsonc" <<'EOF'
{
  "schemaVersion": 2,
  "appearance": {
    "glass": { "wallpaperBlur": true, "searchWallpaperBlur": true },
    "wallpaper": { "image": "mauritius.jpg", "target": "both" }
  },
  "home": {
    "searchBar": { "position": "bottom" },
    "favorites": ["com.android.dialer", "com.android.messaging", "app.vanadium.browser", "app.grapheneos.camera"],
    "widgets": { "enabled": true },
    "grid": { "columns": 4, "layouts": {
      "phone": { "items": [ { "id": "clock", "widget": "clock", "x": 0, "y": 0, "w": 4, "h": 2 } ] },
      "fold": { "items": [ { "id": "dock", "widget": "favorites", "x": 7, "y": 0, "w": 1, "h": 6 } ] }
    } }
  },
  "search": { "layout": "grid" }
}
EOF

# --- one snapshot per build -------------------------------------------------
apks=("$@")
for k in "${!apks[@]}"; do
  apk="${apks[k]}"
  # Run-specific, so an existing snapshot of that name is never taken or deleted.
  name="cold-$$-m$k"; names+=("$name")
  log "preparing $name from $(basename "$apk")"
  restore clean
  adb -s "$SERIAL" install -r "$apk" >/dev/null
  sh_ cmd role add-role-holder android.app.role.HOME "$PKG"
  show_home; sleep 5
  sh_ content write --uri "content://$PKG.config-ingest/wallpapers/mauritius.jpg" \
    < "$GOS_REPO/themes/mauritius/tall/wallpaper.jpg"
  push_config "$WORK/zone.jsonc" "zone fixture" || push_config "$WORK/zone.jsonc" "zone fixture, again"
  # Two cold starts before the snapshot, so dexopt, the first reload and the
  # first write-back baseline are behind every build alike.
  cold_start >/dev/null || true; sleep 3
  cold_start >/dev/null || true; sleep 5
  show_home; sleep 3
  "$RUN" snapshot "$name" >/dev/null
done

# --- interleaved cold starts ------------------------------------------------
revlist=""
for k in "${!apks[@]}"; do revlist+="$(rev "$k")-"; done
OUT="${OUT:-$HERE/measurements/coldstart-${revlist%-}.tsv}"
{
  printf '# serial %s, overlay %s, GPU %s, adb uid 2000, runs %s, starts %s, load floor %s, max load %s\n' \
    "$SERIAL" "$(basename "$OVERLAY_DIR")" "$GPU" "$RUNS" "$STARTS" "$LOAD_FLOOR" "${MAX_LOAD:-none}"
  for k in "${!apks[@]}"; do
    printf '# %s = rev %s, apk sha256 %s\n' "${names[k]}" "$(rev "$k")" "$(sha256sum "${apks[k]}" | cut -d' ' -f1)"
  done
  printf 'build\trun\tstart\thostload\ttotal_ms\n'
} > "$OUT"

for run in $(seq "$RUNS"); do
  # Once per round, before its first build: a round either starts under the
  # ceiling and runs complete, or does not start. A check between its builds
  # would end the run with half a round - unpaired rows.
  if [ -n "$MAX_LOAD" ]; then
    load="$(cut -d' ' -f1 /proc/loadavg)"
    awk -v l="$load" -v m="$MAX_LOAD" 'BEGIN { exit !(l <= m) }' \
      || die "host load $load above MAX_LOAD $MAX_LOAD before round $run: run ended"
  fi
  # The order alternates round by round: with a fixed order, load that rises
  # within a round would always land on the same build.
  order=("${names[@]}")
  if [ $((run % 2)) -eq 0 ]; then
    order=(); for ((i = ${#names[@]} - 1; i >= 0; i--)); do order+=("${names[i]}"); done
  fi
  for name in "${order[@]}"; do
    restore "$name"
    sleep 3; wake_screen; sleep 2
    for start in $(seq "$STARTS"); do
      load="$(cut -d' ' -f1 /proc/loadavg)"
      # A failed force-stop ends the run here, with its own message.
      t="$(cold_start)" || exit 1
      # A missing sample would leave a pair incomplete and still look like a
      # valid series; stop instead, as measure-footprint.sh does.
      [ -n "$t" ] || die "am start -W reported no TotalTime ($name, round $run, start $start)"
      printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$run" "$start" "$load" "$t" | tee -a "$OUT"
      # Let the start settle before the next one; the last is followed by a restore.
      if [ "$start" -lt "$STARTS" ]; then sleep 4; fi
    done
  done
done
"$RUN" restore clean >/dev/null
log "written: $OUT"
