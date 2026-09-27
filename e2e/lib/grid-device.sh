#!/usr/bin/env bash
# Device helpers shared by the e2e scripts. Everything talks to the launcher
# through its public surface: the ingest provider, the reload broadcast, the
# read-back provider, `input` and `uiautomator dump`.
#
# One definition per helper, here (#126): a script that needs different
# behaviour gets a parameter, or a function under its own name.
# e2e/check-helpers.sh fails CI when a script redefines one of these.
#
# Expects: SERIAL, PKG, WORK (a scratch dir), and the log/die functions.

RECEIVER="$PKG/de.mm20.launcher2.config.service.ReloadConfigReceiver"
ACTION="$PKG.action.RELOAD_CONFIG"
STATE_URI="content://$PKG.state"
INGEST_URI="content://$PKG.config-ingest/launcher.json"
LAUNCHER_ACTIVITY="$PKG/de.mm20.launcher2.ui.launcher.LauncherActivity"
DEVICE_CONFIG="/storage/emulated/0/Android/data/$PKG/files/config/launcher.json"

# One adb call, cut off at ADB_DEADLINE (a $SECONDS value) when a caller has
# set one, 60 s from now otherwise. A wedged connection hangs adb for good;
# every helper a wait loop calls goes through this, so the loop's timeout is
# wall-clock time and not a count of attempts (#126, #147).
adb_t() {
  local left=$(( ${ADB_DEADLINE:-$((SECONDS + 60))} - SECONDS ))
  [ "$left" -gt 0 ] || return 124
  timeout "$left" adb -s "$SERIAL" "$@"
}

# adb_t's output without the device's carriage returns, and adb's own
# status. For every caller that branches on the status: a pipe into tr took
# tr's status unless the caller had pipefail, and a timed-out call passed as
# an empty answer (#175 review).
adb_out() {
  local out rc=0
  out="$(adb_t "$@")" || rc=$?
  printf '%s' "${out//$'\r'/}"
  return "$rc"
}

# The deadline $1 seconds from now, but never later than one a caller has
# already set: a wait inside a wait gets what is left of the outer one at
# most (query_json_as_user runs inside wait_diagnostics_sha, #164).
deadline_in() { # $1 = seconds
  local d=$((SECONDS + $1))
  [ -n "${ADB_DEADLINE:-}" ] && [ "$ADB_DEADLINE" -lt "$d" ] && d=$ADB_DEADLINE
  echo "$d"
}

# Runs "$@" until it succeeds or $1 seconds have passed, and fails then. The
# adb calls of every attempt share that deadline, so one slow call cannot
# carry the loop past it: a uiautomator dump alone took 3.84 s on the
# emulator (#127).
retry_for() { # $1 = timeout (s), $2... = command
  # Computed before `local` shadows the caller's deadline.
  local d t0=$SECONDS timeout=$1
  d=$(deadline_in "$1")
  local ADB_DEADLINE=$d
  shift
  # not a wait: the deadline mechanism itself, bounded by ADB_DEADLINE
  while [ "$ADB_DEADLINE" -gt "$SECONDS" ]; do
    if "$@"; then
      # How close it came, on stderr: stdout belongs to callers that
      # capture it. 9.8 s of 10 is a pass that is about to become a failure.
      # The bound reported is the one in force, which an outer deadline
      # may have shortened, with the one asked for when they differ.
      local bound=$((ADB_DEADLINE - t0)) asked=""
      [ "$bound" = "$timeout" ] || asked=", asked for $timeout s"
      printf '   %s: after %s s of %s s%s\n' "$*" "$((SECONDS - t0))" "$bound" "$asked" >&2
      return 0
    fi
    [ $((ADB_DEADLINE - SECONDS)) -gt 1 ] || break
    sleep 1
  done
  return 1
}

# Runs "$@" up to $1 times, the adb calls of each attempt capped at $2
# seconds, so the total is bounded by construction (rounds x (cap + 1 s)).
# For waits whose question is "did it happen", where the cost of asking
# varies with host load and has nothing to do with the answer. Leaves the
# attempt count in ROUNDS_USED.
ROUNDS_USED=0
retry_rounds() { # $1 = rounds, $2 = cap per round (s), $3... = command
  local rounds=$1 cap=$2 i
  shift 2
  # not a wait: bounded rounds, each capped, stopping at an outer deadline
  for ((i = 1; i <= rounds; i++)); do
    ROUNDS_USED=$i
    ADB_DEADLINE=$(deadline_in "$cap") "$@" && return 0
    [ "$i" -lt "$rounds" ] || break
    # Inside a wall-clock wait, stop once its deadline has no room for a
    # pause and another round (#179 review).
    [ -z "${ADB_DEADLINE:-}" ] || [ $((ADB_DEADLINE - SECONDS)) -gt 1 ] || break
    sleep 1
    # And again after it: a slow host can stretch the pause past a second.
    [ -z "${ADB_DEADLINE:-}" ] || [ "$ADB_DEADLINE" -gt "$SECONDS" ] || break
  done
  return 1
}

# Succeeds when "$@" prints anything.
shows() { [ -n "$("$@" 2>/dev/null)" ]; }

