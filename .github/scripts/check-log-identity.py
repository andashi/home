#!/usr/bin/env python3
"""Check that no warning or error log in shipped Kotlin names an app (#15).

Usage: check-log-identity.py [<repository root>]

A release build logs at info, warning and error; debug and verbose are
stripped (proguard-rules.pro, verified on the APK by check-release-logs.py).
What those lines may say: what failed, and what kind of thing it was. What
they may not say: which app. An app inventory assembled from logcat is what a
launcher on GrapheneOS must not emit.

Every Log.i/w/e/wtf call in code - not in a comment or a string - is read
whole, across lines. In each value its message prints - an interpolated
expression, or an operand concatenated with `+` - every name chain is judged
by what it prints: its last segment, or, when it is a call, anything its
receiver chain names. So `${iconPack.packageName}`, `${intent ?: "none"}`
and `${intent.toUri(0)}` are findings, and `${key.blurPx}` is not. Printing `it` or
`this` whole is a finding too, whose string could carry anything; `it.code`
is not. A chain compared with null prints a boolean and passes.

This is a tripwire for the forms that leaked, not a proof: a value reached
through an innocent name passes it. Exceptions attached to a call are not
judged - that channel, like CrashReporter.logException, carries the stack
traces a crash is diagnosed by, and trading it away needs its own argument.
"""

import pathlib
import re
import sys

IDENTITY = {
    "package", "packagename", "pkg", "component", "componentname", "intent",
    "serviceintent", "serialized", "key", "label", "provider", "providerinfo",
    "widgetproviderinfo", "iconpack", "widget", "uri", "url", "activity",
    "activityname", "descriptor", "interfacedescriptor", "diagnostics",
    # A config diagnostic's path can be one of the file's own keys.
    "path",
    "it", "this",
}
# libs/nextcloud is in the tree but built by nothing: absent from
# settings.gradle.kts and referenced by no module, so it cannot ship.
SKIP = ("/test/", "/androidTest/", "/build/", "libs/nextcloud")
CALL = re.compile(r"\bLog\.(i|w|e|wtf)\s*\(")


def _tokens(src, start=0):
    """Kotlin source from `start` as (kind, text, end): "code" for one
    character outside strings, "tmpl" for a `${...}` expression inside one,
    "ident" for a `$name` inside one, and "quote" where a string closes.
    Comments yield nothing. The one place string, template and comment
    syntax is understood."""
    i, quote = start, None
    while i < len(src):
        if quote:
            if quote == '"' and src[i] == "\\":  # a raw string has no escapes
                i += 2
                continue
            if src.startswith(quote, i):
                i += len(quote)
                quote = None
                yield "quote", "", i
                continue
            if src.startswith("${", i):
                end = _brace_end(src, i + 1)
                yield "tmpl", src[i + 2:end], end + 1
                i = end + 1
                continue
            m = _NAME.match(src, i)
            if m:
                yield "ident", m.group(1), m.end()
                i = m.end()
                continue
            i += 1
            continue
        if src.startswith("//", i):
            end = src.find("\n", i)
            i = len(src) if end < 0 else end
            continue
        if src.startswith("/*", i):
            # Kotlin block comments nest.
            depth, i = 1, i + 2
            while i < len(src) and depth:
                if src.startswith("/*", i):
                    depth, i = depth + 1, i + 2
                elif src.startswith("*/", i):
                    depth, i = depth - 1, i + 2
                else:
                    i += 1
            continue
        if src.startswith('"""', i):
            quote = '"""'
            i += 3
            continue
        if src[i] == '"':
            quote = '"'
            i += 1
            continue
        if src[i] == "'":
            i = _char_end(src, i)
            continue
        yield "code", src[i], i + 1
        i += 1


def _char_end(src, i):
    """The index after the character literal opening at `i`: `'"'` is no
    string, and `'\\''` no end of one."""
    j = i + 1
    j += 2 if src.startswith("\\", j) else 1
    while j < len(src) and src[j] != "'":  # '\\u0041'
        j += 1
    return j + 1


def _string_end(src, i, quote):
    """The index after the string opening at `i` with `quote`, templates and
    their own strings included."""
    i += len(quote)
    while i < len(src):
        if quote == '"' and src[i] == "\\":
            i += 2
            continue
        if src.startswith(quote, i):
            return i + len(quote)
        if src.startswith("${", i):
            i = _brace_end(src, i + 1) + 1
            continue
        i += 1
    return len(src)


