#!/usr/bin/env bash
# Footprint measurement for the module diet (#20): what the launcher costs
# before a removal and what it costs after.
#
#   e2e/measure-footprint.sh [--static] [--runs N] [--label NAME] [--out FILE] [APK]
#   e2e/measure-footprint.sh --compare BEFORE.tsv AFTER.tsv
#
# Two halves, because they have very different costs:
#
#   static   APK size by category, dex method references, declared
#            permissions, Gradle module count. Host only - no emulator, no
#            device lock, a few seconds. This is the half that belongs in
#            every removal PR, and the half that CI could run.
#   runtime  cold start, PSS/RSS, CPU and threads of the launcher process on
#            the GrapheneOS test instance. Needs the emulator and its lock, so
#            it costs a boot cycle (minutes) per run.
#
# `--runs N` repeats the whole runtime cycle N times and reports the median
# plus a `<metric>.spread` line, the range as a percentage of that median. One
# run shows a large effect; anything smaller needs N >= 3, because these
# figures spread ~6% on memory and ~20% on CPU between boots of the same build.
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

# Full measurement cycles (boot, install, measure). One is enough to see a
# large effect; quoting a small one needs at least three, because runtime
# figures spread ~6% on memory and ~20% on CPU between boots of the same
# build (measured 2026-09-20, table in e2e/measurements/README.md).
RUNS="${RUNS:-1}"
# Cold start is noisy within a cycle too: the first launch after install pays
# for dexopt and a cold page cache, so warm-up runs are discarded and the
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
  sed -n '2,36p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
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
    --runs) RUNS="${2:-}"; shift 2
            case "$RUNS" in ''|*[!0-9]*) die "--runs needs a positive integer" ;; esac
            [ "$RUNS" -ge 1 ] || die "--runs needs a positive integer" ;;
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
          # %d would truncate: a 3.90 -> 1.50 change printed as -2, not -2.40
          delta = (d == int(d)) ? sprintf("%+d", d) : sprintf("%+.2f", d)
          printf "%-28s %14s %14s %14s %9s\n", m, bv, av, delta, pct
        } else {
          printf "%-28s %14s %14s %14s %9s\n", m, (bv == "" ? "-" : bv), (av == "" ? "-" : av), "", ""
        }
      }
    }
  ' "$before" "$after"

  # Not optional: silently skipping the comparison would let a missing artifact
  # hide an added permission while --compare still exits 0.
  local bperm="${before%.tsv}.permissions" aperm="${after%.tsv}.permissions"
  [ -f "$bperm" ] || die "permission list not found: $bperm"
  [ -f "$aperm" ] || die "permission list not found: $aperm"
  if true; then
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