# adb as the unrooted shell (uid 2000), which is what a release build
# offers. After `run.sh start` loads a snapshot, adbd answers a moment
# later than the emulator reports up, and `adb unroot` restarts it again;
# asserting once failed right there (emulator-5560, 2026-09-25 22:11). So
# it is polled against a real deadline, reconnecting an offline transport
# in between.
# The reconnect names this serial and is bounded like every other call: a
# bare `adb reconnect offline` reaches every emulator on the host, including
# instances other sessions hold under their locks.
is_unrooted_shell() {
  [ "$(adb_t shell id -u 2>/dev/null | tr -d '\r')" = 2000 ] && return 0
  adb_t reconnect >/dev/null 2>&1 || true
  return 1
}
unrooted_shell() { # [$1 = timeout (s), default 60]
  # One deadline for the whole operation: a hanging `adb unroot` must not
  # start the clock late.
  local timeout=${1:-60}
  local d
  d=$(deadline_in "$timeout")
  local ADB_DEADLINE=$d
  adb_t unroot >/dev/null 2>&1 || true
  retry_for "$((ADB_DEADLINE - SECONDS))" is_unrooted_shell \
    || die "adb is not the unrooted shell (uid 2000) after ${timeout}s"
  log "adb as unrooted shell (uid 2000)"
}

# Boots SERIAL's instance when it is down, from SNAPSHOT (default clean), with
# the instance's lock held, and leaves BOOTED=1 when it did; finish_instance
# ends it. Running is `run.sh running`'s answer, the one definition: the qemu
# process on the port (provisioning 514d9c6), not adb's answer, which is
# briefly offline after a boot and after `adb unroot`. Needs GOS_REPO, SERIAL
# and an exported LOCK_OWNER.
BOOTED=0
boot_instance() {
  gos_run running >/dev/null 2>&1 && return 0
  log "booting $SERIAL from ${SNAPSHOT:-clean}"
  SNAPSHOT="${SNAPSHOT:-clean}" gos_run start >/dev/null || die "could not boot $SERIAL"
  BOOTED=1
}

# Deletes a run's own snapshots (~3.5 GB each), one retry each, and checks
# the list afterwards: fails and names what is left, or every one of them
# when the list cannot be read, since an unread list is not an empty one.
delete_snapshots() { # $@ = snapshot names
  [ $# -gt 0 ] || return 0
  local n listed left=()
  for n in "$@"; do
    # A failed delete is not fatal here: the list below is the check.
    ADB_DEADLINE=$(deadline_in 30) adb_t emu avd snapshot delete "$n" >/dev/null 2>&1 \
      || { sleep 2; ADB_DEADLINE=$(deadline_in 30) adb_t emu avd snapshot delete "$n" >/dev/null 2>&1; } \
      || :
  done
  if ! listed="$(ADB_DEADLINE=$(deadline_in 15) adb_out emu avd snapshot list 2>/dev/null)"; then
    printf 'x could not list the snapshots on %s; check by hand for: %s\n' "$SERIAL" "$*" >&2
    return 1
  fi
  # A name is a whole field of the list: neither another run's cold-1-m10
  # nor its cold-1-m1-old is this run's cold-1-m1.
  for n in "$@"; do
    awk -v n="$n" '{ for (i = 1; i <= NF; i++) if ($i == n) { found = 1; exit } } END { exit !found }' <<<"$listed" \
      && left+=("$n")
  done
  [ "${#left[@]}" -eq 0 ] \
    || { printf 'x snapshots left on %s, delete them by hand: %s\n' "$SERIAL" "${left[*]}" >&2; return 1; }
}

query_json() { # $1 = provider path (config|diagnostics)
  # The provider answers one row whose json value spans many lines. Anything
  # else is an error, never an empty answer: a caller reading "" as "no
  # config" would pass for the wrong reason.
  local out
  out="$(adb_out shell content query --uri "$STATE_URI/$1" 2>&1)" \
    || { printf 'content query failed: %s\n' "$out" >&2; return 1; }
  case "$out" in
    "Row: 0 json="*) printf '%s' "${out#Row: 0 json=}" ;;
    *) printf 'unexpected provider output: %s\n' "$out" >&2; return 1 ;;
  esac
}

# Waits for a /diagnostics report matching the jq filter; the match is left in
# LAST_REPORT for the caller to assert on.
LAST_REPORT=""
LAST_SEEN_REPORT=""
report_matches() { # $1 = jq filter
  # A failed query keeps the last report seen, for the timeout message.
  local got
  got="$(query_json diagnostics 2>/dev/null)" && [ -n "$got" ] || return 1
  LAST_SEEN_REPORT="$got"
  jq -e "$1" <<<"$got" >/dev/null 2>&1 && LAST_REPORT="$got"
}
wait_report() { # $1 = jq filter, $2 = timeout (s), $3 = description
  LAST_SEEN_REPORT=""
  retry_for "$2" report_matches "$1" && return 0
  printf 'last /diagnostics report:\n%s\n' "$LAST_SEEN_REPORT" >&2
  die "timed out (${2}s) waiting for report: $3"
}