def _code_only(src):
    """`src` with every comment and string blanked, lengths and line breaks
    kept: a call is looked for in code, never in a comment or a string."""
    out, pos = [], 0
    for kind, text, end in _tokens(src):
        if kind != "code":
            continue
        start = end - 1
        out.append("".join("\n" if c == "\n" else " " for c in src[pos:start]))
        out.append(text)
        pos = end
    out.append("".join("\n" if c == "\n" else " " for c in src[pos:]))
    return "".join(out)


_NAME = re.compile(r"\$([A-Za-z_]\w*)")


def _call(src, start):
    """The text of the call starting at `start`, to its closing parenthesis."""
    depth = 0
    for kind, text, end in _tokens(src, start):
        if kind != "code":
            continue
        if text == "(":
            depth += 1
        elif text == ")":
            depth -= 1
            if depth == 0:
                return src[start:end]
    return src[start:]


def _brace_end(src, open_index):
    """The index of the brace closing the one at `open_index`; a brace inside
    a string or a character literal there does not count."""
    depth, i = 0, open_index
    while i < len(src):
        if src.startswith('"""', i):
            i = _string_end(src, i, '"""')
            continue
        if src[i] == '"':
            i = _string_end(src, i, '"')
            continue
        if src[i] == "'":
            i = _char_end(src, i)
            continue
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return len(src) - 1


def _printed(call):
    """Each value the call's message prints, as its source text."""
    values, rest = [], []
    for kind, text, _ in _tokens(call):
        if kind == "code":
            rest.append(text)
        elif kind == "quote":
            rest.append('""')
        elif kind == "ident":
            values.append(text)
        else:
            values.append(text)
            # Strings inside the expression print too.
            values.extend(_printed("(" + text + ")"))
    code = "".join(rest)
    # Operands concatenated to the message: `"..." + intent`.
    # A chain is taken whole or not at all: backtracking into
    # `check.diagnostics.joinToString { ... }` would judge `check.diagnostics`.
    for m in re.finditer(r"\+\s*([A-Za-z_][\w.]*)(?![\w.]|\s*[({])|(?<![\w.])([A-Za-z_][\w.]*)\s*\+", code):
        values.append(m.group(1) or m.group(2))
    return values


_CHAIN = r"[A-Za-z_]\w*(?:\s*\??\.\s*[A-Za-z_]\w*)*"
_WHOLE = {"it", "this"}


def _names_an_app(value):
    # Strings inside the expression are judged on their own (_printed).
    code = "".join(text for kind, text, _ in _tokens(value) if kind == "code").strip()
    # A chain compared with null prints a boolean, not the chain.
    code = re.sub(rf"{_CHAIN}\s*[!=]=\s*null\b|\bnull\s*[!=]=\s*{_CHAIN}", " ", code)
    for m in re.finditer(_CHAIN, code):
        segments = [s.lower() for s in re.split(r"\s*\??\.\s*", m.group(0))]
        # What prints is the last segment - `app.packageName`, `intent` - or,
        # when the chain is a call, something derived from its receiver:
        # `intent.toUri(0)`. A property of another object, `key.blurPx`, is not.
        called = code[m.end():].lstrip().startswith("(")
        if called and segments[-1] == "tostring":
            # `this.toString()` prints its receiver.
            called, segments = False, segments[:-1]
        if not called and len(segments) == 1 and segments[0] in _WHOLE:
            return True  # `it` or `this` whole, `${it ?: "unknown"}` included
        judged = segments if called else segments[-1:]
        if any(s in IDENTITY - _WHOLE for s in judged):
            return True
    return False


def findings(path, src):
    found = []
    for m in CALL.finditer(_code_only(src)):
        call = _call(src, m.start())
        for value in _printed(call):
            if _names_an_app(value):
                line = src.count("\n", 0, m.start()) + 1
                found.append(f"{path}:{line}: Log.{m.group(1)} prints `{value.strip()}`")
    return found


def scan(root):
    found, calls = [], 0
    for f in sorted(root.rglob("*.kt")):
        rel = str(f.relative_to(root))
        if any(s in "/" + rel for s in SKIP):
            continue
        src = f.read_text()
        calls += len(CALL.findall(_code_only(src)))
        found += findings(rel, src)
    if calls == 0:
        sys.exit(f"::error::no Log.i/w/e call found under {root}: the scan read nothing")
    return found


def main(argv):
    root = pathlib.Path(argv[1] if len(argv) > 1 else ".")
    found = scan(root)
    for f in found:
        print(f"::error::a release log names an app: {f}", file=sys.stderr)
    if not found:
        print(f"no Log.i/w/e call in shipped Kotlin under {root} prints an app's identity")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
