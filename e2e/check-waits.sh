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
# How it reads bash, and where it stops: quoted text ('...', "...",
# $'...', with backslash escapes), comments and heredoc bodies are set aside
# first; `do`/`done` count only where they start a command; a loop closes
# only after its own `do`. It is a reader for this repository's scripts,
# not a bash parser: `eval`, loops built in strings and aliases are beyond
# it. check-waits.test.sh holds every shape it is known to read, including
# the ones that once fooled it.
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


def scan(line):
    """(code, uncommented) for one line, read the way bash reads it.

    code: quoted text blanked, comment removed - for keywords and `sleep`.
    uncommented: only the comment removed, strings kept - for the
    condition. Handles '...', "...", $'...' (whose \\' does not end it), and
    backslash escapes outside quotes and inside double quotes."""
    out, quote, i = [], None, 0
    while i < len(line):
        c = line[i]
        if quote is None:
            if c == "\\":
                out.append("  "); i += 2; continue
            if c == "$" and line[i + 1:i + 2] == "'":
                quote = "$'"; out.append("  "); i += 2; continue
            if c in "'\"":
                quote = c; out.append(" "); i += 1; continue
            if c == "#" and (i == 0 or line[i - 1].isspace() or line[i - 1] in ";&|("):
                return "".join(out), line[:i]
            out.append(c); i += 1; continue
        # inside quotes
        if c == "\\" and quote in ('"', "$'"):
            out.append("  "); i += 2; continue
        if (quote == "$'" and c == "'") or c == quote:
            quote = None
        out.append(" "); i += 1
    return "".join(out), line


def keywords(code):
    """How many `do` and `done` start a command in this code."""
    opened = closed = 0
    for segment in re.split(r"[;&|()]|&&|\|\|", code):
        words = segment.split()
        if not words:
            continue
        if words[0] == "do":
            opened += 1
        elif words[0] == "done":
            closed += 1
    return opened, closed


found = 0
for path in sys.argv[1:]:
    # Test files hold loops as fixtures. The library is checked too: its
    # deadline mechanism marks its own loops, and #155 had added a
    # round-counting wait_text to it that a library exemption hid.
    if path.endswith(".test.sh"):
        continue
    lines = open(path).read().split("\n")
    # Heredoc bodies are another language or plain data, never bash loops:
    # blank them before scanning (a Python `while` in one had been read as
    # a bash loop that never met its `do`).
    heredoc = None
    for n, raw in enumerate(lines):
        if heredoc is not None:
            if raw.strip() == heredoc:
                heredoc = None
            lines[n] = ""
            continue
        m = re.search(r"<<-?\s*['\"]?(\w+)['\"]?", scan(raw)[1])
        if m:
            heredoc = m.group(1)
    i = 0
    while i < len(lines):
        if not LOOP.match(lines[i]):
            i += 1
            continue
        start = i
        # The loop body: up to the `done` that closes this loop, counting
        # nested do/done pairs; a one-line loop closes on its own line.
        # A `do` on a later line still opens it; the loop closes only after
        # its own `do` has appeared.
        depth, seen_do, body = 0, False, []
        while i < len(lines):
            code, _ = scan(lines[i])
            opened, closed = keywords(code)
            seen_do = seen_do or opened > 0
            depth += opened - closed
            body.append(code)
            i += 1
            if seen_do and depth <= 0:
                break
        # The marker may head a comment block that runs onto more lines, as
        # long as nothing but comments stands between it and the loop.
        k = start - 1
        while k >= 0 and lines[k].strip().startswith("#") and not lines[k].strip().startswith("# not a wait:"):
            k -= 1
        marked = k >= 0 and lines[k].strip().startswith("# not a wait:")
        # A loop whose condition is the clock is a deadline, not a count.
        # Only the condition, the text before `do`, counts.
        condition = re.split(r"\bdo\b", scan(lines[start])[1], maxsplit=1)[0]
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