# The report about a push is the one carrying its hash that was not there
# before the push, never the one with an expected trigger (ADR 0003, section
# 4: a measurement reload can replace the watcher's report). A step that
# claims a cause asserts the trigger itself, with wait_report.
# The provider answers null itself before the first report; a failed query is
# retried and then fails, never read as null, or an older report of the same
# hash would pass as the push's.
REPORT_NOW=""
report_grab() { REPORT_NOW="$(query_json diagnostics 2>/dev/null)"; }
report_now() { # the current report, or the provider's null before the first one
  retry_for "${REPORT_NOW_TIMEOUT:-10}" report_grab || die "could not read the report before a push"
  printf '%s' "$REPORT_NOW"
}
wait_push_report() { # $1 = report_now before the push, $2 = sha256 pushed, $3 = timeout (s), $4 = description
  wait_report ".configSha256 == \"$2\" and . != $1" "$3" "$4"
}

write_config() { # $1 = local file
  local out
  out="$(adb_out shell content write --uri "$INGEST_URI" < "$1" 2>&1)" \
    || { printf '%s\n' "$out" >&2; die "content write failed"; }
  [ -z "$out" ] || { printf '%s\n' "$out" >&2; die "content write reported an error"; }
}

reload_broadcast() {
  local out
  out="$(adb_out shell am broadcast -n "$RECEIVER" -a "$ACTION" 2>&1)" \
    || { printf '%s\n' "$out" >&2; die "am broadcast failed"; }
  case "$out" in
    *"Broadcast completed"*) ;;
    *) printf '%s\n' "$out" >&2; die "am broadcast did not complete" ;;
  esac
}

# The launcher reports inert-key and unknown-key only for keys of the pushed
# file, so a push that expects every key it writes to take effect asserts
# that the report names none. A call that writes an ignored key on purpose
# declares it, and the declaration is itself an assertion: the reported set
# must equal the declared set, so a declared key that applied fails too.
check_ignored_keys() { # $1 = report, $2 = declared paths (space-separated), $3 = stage name
  local reported declared
  reported="$(jq -r '.diagnostics[]? | select(.code == "inert-key" or .code == "unknown-key") | .path' <<<"$1" | sort -u)" \
    || die "$3: could not read the diagnostics of the push's report"
  declared="$(tr -s ' ' '\n' <<<"$2" | sed '/^$/d' | sort -u)"
  [ "$reported" = "$declared" ] && return 0
  local extra missing
  extra="$(comm -23 <(printf '%s\n' "$reported" | sed '/^$/d') <(printf '%s\n' "$declared" | sed '/^$/d') | paste -sd' ')"
  missing="$(comm -13 <(printf '%s\n' "$reported" | sed '/^$/d') <(printf '%s\n' "$declared" | sed '/^$/d') | paste -sd' ')"
  jq -c '.diagnostics' <<<"$1" >&2
  die "$3: ${extra:+ignored by the launcher, not declared: $extra}${extra:+${missing:+; }}${missing:+declared --ignored, but not reported: $missing}"
}

# Push a file and wait until the launcher reports it applied (by hash), with
# no key ignored beyond the declared ones.
push_config() { # $1 = local file, $2 = stage name, [--ignored "path ..."]...
  local file=$1 stage=$2 ignored="" h before
  shift 2
  while [ $# -gt 0 ]; do
    case "$1" in
      --ignored)
        [ $# -ge 2 ] && [[ "$2" != --* ]] || die "push_config: --ignored needs a value, got: ${2:-nothing}"
        ignored="$ignored $2"; shift 2 ;;
      *) die "push_config: unknown argument: $1" ;;
    esac
  done
  h="$(sha256sum "$file" | cut -d' ' -f1)"
  before="$(report_now)" || exit 1
  write_config "$file"
  log "$stage: waiting for the reload of the pushed file (hash ${h:0:12}...)"
  wait_push_report "$before" "$h" 60 "$stage: reload of the pushed file"
  log "$stage: broadcasting explicit reload"
  before="$(report_now)" || exit 1
  reload_broadcast
  wait_push_report "$before" "$h" 30 "$stage: broadcast report"
  check_ignored_keys "$LAST_REPORT" "$ignored" "$stage"
}

# The one way to run.sh (provisioning's emulator script), for every verb.
# run.sh refuses a foreign owner itself (provisioning 08c2834); this checks,
# at the moment of the call, that the run has an owner to give it: LOCK_OWNER
# exported and non-empty. Asking the shell instead of reading the script
# cannot be fooled by quoting, ordering, unset or eval. An env prefix
# reaches run.sh: SNAPSHOT=clean gos_run start.
gos_run() { # $1 = verb, $2... = its arguments
  local attrs
  attrs="$(declare -p LOCK_OWNER 2>/dev/null)" || attrs=""
  if [[ "$attrs" != "declare -"*x*" LOCK_OWNER="* ]] || [ -z "${LOCK_OWNER:-}" ]; then
    printf 'refusing run.sh %s on %s: LOCK_OWNER is not exported with a value (%s)\n' \
      "$1" "$SERIAL" "${attrs:-unset}" >&2
    return 1
  fi
  (cd "$GOS_REPO" && SERIAL="$SERIAL" emulator/run.sh "$@")
}

