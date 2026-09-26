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
# first, with the quoting context carried across lines and through $( ... );
# `do`/`done` count only where they start a command; a loop closes only
# after its own `do`. It is a reader for this repository's scripts, not a
# bash parser. What it cannot read fails loudly rather than passing: eval,
# an alias, code handed to another shell with -c, and a loop it never saw
# close. check-waits.test.sh holds every shape it is known to read,
# including the ones that once fooled it.
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


def scan(line, stack=None):
    """(code, uncommented, stack) for one line, read the way bash reads it.

    code: quoted text blanked, comment removed - for keywords and `sleep`.
    uncommented: only the comment removed, strings kept - for the
    condition. The stack carries the quoting context across lines: '...',
    "...", $'...' (whose \\' does not end it), and $( ... ), which opens a
    fresh context even inside double quotes, as in "$(tr -d '"')". Backslash
    escapes apply outside quotes and inside double quotes."""
    stack = list(stack or [])
    out, i = [], 0
    while i < len(line):
        c = line[i]
        top = stack[-1] if stack else None
        if top in ("'", "$'"):
            if c == "\\" and top == "$'":
                out.append("  "); i += 2; continue
            if c == "'":
                stack.pop()
            out.append(" "); i += 1; continue
        if top == '"':
            if c == "\\":
                out.append("  "); i += 2; continue
            if c == "$" and line[i + 1:i + 2] == "(":
                stack.append("("); out.append("  "); i += 2; continue
            if c == '"':
                stack.pop()
            out.append(" "); i += 1; continue
        # unquoted, at top level or inside $( ... )
        if c == "\\":
            out.append("  "); i += 2; continue
        if c == "$" and line[i + 1:i + 2] == "'":
            stack.append("$'"); out.append("  "); i += 2; continue
        if c == "$" and line[i + 1:i + 2] == "(":
            stack.append("("); out.append("$("); i += 2; continue
        if c in "'\"":
            stack.append(c); out.append(" "); i += 1; continue
        if top == "(" and c == "(":
            stack.append("("); out.append(c); i += 1; continue
        if top == "(" and c == ")":
            stack.pop(); out.append(c); i += 1; continue
        if c == "#" and (i == 0 or line[i - 1].isspace() or line[i - 1] in ";&|("):
            return "".join(out), line[:i], stack
        out.append(c); i += 1
    return "".join(out), line, stack


def scan_file(lines):
    """scan() over a whole file, so a string that spans lines (an awk or
    Python program in single quotes) stays a string on every line of it."""
    views, stack = [], []
    for raw in lines:
        code, uncommented, stack = scan(raw, stack)
        views.append((code, uncommented))
    return views


UNREADABLE = ("eval", "alias")
SEC = r'"?\$SECONDS"?'
OPERAND = r'"?\$?\{?\w+\}?"?'
DEADLINE = re.compile(
    r"^\s*while\s+\[\s+(" + SEC + r"\s+-(lt|le)\s+" + OPERAND
    + r"|" + OPERAND + r"\s+-(gt|ge)\s+" + SEC + r")\s+\]\s*;?\s*$")


def unreadable(code):
    """The construct in this code the guard cannot look inside, or None:
    eval, an alias, or code handed to another shell with -c."""
    for segment in re.split(r"[;&|()]|&&|\|\|", code):
        words = segment.split()
        if not words:
            continue
        if words[0] in UNREADABLE:
            return words[0]
        if words[0] in ("bash", "sh"):
            # -c may follow other options (bash -e -c ...), or share a word
            # with them (bash -ec ...).
            for word in words[1:]:
                if not word.startswith("-"):
                    break
                if not word.startswith("--") and "c" in word[1:]:
                    return words[0] + " -c"
    return None


def starts_loop(code):
    """True when a for/while/until starts a command anywhere on the line:
    at its start, after `;`/`&&`/`||`, or after a pipe (`... | while read`,
    which this repository uses)."""
    if LOOP.match(code):
        return True
    for segment in re.split(r"[;&|]|&&|\|\|", code)[1:]:
        words = segment.lstrip("!({ ").split()
        if words and words[0] in ("for", "while", "until"):
            return True
    return False


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
        # Only an unquoted << opens a heredoc (not '<<END' in a string, not
        # the here-string <<<); its delimiter may itself be quoted.
        opener = re.search(r"(?<!<)<<(?!<)", scan(raw)[0])
        if opener:
            m = re.match(r"<<-?\s*['\"]?([^\s'\";&|<>()]+)", raw[opener.start():])
            if m:
                heredoc, heredoc_line = m.group(1), n
    if heredoc is not None:
        # Never closed: the guard misread the delimiter or the file is cut
        # off, and everything after the opener went unread.
        print(f"::error file=e2e/{path},line={heredoc_line + 1}::a heredoc the guard never saw close (delimiter {heredoc!r}), so what follows it went unread")
        found = 1
    views = scan_file(lines)
    # What the guard cannot read fails loudly, never silently: it cannot see
    # a wait inside eval, an alias or another shell's -c string.
    for n, raw in enumerate(lines):
        what = unreadable(views[n][0])
        if what:
            print(f"::error file=e2e/{path},line={n + 1}::cannot read {what!r}, so a wait there would pass unseen; write it out plainly: {raw.strip()}")
            found = 1
    i = 0
    while i < len(lines):
        if not starts_loop(views[i][0]):
            i += 1
            continue
        start = i
        # The loop body: up to the `done` that closes this loop, counting
        # nested do/done pairs; a one-line loop closes on its own line.
        # A `do` on a later line still opens it; the loop closes only after
        # its own `do` has appeared.
        depth, seen_do, body = 0, False, []
        while i < len(lines):
            code = views[i][0]
            opened, closed = keywords(code)
            seen_do = seen_do or opened > 0
            depth += opened - closed
            body.append(code)
            i += 1
            if seen_do and depth <= 0:
                break
        else:
            # The file ended inside the loop: one the guard did not
            # understand, not one it may pass.
            print(f"::error file=e2e/{path},line={start + 1}::a loop the guard never saw close, so it cannot tell whether it waits: {lines[start].strip()}")
            found = 1
            i = start + 1
            continue
        # The marker may head a comment block that runs onto more lines, as
        # long as nothing but comments stands between it and the loop.
        k = start - 1
        while k >= 0 and lines[k].strip().startswith("#") and not lines[k].strip().startswith("# not a wait:"):
            k -= 1
        marked = k >= 0 and lines[k].strip().startswith("# not a wait:")
        # A loop whose whole condition compares the clock with a deadline is
        # a deadline, not a count: [ "$SECONDS" -lt X ] or [ X -gt "$SECONDS" ],
        # and nothing else. A condition that merely mentions $SECONDS
        # while it counts attempts is not one.
        condition = re.split(r"\bdo\b", views[start][1], maxsplit=1)[0]
        marked = marked or bool(DEADLINE.match(condition))
        if not marked and any(re.search(r"\bsleep\b", l) for l in body):
            print(f"::error file=e2e/{path},line={start + 1}::counts rounds instead of waiting against a deadline: {lines[start].strip()}")
            found = 1
        # Look inside the body too: a marked repetition loop can still hold a
        # wait of its own.
        i = start + 1
sys.exit(found)
PY
echo "no e2e script waits by counting rounds"
