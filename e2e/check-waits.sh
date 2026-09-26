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

# A counted for loop, or any while/until loop: `while :` with an attempt
# counter counts rounds just the same. A loop that does not sleep (a
# `while read`) is left alone below.
LOOP = re.compile(r'^\s*(for\s+\w+\s+in\s+\$\(seq\b[^)]*\)|for\s*\(\(|(while|until)\b)')


def strip_comment(line):
    """The line without its comment, read the way bash reads it: a # inside
    quotes or escaped is not one, a backslash escapes the next character
    outside quotes and inside double quotes, and is literal inside single
    quotes."""
    quote = None
    escaped = False
    for i, c in enumerate(line):
        if escaped:
            escaped = False
        elif c == "\\" and quote != "'":
            escaped = True
        elif quote:
            if c == quote:
                quote = None
        elif c in "'\"":
            quote = c
        elif c == "#" and (i == 0 or line[i - 1].isspace() or line[i - 1] == ";"):
            return line[:i]
    return line
found = 0
for path in sys.argv[1:]:
    # Test files hold loops as fixtures. The library is checked too: its
    # deadline mechanism marks its own loops, and #155 had added a
    # round-counting wait_text to it that a library exemption hid.
    if path.endswith(".test.sh"):
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
            line = strip_comment(lines[i])
            depth += len(re.findall(r"\bdo\b", line)) - len(re.findall(r"\bdone\b", line))
            body.append(line)
            i += 1
            if depth <= 0:
                break
        # The marker may head a comment block that runs onto more lines, as
        # long as nothing but comments stands between it and the loop.
        k = start - 1
        while k >= 0 and lines[k].strip().startswith("#") and not lines[k].strip().startswith("# not a wait:"):
            k -= 1
        marked = k >= 0 and lines[k].strip().startswith("# not a wait:")
        # A loop whose condition is the clock is a deadline, not a count.
        # Only the condition, the text before `do`, counts.
        condition = re.split(r"\bdo\b", strip_comment(lines[start]), maxsplit=1)[0]
        marked = marked or "$SECONDS" in condition
        if not marked and any(re.search(r"\bsleep\b", l) for l in body):
            print(f"::error file=e2e/{path},line={start + 1}::counts rounds instead of waiting against a deadline: {lines[start].strip()}")
            found = 1
        # Look inside the body too: a marked repetition loop can still hold a
        # wait of its own.
        i = start + 1
sys.exit(found)
PY
echo "no e2e script waits by counting rounds"