# Cleanup: stop the instance this run holds, then release its lock. run.sh
# refuses a stop for anyone but LOCK_OWNER (provisioning 08c2834), and a
# refusal behind `|| true` left the instance running while the release
# unlocked it. A failed stop is printed and keeps the lock, so the instance
# stays visibly owned. Needs GOS_REPO, SERIAL and an exported LOCK_OWNER.
stop_instance() {
  local out
  out="$(gos_run stop 2>&1)" && return 0
  printf 'could not stop %s as %s:\n%s\n' "$SERIAL" "${LOCK_OWNER:-nobody}" "$out" >&2
  return 1
}
stop_and_release() {
  stop_instance || {
    printf 'keeping the lock on %s (%s), so nobody walks into a running instance\n' "$SERIAL" "${LOCK_OWNER:-nobody}" >&2
    return 1
  }
  local out
  out="$(cd "$GOS_REPO" && emulator/device-lock.sh release "$LOCK_OWNER" "$SERIAL" 2>&1)" \
    || { printf 'could not release the lock on %s (%s):\n%s\n' "$SERIAL" "$LOCK_OWNER" "$out" >&2; return 1; }
}

# The end of a run on the instance it locked. A running instance nobody holds
# is a stray, whoever booted it, so it is stopped and released - unless the
# caller held the lock before the run (HELD_BEFORE=1) and so owns the
# instance's lifecycle. Stopping one this run did not boot (BOOTED=0) is said
# out loud, with what would have kept it running.
finish_instance() {
  [ "${HELD_BEFORE:-0}" = 1 ] && return 0
  [ "${BOOTED:-0}" = 1 ] || printf ':: stopping %s: it was running and nobody held its lock. To keep an instance across a run, hold its lock before the run (device-lock.sh acquire <owner> %s) and pass that owner as LOCK_OWNER.\n' \
    "$SERIAL" "$SERIAL" >&2
  stop_and_release
}

# Fails, before anything boots, unless the APK installs as the package the
# script works with; an unreadable package fails too, since an empty answer
# is not a match. aapt2 is AAPT2, else on PATH, else the newest build-tools.
require_apk_package() { # $1 = expected package, $2... = apks
  local want="$1" aapt2 apk got
  shift
  # A lookup that finds nothing is empty, not an exit: the check below says so.
  aapt2="${AAPT2:-$(command -v aapt2 || { ls -d "${ANDROID_HOME:-/opt/android-sdk}"/build-tools/*/aapt2 2>/dev/null || true; } | sort -V | tail -1)}"
  [ -x "$aapt2" ] || die "aapt2 not found (set AAPT2): it checks what each APK installs as"
  for apk in "$@"; do
    [ -f "$apk" ] || die "no such APK: $apk"
    got="$("$aapt2" dump packagename "$apk" 2>/dev/null)" && [ -n "$got" ] \
      || die "could not read the package of ${apk##*/}"
    [ "$got" = "$want" ] || die "${apk##*/} installs as $got, but this script works with $want"
  done
}

wake_screen() {
  adb_t shell svc power stayon true >/dev/null 2>&1 || true
  adb_t shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  adb_t shell wm dismiss-keyguard >/dev/null 2>&1 || true
  adb_t shell cmd statusbar collapse >/dev/null 2>&1 || true
}

# The HOME intent, restricted to the launcher, as the system starts it. By
# component (am start -n) the launcher lands in a task of its own: the first
# Home press then creates the home instance and finishes that one, and a
# gesture made during the swap never reaches the launcher.
show_home() {
  wake_screen
  adb_t shell am start -a android.intent.action.MAIN -c android.intent.category.HOME "$PKG" >/dev/null 2>&1 || true
}

# Prints "left top right bottom" of the first node matching the attribute.
# Dumps the screen into $WORK/dump.xml. The dump and its read-back are both
# adb calls under the caller's deadline. A dump taken while the device is
# still busy can come back empty; that is "not on screen yet", for the
# caller to retry, not a parse error.
dump_screen() {
  adb_t shell rm -f /sdcard/grid-dump.xml >/dev/null 2>&1 || true
  adb_t shell uiautomator dump /sdcard/grid-dump.xml >/dev/null 2>&1 || return 1
  adb_t shell cat /sdcard/grid-dump.xml > "$WORK/dump.raw" 2>/dev/null || return 1
  tr -d '\r' < "$WORK/dump.raw" > "$WORK/dump.xml"
  [ -s "$WORK/dump.xml" ]
}

node_bounds() { # $1 = attribute (resource-id|content-desc|text), $2 = value
  dump_screen || return 1
  python3 - "$WORK/dump.xml" "$1" "$2" <<'PY'
import re, sys
try:
    import defusedxml.ElementTree as ET
except ImportError:
    import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for node in root.iter("node"):
    if node.get(sys.argv[2], "") == sys.argv[3]:
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if m:
            print(*m.groups()); break
PY
}

# Content descriptions are what TalkBack reads: use them only for real
# labels ("Search"). The grid's automation hooks are test tags, which its
# root exposes as resource ids (#117).
desc_bounds() { node_bounds content-desc "$1"; }
id_bounds() { node_bounds resource-id "$1"; }

wait_desc() { # $1 = content-desc, $2 = timeout (s), $3 = description
  retry_for "$2" shows desc_bounds "$1" || die "timed out (${2}s) waiting for '$1' on screen: $3"
}

wait_id() { # $1 = resource-id (test tag), $2 = timeout (s), $3 = description
  retry_for "$2" shows id_bounds "$1" || die "timed out (${2}s) waiting for '$1' on screen: $3"
}

