#!/usr/bin/env bash
# Tests for e2e/lib/grid-device.sh that need no device: adb is a fake on PATH.
#
#   e2e/lib/grid-device.test.sh
set -euo pipefail
cd "$(dirname "$0")"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
SERIAL=fake PKG=org.andashi.home.test
log() { :; }
die() { printf 'die: %s\n' "$*" >&2; exit 1; }
# shellcheck source=grid-device.sh
. ./grid-device.sh

failed=0
check() { # $1 = description, $2... = command that must succeed
  local what="$1"; shift
  if "$@"; then printf ' + %s\n' "$what"; else printf ' x %s\n' "$what"; failed=1; fi
}

# A fake adb whose `uiautomator dump` hangs, as a wedged device's does.
mkdir -p "$WORK/bin"
cat > "$WORK/bin/adb" <<'EOF'
#!/usr/bin/env bash
case "$*" in *"uiautomator dump"*) sleep 60 ;; esac
EOF
chmod +x "$WORK/bin/adb"

# wait_cell's timeout is wall-clock time. One dump took 3.84 s on the
# emulator (#127), so counting rounds made `wait_cell ... 10` run for most of
# a minute, and a hanging dump made it unbounded.
wait_cell_is_bounded() {
  local start=$SECONDS
  ( PATH="$WORK/bin:$PATH" wait_cell analog 3 "test" ) 2>/dev/null && return 1
  [ $((SECONDS - start)) -le 6 ]
}
check "wait_cell gives up after its timeout even while the dump hangs" wait_cell_is_bounded

# Control: a device that answers has the cell found at once.
mkdir -p "$WORK/answers"
cat > "$WORK/answers/adb" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"cat /sdcard/grid-dump.xml"*)
    echo '<hierarchy><node resource-id="grid-item:analog" bounds="[0,100][200,300]"/></hierarchy>' ;;
esac
EOF
chmod +x "$WORK/answers/adb"
wait_cell_finds_a_cell() {
  local start=$SECONDS
  ( PATH="$WORK/answers:$PATH" wait_cell analog 3 "test" ) && [ $((SECONDS - start)) -le 1 ]
}
check "wait_cell returns at once when the cell is on screen" wait_cell_finds_a_cell

exit "$failed"
