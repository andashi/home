#!/usr/bin/env bash
# Fails when an e2e script waits by counting rounds (#164). A loop like
#
#   for i in $(seq 10); do <adb check> && break; sleep 1; done
#
# reads as "10 seconds" but is 10 rounds, and each round is an adb call that
# can take seconds (a uiautomator dump took 3.84 s on the emulator) or hang
# for good. Waits go through the library's retry_for / retry_rounds, whose
# bound is wall-clock time or a stated number of capped rounds (#126).
#
# A loop that sleeps but is not a wait (an animation, a measurement's
# repetitions) says so on the line above it:  # not a wait: <why>
#
#   e2e/check-waits.sh [dir]        # exit 1 and name every counting wait
set -euo pipefail
cd "${1:-$(dirname "$0")}"  # a directory laid out like e2e/, for the tests

python3 - *.sh */*.sh <<'PY'
import re, sys

LOOP = re.compile(r'^\s*(for\s+\w+\s+in\s+\$\(seq\b[^)]*\)|while\s+\[\s+"?\$elapsed"?\s+-lt\b)')
found = 0
for path in sys.argv[1:]:
    # The library holds the deadline mechanism itself; test files hold loops
    # as fixtures.
    if path == "lib/grid-device.sh" or path.endswith(".test.sh"):
        continue
    lines = open(path).read().split("\n")
    i = 0
    while i < len(lines):
        if not LOOP.match(lines[i]):
            i += 1
            continue
        start = i
        # The loop body: up to the `done` that closes this loop, counting
        # nested do/done pairs; a one-line loop closes on its own line.
        depth, body = 0, []
        while i < len(lines):
            line = re.sub(r"#.*$", "", lines[i])
            depth += len(re.findall(r"\bdo\b", line)) - len(re.findall(r"\bdone\b", line))
            body.append(line)
            i += 1
            if depth <= 0:
                break
        marked = start > 0 and lines[start - 1].strip().startswith("# not a wait:")
        if not marked and any(re.search(r"\bsleep\b", l) for l in body):
            print(f"::error file=e2e/{path},line={start + 1}::counts rounds instead of waiting against a deadline: {lines[start].strip()}")
            found = 1
        # Look inside the body too: a marked repetition loop can still hold a
        # wait of its own.
        i = start + 1
sys.exit(found)
PY
echo "no e2e script waits by counting rounds"