tap_bounds() { # $1 = "l t r b"
  set -- $1
  adb_t shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

tap_desc() { # $1 = content-desc
  local b
  b="$(desc_bounds "$1")" || die "uiautomator dump failed while looking for '$1'"
  [ -n "$b" ] || die "'$1' is not on screen"
  tap_bounds "$b"
}

tap_id() { # $1 = resource-id (test tag)
  local b
  b="$(id_bounds "$1")" || die "uiautomator dump failed while looking for '$1'"
  [ -n "$b" ] || die "'$1' is not on screen"
  tap_bounds "$b"
}

# "top bottom" in px: the lower edge of the status bar and the upper edge of
# the navigation bar, from the frames `dumpsys window` reports; 0 and a
# bound past any screen for a bar that is hidden or absent. With several
# displays (the Fold) the union, which refuses more rather than less.
system_bars() {
  adb_t shell dumpsys window | tr -d '\r' | awk '
    / InsetsSource id=/ && /visible=true/ && match($0, /frame=\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]/) {
      split(substr($0, RSTART + 6, RLENGTH - 6), f, /[^0-9]+/)
      if (/ type=statusBars / && f[5] > top) top = f[5]
      if (/ type=navigationBars / && /sideHint=BOTTOM/ && (bottom == "" || f[3] < bottom)) bottom = f[3]
    }
    END { print top + 0, (bottom == "" ? 1000000 : bottom) }'
}

# tap_text taps a node only where a tap reaches it (#155). A settings row
# scrolled under the top app bar is still in the dump, and a tap at its
# centre lands on the bar; l4-write-back failed on exactly that. What covers
# the centre is measured, never assumed as a height:
# - what the app draws over its own scrolling content: a node that comes
#   after the target's scrollable ancestor in the dump and lies outside it,
#   such as a top app bar of any height ([0,0][1080,264] on "Grid and icons"
#   at 480 dpi) or a floating button;
# - the status and navigation bars, from system_bars.
# A node that does not scroll is where it is drawn: a chip right under a top
# search bar, or a label its own button is drawn over, is tapped.
# Returning 1 then is the same contract as "absent": a caller that scrolls
# and retries keeps working, and one that took 1 for "absent" now fails
# loudly instead of tapping the wrong thing.
tap_text() { # $1 = visible text; returns 1 (no exit) when absent or not where a tap reaches it
  local point
  [ -n "$(node_bounds text "$1")" ] || return 1
  point="$(python3 - "$WORK/dump.xml" "$1" $(system_bars) <<'PY'
import re, sys
try:
    import defusedxml.ElementTree as ET
except ImportError:
    import xml.etree.ElementTree as ET
dump, text, top, bottom = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
root = ET.parse(dump).getroot()
def rect(node):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    return tuple(map(int, m.groups())) if m else None
order = list(root.iter("node"))
target = next(n for n in order if n.get("text", "") == text and rect(n))
l, t, r, b = rect(target)
x, y = (l + r) // 2, (t + b) // 2
if not top <= y < bottom:
    sys.exit()
parent = {c: p for p in root.iter() for c in p}
scroller = target
while scroller is not None and scroller.get("scrollable") != "true":
    scroller = parent.get(scroller)
if scroller is not None:
    inside = set(scroller.iter())
    for node in order[order.index(scroller) + 1:]:
        c = None if node in inside else rect(node)
        if c and c[0] <= x < c[2] and c[1] <= y < c[3]:
            sys.exit()
print(x, y)
PY
)"
  [ -n "$point" ] || return 1
  adb_t shell input tap $point
}

# "id left top right bottom" for every grid cell on screen.
dump_cells() {
  dump_screen || { printf "uiautomator dump failed\n" >&2; return 1; }
  python3 - "$WORK/dump.xml" <<'PY'
import re, sys
try:
    import defusedxml.ElementTree as ET
except ImportError:
    import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for node in root.iter("node"):
    tag = node.get("resource-id", "")
    if not tag.startswith("grid-item:"):
        continue
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if m:
        print(tag[len("grid-item:"):], *m.groups())
PY
}

has_cells() { [ "$(dump_cells 2>/dev/null | wc -l)" -ge "$1" ]; }
wait_cells() { # $1 = expected count, $2 = timeout (s)
  retry_for "$2" has_cells "$1" || die "timed out (${2}s) waiting for $1 cells on screen"
}

density_scale() {
  adb -s "$SERIAL" shell wm density | tr -d '\r' | awk '/density/ { print $NF / 160; exit }'
}

# "width height" of the display in pixels.
screen_size() {
  adb_t shell wm size | tr -d '\r' | awk '/size/ {s=$NF} END {split(s, a, "x"); print a[1], a[2]}'
}

# "package/activity" of the resumed activity in front; empty when there is none.
top_activity() {
  adb_t shell dumpsys activity activities | tr -d '\r' \
    | sed -n 's/.*topResumedActivity=ActivityRecord{[^ ]* [^ ]* \([^ ]*\) .*/\1/p' | head -1
}