# One full cycle: snapshot load, install, measure. It is a function because
# the runtime figures have to be repeated to mean anything - see --runs below
# and "How much of a delta is real" in e2e/measurements/README.md. Each cycle
# reboots from the snapshot rather than just relaunching the app, because the
# variance that matters sits between boots (ART compilation state moves the
# Code figure by ~9%), and repeating inside one boot would report a spread
# that is narrower than the truth.
#
# Writes `metric<TAB>value<TAB>unit` to stdout; the caller aggregates.
run_cycle() { # $1 = run number
  local n="$1"
  log "run $n/$RUNS: booting $SERIAL from snapshot '$SNAPSHOT'" >&2
  (cd "$GOS_REPO" && SNAPSHOT="$SNAPSHOT" emulator/run.sh start) >&2

  local install_out
  install_out="$(adb -s "$SERIAL" install -r "$APK" 2>&1)" || { printf '%s\n' "$install_out" >&2; die "adb install failed"; }
  grep -q '^Success' <<<"$install_out" || { printf '%s\n' "$install_out" >&2; die "adb install failed"; }

  # No grep -q on an adb pipeline: under pipefail, -q exits after the first
  # match and the SIGPIPE to adb makes the pipeline fail despite the match.
  local activity component
  activity="$(adb -s "$SERIAL" shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME "$PKG" | tr -d '\r' | grep -oP 'name=\K\S+' | head -1 || true)"
  [ -n "$activity" ] || die "no HOME activity for $PKG"
  component="$PKG/$activity"

  # The launcher is measured as the home app, not as an app that happens to be
  # open: holding the HOME role changes what it does at startup (widget host,
  # shortcut queries) and therefore what it costs.
  adb -s "$SERIAL" shell cmd role add-role-holder --user 0 android.app.role.HOME "$PKG" >/dev/null 2>&1 || true
  local holder
  holder="$(adb -s "$SERIAL" shell cmd role get-role-holders --user 0 android.app.role.HOME 2>/dev/null | tr -d '\r')"
  case "$holder" in
    *"$PKG"*) ;;
    *) die "HOME role is '$holder', not $PKG - startup and runtime figures would not be a launcher's" ;;
  esac

  # The battery is unplugged before anything is measured, and this is not a
  # detail. NavBarEffects (removed in this series, but the point stands for
  # anything that animates) ran an infinite per-frame loop for as long as the
  # battery read CHARGING or FULL, and an emulator is permanently on AC: that
  # took the same build from 1.70% to ~110% of a core on an idle home screen.
  adb -s "$SERIAL" shell dumpsys battery unplug >/dev/null 2>&1 || true
  adb -s "$SERIAL" shell dumpsys battery set status 3 >/dev/null 2>&1 || true
  local battery_status
  battery_status="$(adb -s "$SERIAL" shell dumpsys battery 2>/dev/null | tr -d '\r' | awk -F': *' '/^ *status:/ { print $2; exit }')"
  [ "$battery_status" = "3" ] \
    || warn "battery status is '$battery_status', expected 3 (discharging) - an animation may still be running" >&2
  printf 'battery.status\t%s\tenum\n' "${battery_status:-unknown}"

  # Cold start: force-stop empties the process, `am start -W` waits for the
  # first frame and reports TotalTime. WaitTime would include the time the
  # system spent tearing down the previous activity, which is not ours.
  cold_start() {
    adb -s "$SERIAL" shell am force-stop "$PKG" >/dev/null 2>&1 || true
    sleep 1
    adb -s "$SERIAL" shell am start -W -n "$component" 2>/dev/null | tr -d '\r' \
      | awk -F': *' '/^TotalTime/ { print $2; exit }'
  }

  local _ i t
  for _ in $(seq "$WARMUP_RUNS"); do cold_start >/dev/null || true; done
  local times=()
  for i in $(seq "$START_RUNS"); do
    t="$(cold_start || true)"
    [ -n "$t" ] || die "am start -W reported no TotalTime (run $n, start $i)"
    times+=("$t")
  done
  local median
  median="$(printf '%s\n' "${times[@]}" | sort -n | awk '{v[NR] = $1} END {print (NR % 2) ? v[(NR+1)/2] : int((v[NR/2] + v[NR/2+1]) / 2)}')"
  printf 'start.cold.median\t%s\tms\n' "$median"
  log "run $n/$RUNS: cold start median ${median} ms" >&2

  sleep "$SETTLE"

  # CPU first, memory after: `dumpsys meminfo` makes the target process do
  # work, which would land inside the CPU window and inflate it.
  #
  # cpu.startup is the CPU time burned from launch through the settle window -
  # where a removed module's initialisation shows. cpu.home_screen is the rate
  # it keeps burning afterwards. Deliberately not called "idle": the launcher
  # is not necessarily idle, and the name should not claim it is.
  local pid hz t0 t1
  pid="$(adb -s "$SERIAL" shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
  if [ -n "$pid" ]; then
    # utime + stime, summed over all threads, from /proc/<pid>/stat. Fields 14
    # and 15 are only at those positions because the comm field (2) holds a
    # process name, which never contains a space.
    read_ticks() { adb -s "$SERIAL" shell cat "/proc/$pid/stat" 2>/dev/null | tr -d '\r' | awk '{print $14 + $15}'; }
    hz="$(adb -s "$SERIAL" shell getconf CLK_TCK 2>/dev/null | tr -d '\r')"; hz="${hz:-100}"
    t0="$(read_ticks || true)"
    if [ -n "$t0" ]; then
      printf 'cpu.startup\t%s\ts\n' "$(awk -v t="$t0" -v hz="$hz" 'BEGIN { printf "%.2f", t / hz }')"
      printf 'threads\t%s\tcount\n' "$(adb -s "$SERIAL" shell "ls /proc/$pid/task | wc -l" 2>/dev/null | tr -d '\r')"
      sleep "$CPU_WINDOW"
      t1="$(read_ticks || true)"
      [ -n "$t1" ] || die "/proc/$pid/stat became unreadable during the CPU window"
      printf 'cpu.home_screen\t%s\tpct_core\n' "$(awk -v a="$t0" -v b="$t1" -v w="$CPU_WINDOW" -v hz="$hz" 'BEGIN { printf "%.2f", (b - a) * 100.0 / (hz * w) }')"

      # The same window plugged in, which is where the cost of anything that
      # animates while charging lives. Keeping both means such a cost stays
      # visible instead of being measured away by the unplug above.
      local tc0 tc1
      adb -s "$SERIAL" shell dumpsys battery set ac 1 >/dev/null 2>&1 || true
      adb -s "$SERIAL" shell dumpsys battery set status 2 >/dev/null 2>&1 || true
      sleep 3   # the animation starts from a battery broadcast, not immediately
      tc0="$(read_ticks || true)"
      [ -n "$tc0" ] || die "/proc/$pid/stat unreadable before the charging CPU window"
      if true; then
        sleep "$CPU_WINDOW"
        tc1="$(read_ticks || true)"
        [ -n "$tc1" ] || die "/proc/$pid/stat became unreadable during the charging CPU window"
        printf 'cpu.home_screen_charging\t%s\tpct_core\n' "$(awk -v a="$tc0" -v b="$tc1" -v w="$CPU_WINDOW" -v hz="$hz" 'BEGIN { printf "%.2f", (b - a) * 100.0 / (hz * w) }')"
      fi
      # back to discharging, so memory is read in the same state as the rest
      adb -s "$SERIAL" shell dumpsys battery unplug >/dev/null 2>&1 || true
      adb -s "$SERIAL" shell dumpsys battery set status 3 >/dev/null 2>&1 || true
      sleep 3
    else
      warn "/proc/$pid/stat not readable - CPU figures skipped" >&2
    fi
  else
    warn "launcher process not running after settle - CPU figures skipped" >&2
  fi

  # `Native Heap: 0` in the App Summary is what this device reports, in both
  # the Pss and the Rss column (verified against the raw dumpsys output on
  # 2026-09-20) - it is not a parse failure, and it should not be "fixed".
  #
  # App Summary rather than the per-heap table: PSS answers "what does this
  # process cost the device", RSS "how much is resident". Both, because a
  # removal can move one without the other.
  local meminfo
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
  printf 'mem.pss.total\t%s\tKB\n' "$(printf '%s\n' "$meminfo" | awk '/TOTAL PSS:/ { for (i = 1; i <= NF; i++) if ($i == "PSS:") { print $(i+1); exit } }')"
  printf 'mem.rss.total\t%s\tKB\n' "$(printf '%s\n' "$meminfo" | awk '/TOTAL RSS:/ { for (i = 1; i <= NF; i++) if ($i == "RSS:") { print $(i+1); exit } }')"
  printf 'mem.java_heap\t%s\tKB\n' "$(mem_field 'Java Heap')"
  printf 'mem.native_heap\t%s\tKB\n' "$(mem_field 'Native Heap')"
  printf 'mem.code\t%s\tKB\n' "$(mem_field 'Code')"
  printf 'mem.graphics\t%s\tKB\n' "$(mem_field 'Graphics')"

  (cd "$GOS_REPO" && SERIAL="$SERIAL" emulator/run.sh stop) >/dev/null 2>&1 || true
}

