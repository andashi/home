#!/usr/bin/env bash
# The launcher's first frame after an unfold, build against build (#122).
#
#   e2e/measure-unfold.sh a.apk b.apk [...]
#
# Measured, recorded, not asserted. For each APK the script prepares one
# snapshot of the same state - installed, HOME, provisioning's mauritius
# wallpaper, a right-edge favorites dock with four favorites, folded, on the
# home screen, after one unfold/fold cycle so the glass backdrop cache holds
# both displays - and then unfolds from those snapshots in turn, RUNS rounds,
# so host drift hits every build alike.
#
# Robust by construction to prior state (every measured unfold starts by
# loading a snapshot, which resets RAM and disks) and to host load (the
# interleaving). Not robust to a concurrent workload on the same instance:
# an unpinned `./gradlew connected*` from another shell installs and runs
# there mid-series. Hold the instance's lock, and pin ANDROID_SERIAL on
# every Gradle device task (AGENTS.md, "Emulator").
#
# An unfold here is the hinge sensor swept from 0 to 180 degrees in 10-degree
# steps (the console's `sensor set hinge-angle0`), not `cmd device_state
# state 2`: the override skips the sensor and the device-state policy, and
# the sweep is what a hand does. Per run, from a Perfetto trace
# (SurfaceFlinger's frametimeline plus logcat; the GrapheneOS kernel has no
# usable ftrace, so there are no atrace sections) and `dumpsys gfxinfo
# framestats`, all in ms from the display-state request:
#
#   config_ms       the configuration change reaches activities
#   frame_start_ms  the launcher's first frame after the configuration change starts
#   frame_end_ms    ... and is presented
#   frame_ms        its length
#   screen_on_ms    the display power controller unblocks the inner display
#   delay .. total  that frame's phases (e2e/unfold_framestats.py)
#
# The inner display stays dark until every visible window has drawn at the
# new size, so screen_on_ms is what a user waits for, and the launcher's
# frame is on that path.
#
# Output: e2e/measurements/unfold-<revs>.tsv (one line per run, headed by the
# builds' revisions and SHA-256s) unless OUT is set. Each APK's revision comes
# from REVS (space-separated, in APK order), else "unknown".
#
# Instance: SERIAL + OVERLAY_DIR (default emulator-5562, instances/test-fold-gpu,
# a foldable that runs GPU=host since its first start), snapshot `clean`,
# under the instance's device lock. Everything runs as the unrooted shell
# (uid 2000, asserted); `run.sh start` ends with `adb root`, so run
# `adb -s $SERIAL unroot` after starting the instance. Needs `trace_processor`
# (TRACE_PROCESSOR, else on PATH).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
GOS_REPO="${GOS_REPO:-$HOME/Development/andashi/provisioning}"
export SERIAL="${SERIAL:-emulator-5562}"
export OVERLAY_DIR="${OVERLAY_DIR:-$GOS_REPO/emulator/instances/test-fold-gpu}"
export GPU="${GPU:-host}"
PKG=org.andashi.home
RUNS="${RUNS:-5}"
TP="${TRACE_PROCESSOR:-$(command -v trace_processor || true)}"
# A session that already holds the instance passes its own owner name; the
# lock is re-entrant for it and is then left held at the end.
LOCK_OWNER="${LOCK_OWNER:-measure-unfold@$SERIAL#$$}"
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
  # Its own snapshots, ~3.5 GB each; `clean` stays.
  for n in "${names[@]}"; do adb -s "$SERIAL" emu avd snapshot delete "$n" >/dev/null 2>&1 || true; done
  [ "$HELD_BEFORE" = 1 ] || "$LOCK" release "$LOCK_OWNER" "$SERIAL" >/dev/null 2>&1 || true
}
trap cleanup EXIT
# shellcheck source=lib/grid-device.sh
. "$HERE/lib/grid-device.sh"