on_top() { # $1 = package
  case "$(top_activity)" in "$1"/*) return 0 ;; *) return 1 ;; esac
}

# "Total frames rendered" of $PKG; empty when gfxinfo does not say.
frames_rendered() {
  adb_t shell dumpsys gfxinfo "$PKG" | tr -d '\r' | awk '/Total frames rendered/ { print $NF; exit }'
}

# Whether an injected touch would reach the launcher now: it has focus, no
# window transition runs (focus switches while the app-to-home transition
# still swallows input), and it has drawn no frame for 300 ms, so its main
# thread is free to handle the events as `input` sends them. Measured on the
# emulator: AGENTS.md, emulator section. For retry_for. Leaves what held it
# back in TOUCH_READY_WHY, for the caller's failure message: from outside
# the three conditions look the same.
TOUCH_READY_WHY=""
touch_ready() {
  local windows before after
  windows="$(adb_t shell dumpsys window | tr -d '\r')" || { TOUCH_READY_WHY="dumpsys window failed"; return 1; }
  grep -q "mCurrentFocus=.*$PKG/" <<<"$windows" \
    || { TOUCH_READY_WHY="focus: $(grep -m1 -o 'mCurrentFocus=.*' <<<"$windows" || echo none)"; return 1; }
  ! grep -q 'reason=Transition' <<<"$windows" \
    || { TOUCH_READY_WHY="transition: $(grep -m1 'reason=Transition' <<<"$windows" | sed 's/^ *//')"; return 1; }
  before="$(frames_rendered)" && [ -n "$before" ] || { TOUCH_READY_WHY="frames: no count"; return 1; }
  sleep 0.3
  after="$(frames_rendered)" && [ -n "$after" ] || { TOUCH_READY_WHY="frames: no count"; return 1; }
  [ "$before" = "$after" ] || { TOUCH_READY_WHY="frames $before->$after in 0.3 s"; return 1; }
  TOUCH_READY_WHY=""
}

cell_center() { # $1 = id
  local line
  line="$(dump_cells | awk -v id="$1" '$1 == id')"
  [ -n "$line" ] || return 1
  set -- $line
  printf '%s %s\n' $(( ($2 + $4) / 2 )) $(( ($3 + $5) / 2 ))
}

wait_cell() { # $1 = id, $2 = timeout (s), $3 = message
  retry_for "$2" shows cell_center "$1" || die "timed out (${2}s) waiting for cell '$1': $3"
}

# Cell pitch in px from the dock's width and its span in cells.
cell_pitch() { # $1 = dock width in cells
  local dock gap
  dock="$(dump_cells | awk '$1 == "dock"')"
  [ -n "$dock" ] || return 1
  gap="$(awk -v s="$(density_scale)" 'BEGIN { print 8 * s }')"
  python3 - "$dock" "$gap" "$1" <<'PY'
import sys
_, l, t, r, b = sys.argv[1].split(); l, r = int(l), int(r)
print(int((r - l + float(sys.argv[2])) / int(sys.argv[3])))
PY
}

# A point in a free cell: DOCK_W - 1 columns right of the dock's left edge,
# two rows above it (the fixtures leave it empty).
free_cell_point() { # $1 = dock width in cells
  local dock gap
  dock="$(dump_cells | awk '$1 == "dock"')"
  [ -n "$dock" ] || return 1
  gap="$(awk -v s="$(density_scale)" 'BEGIN { print 8 * s }')"
  python3 - "$dock" "$gap" "$1" <<'PY'
import sys
_, l, t, r, b = sys.argv[1].split(); l, t, r, b = map(int, (l, t, r, b))
gap = float(sys.argv[2]); w = int(sys.argv[3])
pitch = (r - l + gap) / w
print(int(l + (w - 0.5) * pitch), int(t - 1.5 * pitch))
PY
}

long_press() { # $1 = "x y"
  set -- $1
  adb -s "$SERIAL" shell input swipe "$1" "$2" "$1" "$2" 900
}

enter_edit_mode() { # $1 = dock width in cells
  local point
  point="$(free_cell_point "$1")" || die "no dock on screen to locate a free cell from"
  long_press "$point"
  wait_id grid-edit-done 15 "edit bar after the long press"
}

# Drag with explicit motion events (input swipe lands short of the target).
# With $4 = hold, the pointer stays down after the moves so the drag can be
# photographed; call drag_release to finish it.
DRAG_END=""
drag_cell() { # $1 = id, $2 = dx cells, $3 = dy cells, $4 = dock width, [$5 = hold]
  local from pitch x y tx ty i
  from="$(cell_center "$1")" || die "cell $1 not on screen"
  pitch="$(cell_pitch "$4")"
  x="${from%% *}"; y="${from##* }"
  tx=$(( x + $2 * pitch )); ty=$(( y + $3 * pitch ))
  adb -s "$SERIAL" shell input motionevent DOWN "$x" "$y"
  sleep 1
  for i in 1 2 3 4 5 6 7 8; do
    adb -s "$SERIAL" shell input motionevent MOVE $(( x + ($2 * pitch * i) / 8 )) $(( y + ($3 * pitch * i) / 8 ))
  done
  DRAG_END="$tx $ty"
  [ "${5:-}" = hold ] || drag_release
}

drag_release() {
  set -- $DRAG_END
  adb -s "$SERIAL" shell input motionevent UP "$1" "$2"
  DRAG_END=""
}

