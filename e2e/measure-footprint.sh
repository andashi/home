#!/usr/bin/env bash
# Footprint measurement for the module diet (#20): what the launcher costs
# before a removal and what it costs after.
#
#   e2e/measure-footprint.sh [--static] [--label NAME] [--out FILE] [APK]
#   e2e/measure-footprint.sh --compare BEFORE.tsv AFTER.tsv
#
# Two halves, because they have very different costs:
#
#   static   APK size by category, dex method references, declared
#            permissions, Gradle module count. Host only - no emulator, no
#            device lock, a few seconds. This is the half that belongs in
#            every removal PR, and the half that CI could run.
#   runtime  cold start, PSS/RSS and idle CPU of the launcher process on the
#            GrapheneOS test instance. Needs the emulator and its lock, so it
#            costs a boot cycle (minutes).
#
# `--static` stops after the first half. Without it both run.
#
# Output is a TSV of `metric<TAB>value<TAB>unit` lines plus a sibling
# `<out>.permissions` file, both diffable; `--compare` prints before/after
# with deltas and names the permissions that appeared or disappeared. Results
# for the diet live in e2e/measurements/ and are committed, so a PR can point
# at the baseline instead of restating it.
#
# Why the process is measured in the owner profile only: the andashi setup
# runs the launcher in every non-managed profile (6 of them as of
# 2026-09-20), but the emulator keeps at most three users running at a time
# and evicts the rest, so a device-wide total is not measurable there. The
# report multiplies the per-process figure by the profile count and labels
# that line as an extrapolation, because that is what it is.
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
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# One instance per session (README of the provisioning repo, "Emulator
# instances"): SERIAL and OVERLAY_DIR name the instance and always go together.
INSTANCE_OVERRIDE="${SERIAL:+s}${OVERLAY_DIR:+o}"
SERIAL="${SERIAL:-emulator-5556}"
export SERIAL
export OVERLAY_DIR="${OVERLAY_DIR:-$GOS_REPO/emulator/instances/test}"

# `clean` is the honest base for a measurement: profiles-ready has run
# 00-profiles.sh, which leaves extra users behind that compete for memory.
SNAPSHOT="${SNAPSHOT:-clean}"
PKG="${PKG:-org.andashi.home.debug}"
PROFILES_JSON="$GOS_REPO/config/profiles.json"

# Cold start is noisy: the first launch after install pays for dexopt and a
# cold page cache, so one run is discarded before the measured ones, and the
# median of the rest is reported rather than the mean.
WARMUP_RUNS="${WARMUP_RUNS:-2}"
START_RUNS="${START_RUNS:-5}"
# Idle CPU is sampled over a window rather than read once: `dumpsys cpuinfo`
# reports a load average over its own window, which is not ours.
CPU_WINDOW="${CPU_WINDOW:-10}"
# Time for the launcher to finish its first frame, widget hosting and search
# index warm-up before memory is read. Measured on the test instance: PSS is
# still climbing for ~5 s after the window is up.
SETTLE="${SETTLE:-15}"

APKANALYZER="${APKANALYZER:-${ANDROID_HOME:-/opt/android-sdk}/cmdline-tools/latest/bin/apkanalyzer}"

DASHES='------------------------------'

c(){ [ -t 1 ] && printf '\033[%sm%s\033[0m\n' "$1" "$2" || printf '%s\n' "$2"; }