[ $# -ge 1 ] || die "usage: $0 a.apk [b.apk ...]"
[ -x "$TP" ] || die "trace_processor not found (TRACE_PROCESSOR)"
"$LOCK" acquire "$LOCK_OWNER" "$SERIAL" >/dev/null || die "$SERIAL is locked by someone else"
unrooted_shell
read -r -a revs <<<"${REVS:-}"
resolve_postures   # POSTURE_CLOSED / POSTURE_OPENED: the ids differ between instances

sh_() { adb -s "$SERIAL" shell "$@"; }
perfetto_finished() { adb_t shell pidof perfetto >/dev/null 2>&1; [ $? = 1 ]; }
rev() { printf '%s' "${revs[$1]:-unknown}"; }
# A snapshot load can leave the host's adb transport offline for good (seen
# on emulator-5562 after `run.sh start` plus `adb unroot`); reconnect it.
restore() {
  "$RUN" restore "$1" >/dev/null
  timeout 20 adb -s "$SERIAL" wait-for-device \
    || { timeout 10 adb -s "$SERIAL" reconnect >/dev/null 2>&1; timeout 30 adb -s "$SERIAL" wait-for-device; } \
    || die "$SERIAL stayed offline after loading $1"
}

cat > "$WORK/fold.jsonc" <<'EOF'
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
    "grid": { "columns": 4, "layouts": { "fold": { "items": [
      { "id": "dock", "widget": "favorites", "x": 7, "y": 0, "w": 1, "h": 6 }
    ] } } }
  }
}
EOF

cat > "$WORK/trace.pbtx" <<'EOF'
buffers { size_kb: 32768 fill_policy: RING_BUFFER }
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
data_sources { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true } } }
data_sources { config { name: "android.log" android_log_config { log_ids: LID_DEFAULT log_ids: LID_SYSTEM } } }
duration_ms: 9000
EOF

cat > "$WORK/split.sql" <<'EOF'
with t0 as (select min(ts) ts from android_logs where msg like 'Requesting Transition to state%'),
     cfg as (select min(l.ts) ts from android_logs l, t0 where l.msg like 'Config changes=%' and l.ts > t0.ts),
     unb as (select min(l.ts) ts from android_logs l, t0 where l.msg like 'Unblocked screen on%' and l.ts > t0.ts),
     f as (select a.ts, a.dur, a.name from actual_frame_timeline_slice a join process p using (upid), t0, cfg
           where p.name = 'org.andashi.home' and a.layer_name like '%LauncherActivity%' and a.ts >= cfg.ts
           order by a.ts limit 1)
select round((cfg.ts - t0.ts)/1e6,1), round((f.ts - t0.ts)/1e6,1), round((f.ts + f.dur - t0.ts)/1e6,1),
       round(f.dur/1e6,1), round((unb.ts - t0.ts)/1e6,1), f.name
from t0, cfg, unb, f;
EOF

# --- one snapshot per build -------------------------------------------------
apks=("$@")
for k in "${!apks[@]}"; do
  apk="${apks[k]}"
  name="unfold-m$k"; names+=("$name")
  log "preparing $name from $(basename "$apk")"
  restore clean
  adb -s "$SERIAL" install -r "$apk" >/dev/null
  sh_ cmd role add-role-holder android.app.role.HOME "$PKG"
  sh_ cmd device_state state "$POSTURE_OPENED" >/dev/null
  show_home; sleep 5
  adb -s "$SERIAL" shell content write --uri "content://$PKG.config-ingest/wallpapers/mauritius.jpg" \
    < "$GOS_REPO/themes/mauritius/tall/wallpaper.jpg"
  push_config "$WORK/fold.jsonc" "fold fixture" || push_config "$WORK/fold.jsonc" "fold fixture, again"
  sh_ cmd device_state state "$POSTURE_CLOSED" >/dev/null; sleep 3; wake_screen; sleep 1
  sh_ cmd device_state state "$POSTURE_OPENED" >/dev/null; sleep 3; wake_screen; sleep 2
  sh_ cmd device_state state "$POSTURE_CLOSED" >/dev/null; sleep 3; show_home; sleep 5
  "$RUN" snapshot "$name" >/dev/null