# Posture ids by name (the GrapheneOS fold instance counts from 0, the SDK
# foldable from 1).
resolve_postures() {
  local states
  states="$(adb -s "$SERIAL" shell cmd device_state print-states 2>/dev/null | tr -d '\r')"
  POSTURE_CLOSED="$(sed -n "s/.*identifier=\([0-9]*\), name='CLOSED'.*/\1/p" <<<"$states" | sed -n 1p)"
  POSTURE_HALF="$(sed -n "s/.*identifier=\([0-9]*\), name='HALF_OPENED'.*/\1/p" <<<"$states" | sed -n 1p)"
  POSTURE_OPENED="$(sed -n "s/.*identifier=\([0-9]*\), name='OPENED'.*/\1/p" <<<"$states" | sed -n 1p)"
  [ -n "$POSTURE_CLOSED" ] && [ -n "$POSTURE_HALF" ] && [ -n "$POSTURE_OPENED" ] \
    || die "$SERIAL has no CLOSED/HALF_OPENED/OPENED postures (not a foldable?): $states"
  log "postures: closed=$POSTURE_CLOSED half=$POSTURE_HALF opened=$POSTURE_OPENED"
}

# Makes $PKG the home app, and checks afterwards that it holds the role, by
# exact package name: org.andashi.home.debug is not org.andashi.home. The
# command's own output goes into the failure. Eleven inline copies sent it to
# /dev/null, and on 2026-09-26 11:33 a failure could not be told apart from
# a slow emulator afterwards.
grant_home_role() { # [$1 = user, default 0]
  local user=${1:-0} out holders
  out="$(adb_out shell cmd role add-role-holder --user "$user" android.app.role.HOME "$PKG" 2>&1)" \
    || die "could not grant the HOME role to $PKG (user $user): ${out:-no output}"
  holders="$(adb_out shell cmd role get-role-holders --user "$user" android.app.role.HOME 2>&1)" \
    || die "could not read the HOME role holders (user $user): ${holders:-no output}"
  grep -Fxq "$PKG" <<<"$holders" \
    || die "the HOME role is held by '${holders:-nobody}', not $PKG (user $user)${out:+; add-role-holder said: $out}"
  log "HOME role held by $PKG (user $user)"
}

# What the screen shows, from one dump: "search" while search is open (its
# filter button is on screen), "home" while the launcher's bar is on screen
# without it, "unknown" for a failed or empty dump or anything else, which
# is never taken for either. So a failed look cannot pass a check.
screen_state() {
  dump_screen || { echo unknown; return 0; }
  if grep -q 'content-desc="Show filters"' "$WORK/dump.xml"; then echo search
  elif grep -q 'content-desc="Search"' "$WORK/dump.xml"; then echo home
  else echo unknown; fi
}
search_is_open() { [ "$(screen_state)" = search ]; }
home_is_shown() { [ "$(screen_state)" = home ]; }
# One valid look, left in SCREEN: fails only on "unknown", so a caller can
# retry for a readable dump without retrying for the answer it wants.
SCREEN=unknown
screen_known() { SCREEN="$(screen_state)"; [ "$SCREEN" != unknown ]; }

# The soft keyboard's state, left in IME: shown, hidden, or unknown when adb
# did not answer. "Not shown" is only ever a positive answer.
IME=unknown
ime_read() {
  local state
  # Captured first: grep -q stops at the first match, and under pipefail the
  # writer's SIGPIPE would read as "not shown".
  state="$(adb_out shell dumpsys input_method 2>/dev/null)" || { IME=unknown; return 1; }
  if grep -q 'mInputShown=true' <<<"$state"; then IME=shown; else IME=hidden; fi
}
ime_shown() { ime_read && [ "$IME" = shown ]; }
ime_hidden() { ime_read && [ "$IME" = hidden ]; }

# Opens search from the launcher's bar and types $1, if given.
#
# Named open_search_field, not open_search, on purpose (#164): l4-search.sh
# had an open_search that also typed a letter and closed the keyboard. A
# function that moves into the library keeps its name only if it keeps its
# behaviour; a different behaviour gets a different name, so a call site
# that still means the old one fails loudly instead of changing silently.
# #172's contacts step did exactly that on a conflict-free rebase.
#
# Bounded by rounds, and each round re-issues the tap: opening search is an
# action that can be lost, and a tap that did not register cannot be waited
# out, only repeated (#164). A wall-clock 10 s had room for about two looks
# on the software-rendered fold (a dump about 4 s), and config-screenshots
# failed there on 2026-09-26. Up to 3 rounds, each capped by ROUND_CAP
# through adb_t. A success logs its rounds and seconds, so the need behind
# the 3 is recorded, not guessed.
search_open_round() {
  search_is_open && return 0
  local b
  b="$(desc_bounds Search 2>/dev/null)" || b=""
  [ -n "$b" ] && tap_bounds "$b"
  search_is_open
}
open_search_field() { # [$1 = text to type]
  local t0=$SECONDS
  retry_rounds 3 "${ROUND_CAP:-20}" search_open_round \
    || die "search did not open after 3 rounds of tapping the bar ($((SECONDS - t0)) s)"
  log "search open after $ROUNDS_USED rounds, $((SECONDS - t0)) s"
  [ -z "${1:-}" ] || adb_t shell input text "$1"
}

# Closes the soft keyboard if it comes up. It comes up a moment after the
# field is focused, so a single look right away misses it. It takes the
# first Back for itself, which would otherwise close search under a test
# of Back. KEYBOARD_TIMEOUT (5 s) for it to come up, twice that to close.
dismiss_keyboard() {
  local timeout=${KEYBOARD_TIMEOUT:-5}
  if ! retry_for "$timeout" ime_shown; then
    [ "$IME" = hidden ] && return 0
    die "could not read the keyboard's state within ${timeout}s"
  fi
  adb_t shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
  retry_for "$((2 * timeout))" ime_hidden || die "the keyboard did not close within $((2 * timeout))s"
}