# Only well-formed `metric<TAB>value<TAB>unit` lines are data. Anything else
# reaching this file would be aggregated as if it were a measurement, so it
# is dropped here rather than trusted not to happen.
RAW="$(mktemp)"
trap 'rm -f "$RAW"; cleanup' EXIT
for run in $(seq "$RUNS"); do
  run_cycle "$run" | grep -P '^[a-z][a-z0-9_.]*\t[^\t]+\t[A-Za-z_]*$' >> "$RAW"
done

# Median across runs, plus the spread, because a delta smaller than the spread
# is not a result. Non-numeric metrics (battery.status) are passed through.
emit_aggregated() {
  awk -F'\t' -v runs="$RUNS" '
    { if (!($1 in seen)) { order[++n] = $1; seen[$1] = 1; unit[$1] = $3 }
      vals[$1] = vals[$1] " " $2 }
    END {
      for (i = 1; i <= n; i++) {
        m = order[i]
        c = split(vals[m], v, " ")
        if (c != runs) {
          printf "metric %s has %d of %d samples - a cycle did not report it\n", m, c, runs > "/dev/stderr"
          bad = 1
        }
        numeric = 1
        for (j = 1; j <= c; j++) if (v[j] + 0 != v[j] && v[j] != "0") numeric = 0
        if (!numeric || c == 0) { printf "%s\t%s\t%s\n", m, v[1], unit[m]; continue }
        # insertion sort: c is at most a handful of runs
        for (j = 2; j <= c; j++) { key = v[j] + 0; k = j - 1
          while (k >= 1 && v[k] + 0 > key) { v[k+1] = v[k]; k-- }
          v[k+1] = key }
        med = (c % 2) ? v[(c+1)/2] + 0 : (v[c/2] + v[c/2+1]) / 2.0
        printf "%s\t%s\t%s\n", m, (med == int(med) ? sprintf("%d", med) : sprintf("%.2f", med)), unit[m]
        if (c > 1 && unit[m] != "enum") {
          lo = v[1] + 0; hi = v[c] + 0
          printf "%s.spread\t%.1f\tpct\n", m, (med != 0 ? (hi - lo) * 100.0 / med : 0)
        }
      }
      if (bad) exit 1
    }' "$RAW"
}
emit_aggregated >> "$OUT"
note "runtime runs: $RUNS"


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