# Paths are printed relative to the repo when they are inside it and
# absolute when they are not: `realpath --relative-to` happily produces
# ../../../../tmp/... for an --out somewhere else, which reads as noise.
show_path() { case "$1" in "$REPO"/*) printf '%s\n' "${1#"$REPO"/}" ;; *) printf '%s\n' "$1" ;; esac; }
log(){ c '1;34' ":: $*"; }; ok(){ c '1;32' " + $*"; }; warn(){ c '1;33' " ! $*"; }
die(){ c '1;31' " x $*" >&2; exit 1; }

usage() {
  sed -n '2,31p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

# --- arguments -----------------------------------------------------------

STATIC_ONLY=0
LABEL=""
OUT=""
COMPARE=0
APK=""
ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --static|--static-only) STATIC_ONLY=1; shift ;;
    --label) LABEL="${2:-}"; [ -n "$LABEL" ] || die "--label needs a name"; shift 2 ;;
    --out) OUT="${2:-}"; [ -n "$OUT" ] || die "--out needs a path"; shift 2 ;;
    --compare) COMPARE=1; shift ;;
    -h|--help) usage 0 ;;
    -*) die "unknown option: $1 (--help)" ;;
    *) ARGS+=("$1"); shift ;;
  esac
done

# --- compare mode --------------------------------------------------------

# Joins two result files on the metric name. Metrics present in only one of
# them are still printed (with an empty column), because a removal that drops
# a whole metric - the last permission of a kind, a dex file - is exactly
# what this is meant to surface.
compare() { # $1 = before.tsv, $2 = after.tsv
  local before="$1" after="$2"
  [ -f "$before" ] || die "not found: $before"
  [ -f "$after" ] || die "not found: $after"

  printf '%-28s %14s %14s %14s %9s\n' METRIC BEFORE AFTER DELTA CHANGE
  printf '%-28s %14s %14s %14s %9s\n' "$(printf '%.28s' "$DASHES")" "$(printf '%.14s' "$DASHES")" "$(printf '%.14s' "$DASHES")" "$(printf '%.14s' "$DASHES")" "$(printf '%.9s' "$DASHES")"
  awk -F'\t' '
    NR == FNR { if ($0 !~ /^#/ && NF >= 2) { b[$1] = $2; order[++n] = $1 } ; next }
    $0 !~ /^#/ && NF >= 2 { a[$1] = $2; if (!($1 in b)) order[++n] = $1 }
    END {
      for (i = 1; i <= n; i++) {
        m = order[i]
        bv = (m in b) ? b[m] : ""
        av = (m in a) ? a[m] : ""
        if (bv != "" && av != "" && bv + 0 == bv && av + 0 == av) {
          d = av - bv
          pct = (bv + 0 != 0) ? sprintf("%+.1f%%", d * 100.0 / bv) : ""
          printf "%-28s %14s %14s %+14d %9s\n", m, bv, av, d, pct
        } else {
          printf "%-28s %14s %14s %14s %9s\n", m, (bv == "" ? "-" : bv), (av == "" ? "-" : av), "", ""
        }
      }
    }
  ' "$before" "$after"

  local bperm="${before%.tsv}.permissions" aperm="${after%.tsv}.permissions"
  if [ -f "$bperm" ] && [ -f "$aperm" ]; then
    local gone added
    gone="$(comm -23 <(sort "$bperm") <(sort "$aperm") || true)"
    added="$(comm -13 <(sort "$bperm") <(sort "$aperm") || true)"
    printf '\n'
    if [ -n "$gone" ]; then
      c '1;32' "permissions dropped:"
      printf '  - %s\n' $gone
    fi
    if [ -n "$added" ]; then
      c '1;31' "permissions added:"
      printf '  + %s\n' $added
    fi
    [ -n "$gone$added" ] || printf 'permissions unchanged\n'
  fi
}

if [ "$COMPARE" = 1 ]; then
  [ "${#ARGS[@]}" -eq 2 ] || die "--compare needs exactly two result files"
  compare "${ARGS[0]}" "${ARGS[1]}"
  exit 0
fi

# --- setup ---------------------------------------------------------------

APK="${ARGS[0]:-$REPO/app/app/build/outputs/apk/default/debug/app-default-debug.apk}"
[ -f "$APK" ] || die "APK not found: $APK (./gradlew :app:app:assembleDefaultDebug, or pass a path)"
[ -x "$APKANALYZER" ] || die "apkanalyzer not found at $APKANALYZER (set APKANALYZER or ANDROID_HOME)"
command -v jq >/dev/null || die "jq not found"

LABEL="${LABEL:-$(cd "$REPO" && git rev-parse --short HEAD 2>/dev/null || echo current)}"
OUT="${OUT:-$REPO/e2e/measurements/$LABEL.tsv}"
mkdir -p "$(dirname "$OUT")"
PERM_OUT="${OUT%.tsv}.permissions"

: > "$OUT"
emit() { printf '%s\t%s\t%s\n' "$1" "$2" "${3:-}" >> "$OUT"; }
note() { printf '# %s\n' "$*" >> "$OUT"; }

note "label: $LABEL"
note "commit: $(cd "$REPO" && git rev-parse HEAD 2>/dev/null || echo unknown)"
note "apk: $(basename "$APK")"
note "measured: $(date -u +%Y-%m-%dT%H:%M:%SZ)"

# --- static half ---------------------------------------------------------

log "static: $(basename "$APK")"

emit apk.size.file "$(stat -c %s "$APK")" bytes

# download-size is apkanalyzer's estimate of what the store would ship; it
# takes noticeably longer than file-size because it recompresses, so it is
# measured once here rather than per category.
download_size="$("$APKANALYZER" apk download-size "$APK" 2>/dev/null || true)"
[ -n "$download_size" ] || die "apkanalyzer apk download-size failed on $APK"
emit apk.size.download "$download_size" bytes

# Uncompressed size per category, straight from the zip listing: no
# apkanalyzer round trip, and uncompressed is the figure that tracks what was
# actually removed (compression ratios move when content moves).
while IFS=$'\t' read -r cat size; do
  emit "apk.size.$cat" "$size" bytes
done < <(unzip -l "$APK" | awk '
  NR > 3 && NF >= 4 {
    size = $1; f = $4
    cat = (f ~ /\.dex$/)        ? "dex"    :
          (f ~ /^res\//)        ? "res"    :
          (f ~ /^lib\//)        ? "lib"    :
          (f ~ /^assets\//)     ? "assets" :
          (f ~ /resources\.arsc/) ? "arsc"  : "other"
    s[cat] += size
  }
  END { for (c in s) printf "%s\t%d\n", c, s[c] }' | sort)

# Method references, not method definitions: the reference count is what the
# 64k-per-dex limit counts and what tracks "how much library surface does
# this build still reach into".
dexrefs="$("$APKANALYZER" dex references "$APK" 2>/dev/null || true)"
[ -n "$dexrefs" ] || die "apkanalyzer dex references failed on $APK"
emit dex.files "$(printf '%s\n' "$dexrefs" | grep -c .)" count
emit dex.method_refs "$(printf '%s\n' "$dexrefs" | awk -F'\t' '{s += $2} END {print s+0}')" count

# Permissions are a first-class diet metric, not a curiosity: several of the
# security issues this work closes (#13 MANAGE_EXTERNAL_STORAGE, #14 via
# services/accounts) are visible here as a line that disappears.
"$APKANALYZER" manifest permissions "$APK" 2>/dev/null | grep -v '^WARNING:' | sort > "$PERM_OUT" \
  || die "apkanalyzer manifest permissions failed on $APK"
emit manifest.permissions "$(grep -c . "$PERM_OUT")" count

emit gradle.modules "$(grep -cE '^\s*include\(' "$REPO/settings.gradle.kts")" count

ok "static metrics written to $(show_path "$OUT")"

if [ "$STATIC_ONLY" = 1 ]; then
  column -t -s$'\t' "$OUT"
  exit 0
fi

# --- runtime half --------------------------------------------------------

[ "$INSTANCE_OVERRIDE" = "" ] || [ "$INSTANCE_OVERRIDE" = "so" ] \
  || die "SERIAL and OVERLAY_DIR name ONE instance - set both or neither (provisioning README, \"Emulator instances\"). Overriding only one runs one instance's disk under another instance's lock, because the lock is keyed by serial"
[ -d "$GOS_REPO/emulator" ] || die "provisioning repo not found at $GOS_REPO (set GOS_REPO)"

# Unique per run: acquire is re-entrant for the same owner, so two runs of
# this script on one instance must not share a name, or the second gets in
# and its cleanup stops the first one's emulator (#27).
LOCK_OWNER="measure-footprint@$SERIAL#$$"

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

# No grep -q on an adb pipeline: under pipefail, -q exits after the first
# match and the SIGPIPE to adb makes the pipeline fail despite the match.
activity="$(adb -s "$SERIAL" shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME "$PKG" | tr -d '\r' | grep -oP 'name=\K\S+' | head -1 || true)"
[ -n "$activity" ] || die "no HOME activity for $PKG"
component="$PKG/$activity"

# The launcher is measured as the home app, not as an app that happens to be
# open: holding the HOME role changes what it does at startup (widget host,
# shortcut queries) and therefore what it costs.
adb -s "$SERIAL" shell cmd role add-role-holder --user 0 android.app.role.HOME "$PKG" >/dev/null 2>&1 || true
holder="$(adb -s "$SERIAL" shell cmd role get-role-holders --user 0 android.app.role.HOME 2>/dev/null | tr -d '\r')"
case "$holder" in
  *"$PKG"*) ok "HOME role held by $PKG" ;;
  *) warn "HOME role is '$holder', not $PKG - figures are for a non-default launcher" ;;
esac

# The battery is unplugged before anything is measured, and this is not a
# detail. NavBarEffects (app/ui, drawn full-screen over the nav bar area)
# runs `while (isActive) { withInfiniteAnimationFrameMillis {} ... }` for as
# long as the battery status is CHARGING *or FULL*, reallocating its bubble
# array and invalidating a full-screen Canvas on every frame. The emulator is
# permanently on AC and reports 100%, so on a plugged-in instance that loop
# never stops: measured 2026-09-20, the launcher held ~110% of one core and
# rendered ~30 fps on an otherwise idle home screen, split between
# RenderThread (56%) and the main thread (46%).
#
# That is a real cost on a charging phone, but it is a constant here and it
# has nothing to do with how many modules the build contains, so leaving it
# on would drown the signal this series is looking for. Unplugged, the home
# screen is genuinely static and cpu.home_screen measures what is left.
log "unplugging the battery (see the NavBarEffects note above)"
adb -s "$SERIAL" shell dumpsys battery unplug >/dev/null 2>&1 || true
adb -s "$SERIAL" shell dumpsys battery set status 3 >/dev/null 2>&1 || true
battery_status="$(adb -s "$SERIAL" shell dumpsys battery 2>/dev/null | tr -d '\r' | awk -F': *' '/^ *status:/ { print $2; exit }')"
[ "$battery_status" = "3" ] \
  || warn "battery status is '$battery_status', expected 3 (discharging) - the charging animation may still be running"

# Cold start: force-stop empties the process, `am start -W` waits for the
# first frame and reports TotalTime. WaitTime would include the time the
# system spent tearing down the previous activity, which is not ours.
cold_start() {
  adb -s "$SERIAL" shell am force-stop "$PKG" >/dev/null 2>&1 || true
  sleep 1
  adb -s "$SERIAL" shell am start -W -n "$component" 2>/dev/null | tr -d '\r' \
    | awk -F': *' '/^TotalTime/ { print $2; exit }'
}

log "cold start: $WARMUP_RUNS warm-up + $START_RUNS measured runs"
for _ in $(seq "$WARMUP_RUNS"); do cold_start >/dev/null || true; done
times=()
for i in $(seq "$START_RUNS"); do
  t="$(cold_start || true)"
  [ -n "$t" ] || die "am start -W reported no TotalTime (run $i)"
  times+=("$t")
  printf '   run %s: %s ms\n' "$i" "$t"
done
median="$(printf '%s\n' "${times[@]}" | sort -n | awk '{v[NR] = $1} END {print (NR % 2) ? v[(NR+1)/2] : int((v[NR/2] + v[NR/2+1]) / 2)}')"
emit battery.status "${battery_status:-unknown}" enum
emit start.cold.median "$median" ms
emit start.cold.min "$(printf '%s\n' "${times[@]}" | sort -n | head -1)" ms
emit start.cold.max "$(printf '%s\n' "${times[@]}" | sort -n | tail -1)" ms
ok "cold start median: ${median} ms"

log "settling ${SETTLE}s before measuring"
sleep "$SETTLE"

# CPU first, memory after: `dumpsys meminfo` makes the target process do work,
# which would land inside the CPU window and inflate it.
#
# Two figures, because they answer different questions. `cpu.startup` is the
# CPU time the process has burned from launch through the settle window - the
# cost of starting up and reaching steady state, which is where a removed
# module's initialisation shows. `cpu.home_screen` is the rate it keeps
# burning afterwards while nothing is happening.
#
# Deliberately not called "idle": measured 2026-09-20 on the test instance,
# the baseline build sits at ~110% of one core on the home screen across three
# consecutive 20 s windows (109.85 / 110.75 / 110.45) with 39 threads, so the
# launcher is demonstrably not idle there. Debug build, and the emulator has
# no GPU (Graphics PSS is 0, so rendering is software), which inflates
# anything that animates - the absolute number does not transfer to a Pixel.
# It is stable to under 1% between windows, which is what makes it usable as a
# before/after comparison.
pid="$(adb -s "$SERIAL" shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
if [ -n "$pid" ]; then
  # utime + stime, summed over all threads, from /proc/<pid>/stat. Fields 14
  # and 15 are only at those positions because the comm field (2) holds a
  # process name, which never contains a space.
  read_ticks() { adb -s "$SERIAL" shell cat "/proc/$pid/stat" 2>/dev/null | tr -d '\r' | awk '{print $14 + $15}'; }
  hz="$(adb -s "$SERIAL" shell getconf CLK_TCK 2>/dev/null | tr -d '\r')"
  hz="${hz:-100}"
  t0="$(read_ticks || true)"
  if [ -n "$t0" ]; then
    emit cpu.startup "$(awk -v t="$t0" -v hz="$hz" 'BEGIN { printf "%.2f", t / hz }')" s
    emit threads "$(adb -s "$SERIAL" shell "ls /proc/$pid/task | wc -l" 2>/dev/null | tr -d '\r')" count
    sleep "$CPU_WINDOW"
    t1="$(read_ticks || true)"
    [ -n "$t1" ] || die "/proc/$pid/stat became unreadable during the CPU window"
    emit cpu.home_screen "$(awk -v a="$t0" -v b="$t1" -v w="$CPU_WINDOW" -v hz="$hz" 'BEGIN { printf "%.2f", (b - a) * 100.0 / (hz * w) }')" pct_core
  else
    warn "/proc/$pid/stat not readable - CPU figures skipped"
  fi
else
  warn "launcher process not running after settle - CPU figures skipped"
fi

# The same window again, this time plugged in. NavBarEffects animates for as
# long as the battery reads CHARGING or FULL, so this figure carries the cost
# of that animation while cpu.home_screen stays clean. Keeping both means a
# removal that takes the animation out is visible as a number rather than as
# an assertion, and a regression that starts animating something else is too.
if [ -n "${pid:-}" ] && [ -n "${t0:-}" ]; then
  adb -s "$SERIAL" shell dumpsys battery set ac 1 >/dev/null 2>&1 || true
  adb -s "$SERIAL" shell dumpsys battery set status 2 >/dev/null 2>&1 || true
  # The animation starts from a battery broadcast, not immediately.
  sleep 3
  tc0="$(read_ticks || true)"
  if [ -n "$tc0" ]; then
    sleep "$CPU_WINDOW"
    tc1="$(read_ticks || true)"
    [ -n "$tc1" ] || die "/proc/$pid/stat became unreadable during the charging CPU window"
    emit cpu.home_screen_charging "$(awk -v a="$tc0" -v b="$tc1" -v w="$CPU_WINDOW" -v hz="$hz" 'BEGIN { printf "%.2f", (b - a) * 100.0 / (hz * w) }')" pct_core
  fi
  # Back to discharging, so memory below is read in the same state as the
  # cold starts and cpu.home_screen were.
  adb -s "$SERIAL" shell dumpsys battery unplug >/dev/null 2>&1 || true
  adb -s "$SERIAL" shell dumpsys battery set status 3 >/dev/null 2>&1 || true
  sleep 3
fi

# `Native Heap: 0` in the App Summary is what this device reports, in both
# the Pss and the Rss column (verified against the raw dumpsys output on
# 2026-09-20) - it is not a parse failure, and it should not be "fixed".
#
# App Summary rather than the per-heap table: PSS is the figure that answers
# "what does this process cost the device", RSS the one that answers "how
# much is resident". Both are reported because a removal can move one
# without the other (shared framework pages vs. our own dex).
meminfo="$(adb -s "$SERIAL" shell dumpsys meminfo "$PKG" 2>/dev/null | tr -d '\r')"
[ -n "$meminfo" ] || die "dumpsys meminfo returned nothing for $PKG"
mem_field() { # $1 = row label in the App Summary block
  printf '%s\n' "$meminfo" | awk -v want="$1" '
    /App Summary/ { in_sum = 1 }
    in_sum {
      line = $0
      sub(/^[ \t]+/, "", line)
      if (index(line, want ":") == 1) {
        rest = substr(line, length(want) + 2)
        n = split(rest, f, /[ \t]+/)
        for (i = 1; i <= n; i++) if (f[i] ~ /^[0-9]+$/) { print f[i]; exit }
      }
    }'
}
emit mem.pss.total "$(printf '%s\n' "$meminfo" | awk '/TOTAL PSS:/ { for (i = 1; i <= NF; i++) if ($i == "PSS:") { print $(i+1); exit } }')" KB
emit mem.rss.total "$(printf '%s\n' "$meminfo" | awk '/TOTAL RSS:/ { for (i = 1; i <= NF; i++) if ($i == "RSS:") { print $(i+1); exit } }')" KB
emit mem.java_heap "$(mem_field 'Java Heap')" KB
emit mem.native_heap "$(mem_field 'Native Heap')" KB
emit mem.code "$(mem_field 'Code')" KB
emit mem.graphics "$(mem_field 'Graphics')" KB

# Extrapolation, deliberately separate from the measured lines: the launcher
# runs once per non-managed profile, but the emulator keeps at most three
# users running, so the device-wide total cannot be measured here.
if [ -f "$PROFILES_JSON" ]; then
  profiles="$(jq -r '[.profiles[] | select(.type != "managed")] | length' "$PROFILES_JSON" 2>/dev/null || echo "")"
  if [ -n "$profiles" ] && [ "$profiles" -gt 0 ] 2>/dev/null; then
    pss="$(awk -F'\t' '$1 == "mem.pss.total" { print $2 }' "$OUT")"
    emit profiles.non_managed "$profiles" count
    [ -n "$pss" ] && emit mem.pss.all_profiles_est "$((pss * profiles))" KB
  fi
fi

printf '\n'
column -t -s$'\t' "$OUT"
printf '\n'
ok "written: $(show_path "$OUT")"
ok "compare later with: e2e/measure-footprint.sh --compare $(show_path "$OUT") <after>.tsv"