# Waits until the node with resource id $1 is on the home screen, waking and
# re-showing home in between: after a posture change the activity comes back
# on the other display, and a check made before that sees the blank in
# between (it did: 2 frames on the cover).
#
# Bounded by rounds, not seconds (#126). What it waits for costs the device
# 2-3 s; what varies is the observation, a uiautomator dump, which took about
# 4 s on an unloaded host and about 15 s with a second emulator running. A
# wall-clock bound meant 3 rounds on one and 2 on the other, and one dump
# cannot tell "not yet" from "not coming". So: 3 rounds (the clean
# measurement on emulator-5560, 8 posture changes, never needed more than 2),
# each capped by ROUND_CAP through adb_t. The recovery that follows a miss
# (wake, reopen home) gets RECOVERY_CAP of its own, since a dump that used up
# the round would otherwise leave it no time and the next round would look
# at the same screen. A wedged device still ends after at most
# 3 x (ROUND_CAP + RECOVERY_CAP + 1) s, plus 2 s of diagnosis. A success
# logs the rounds and the seconds, so a slow host shows as slow instead of
# hiding inside a round count. Device runs on one host go one at a time all
# the same: every cost here assumes an unloaded host.
home_shows() {
  shows id_bounds "$1" && return 0
  # Deliberately not deadline_in: the recovery may run past the round's cap,
  # which a slow dump can use up entirely (review on #163). The bound
  # 3 x (ROUND_CAP + RECOVERY_CAP + 1) s counts it.
  local ADB_DEADLINE=$((SECONDS + ${RECOVERY_CAP:-5}))
  wake_screen; show_home
  return 1
}
wait_on_home() { # $1 = resource id, [$2 = rounds, default 3]
  local rounds=${2:-3} t0=$SECONDS focus shot="${MISS_DIR:-${TMPDIR:-/tmp}}/wait_on_home-$SERIAL-$(date +%s).png"
  if retry_rounds "$rounds" "${ROUND_CAP:-20}" home_shows "$1"; then
    log "'$1' on home after $ROUNDS_USED rounds, $((SECONDS - t0)) s"
    return 0
  fi
  # The diagnosis shares 2 s of its own: on a wedged connection it must not
  # carry the wait further than its rounds did. The picture goes through
  # `screenshot`, which picks the display on a foldable, and outside $WORK,
  # which is gone at exit. A failed picture never masks the failure.
  local ADB_DEADLINE=$((SECONDS + 2))
  focus="$(adb_t shell dumpsys window 2>/dev/null | tr -d '\r' | awk '/mCurrentFocus/ { print $NF; exit }')"
  ( screenshot "$shot" ) >/dev/null 2>&1 || shot="none"
  die "'$1' did not come back on the home screen after $rounds rounds ($((SECONDS - t0)) s; focus: ${focus:-unknown}; screen: $shot)"
}

posture() { # $1 = closed | half | opened, [$2 = resource id to wait for on home]
  local id
  case "$1" in
    closed) id="$POSTURE_CLOSED" ;;
    half) id="$POSTURE_HALF" ;;
    opened) id="$POSTURE_OPENED" ;;
    *) die "unknown posture $1" ;;
  esac
  adb -s "$SERIAL" shell cmd device_state state "$id" >/dev/null 2>&1 || die "cmd device_state state $id ($1) failed"
  sleep 4
  show_home
  [ -z "${2:-}" ] || wait_on_home "$2"
  sleep 3
}

# The physical id of the display that is ON (a foldable exposes two).
active_display() {
  # One DisplayDeviceInfo line per panel; nested braces inside, so match the
  # whole line rather than a brace-delimited run.
  adb_t shell dumpsys display | tr -d '\r' \
    | grep 'DisplayDeviceInfo{' | grep 'state ON' \
    | sed -n 's/.*uniqueId="local:\([0-9]*\)".*/\1/p' | sed -n 1p
}

# Screenshot to $1 (png). Multi-display devices need the physical id.
screenshot() { # $1 = output file
  local id
  id="$(active_display 2>/dev/null || true)"
  if [ -n "$id" ] && adb_t exec-out screencap -d "$id" -p > "$1" 2>/dev/null \
    && file "$1" | grep -q 'PNG image'; then
    return 0
  fi
  adb_t exec-out screencap -p > "$1" 2>/dev/null
  file "$1" | grep -q 'PNG image' || die "screencap did not produce a PNG for $1"
}

assert_jq() { # $1 = json, $2 = jq filter, $3 = description
  if ! jq -e "$2" >/dev/null 2>&1 <<<"$1"; then
    printf 'offending json:\n%s\n' "$1" >&2
    die "assertion failed: $3"
  fi
}

pull_config() { # $1 = local file
  adb -s "$SERIAL" pull "$DEVICE_CONFIG" "$1" >/dev/null 2>&1 || die "adb pull of $DEVICE_CONFIG failed"
}

wait_text() { # $1 = visible text, $2 = timeout (s)
  retry_for "$2" shows node_bounds text "$1" || die "timed out (${2}s) waiting for '$1' on screen"
}