done

# --- interleaved unfolds ------------------------------------------------------
revlist=""
for k in "${!apks[@]}"; do revlist+="$(rev "$k")-"; done
OUT="${OUT:-$HERE/measurements/unfold-${revlist%-}.tsv}"
{
  printf '# serial %s, overlay %s, GPU %s, adb uid 2000, runs %s\n' "$SERIAL" "$(basename "$OVERLAY_DIR")" "$GPU" "$RUNS"
  for k in "${!apks[@]}"; do
    printf '# %s = rev %s, apk sha256 %s\n' "${names[k]}" "$(rev "$k")" "$(sha256sum "${apks[k]}" | cut -d' ' -f1)"
  done
  printf 'build\trun\thostload\tconfig_ms\tframe_start_ms\tframe_end_ms\tframe_ms\tscreen_on_ms\tdelay\tanim\tlayout\trecord\tsync\tissue\tswap\ttotal\n'
} > "$OUT"

# not a wait: RUNS measurement repetitions
for run in $(seq "$RUNS"); do
  for name in "${names[@]}"; do
    restore "$name"
    sleep 3; wake_screen
    # The snapshot was folded with the device-state override; hand the state
    # back to the hinge sensor, folded, before the sweep drives it.
    adb -s "$SERIAL" emu sensor set hinge-angle0 0 >/dev/null
    sh_ cmd device_state state reset >/dev/null
    sleep 1
    state="$(sh_ cmd device_state print-state | tr -d '\r' | tail -1)"
    [ "$state" = "$POSTURE_CLOSED" ] || die "$name: not folded under the hinge sensor before the sweep (state $state)"
    sh_ rm -f /data/misc/perfetto-traces/unfold.pftrace
    sh_ dumpsys gfxinfo "$PKG" reset >/dev/null
    adb -s "$SERIAL" shell perfetto --txt -c - -o /data/misc/perfetto-traces/unfold.pftrace --background \
      < "$WORK/trace.pbtx" >/dev/null
    sleep 2.5
    load="$(cut -d' ' -f1 /proc/loadavg)"
    # not a wait: the hinge sweep, an animation at 40 ms per step
    for a in $(seq 10 10 180); do adb -s "$SERIAL" emu sensor set hinge-angle0 "$a" >/dev/null; sleep 0.04; done
    sleep 2
    sh_ dumpsys gfxinfo "$PKG" framestats > "$WORK/f.framestats"
    # Up to 20 s of wall-clock time for perfetto to finish the trace (#164).
    # Only pidof's "not found" (1) counts; a timed-out call (124) does not.
    retry_for 20 perfetto_finished || log "$name: perfetto still running after 20 s; the trace may be cut short"
    adb -s "$SERIAL" pull /data/misc/perfetto-traces/unfold.pftrace "$WORK/t.pftrace" >/dev/null 2>&1
    split="$("$TP" -q "$WORK/split.sql" "$WORK/t.pftrace" 2>/dev/null | tail -n +2 | tr -d '"' | tr ',' '\t')"
    # The same frame in framestats, by its vsync id (the last column).
    vsync="${split##*$'\t'}"
    phases="NA"
    if [ -n "$split" ]; then
      phases="$(python3 "$HERE/unfold_framestats.py" "$WORK/f.framestats" "vsync=$vsync" 2>/dev/null | tail -1 | cut -f2,4-10)" \
        || phases="NA"
    fi
    printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$run" "$load" "${split%$'\t'*}" "$phases" | tee -a "$OUT"
  done
done
"$RUN" restore clean >/dev/null
log "written: $OUT"
