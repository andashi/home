#!/usr/bin/env python3
"""Check that no warning or error log in shipped Kotlin names an app (#15).

Usage: check-log-identity.py [<repository root>]

A release build logs at info, warning and error; debug and verbose are
stripped (proguard-rules.pro, verified on the APK by check-release-logs.py).
What those lines may say: what failed, and what kind of thing it was. What
they may not say: which app. An app inventory assembled from logcat is what a
launcher on GrapheneOS must not emit.

Every Log.i/w/e/wtf call is read whole, across lines. Each value its message
prints - an interpolated expression, or an operand concatenated with `+` -
is judged by its last identifier: `${iconPack.packageName}` by
`packageName`. A name from IDENTITY is a finding, and so is printing `it` or
`this` whole, whose string could carry anything. A null comparison prints a
boolean and passes.

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
    "it", "this",
}
# libs/nextcloud is in the tree but built by nothing: absent from
# settings.gradle.kts and referenced by no module, so it cannot ship.
SKIP = ("/test/", "/androidTest/", "/build/", "libs/nextcloud")
CALL = re.compile(r"\bLog\.(i|w|e|wtf)\(")


def _tokens(src, start=0):
    """Kotlin source from `start` as (kind, text, end): "code" for one
    character outside strings, "tmpl" for a `${...}` expression inside one,
    "ident" for a `$name` inside one, and "quote" where a string closes. The
    one place string and template syntax is understood."""
    i, quote = start, None
    while i < len(src):
        if quote:
            if src[i] == "\\":
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
        if src.startswith('"""', i):
            quote = '"""'
            i += 3
            continue
        if src[i] == '"':
            quote = '"'
            i += 1
            continue
        yield "code", src[i], i + 1
        i += 1


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
    depth, i = 0, open_index
    while i < len(src):
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


def _names_an_app(value):
    value = value.strip()
    if not re.fullmatch(r"[A-Za-z_]\w*(?:\s*\??\.\s*[A-Za-z_]\w*)*", value):
        # A call or an operation - `widgetId == null` prints a boolean - is
        # judged by the strings inside it, not as a value.
        return False
    return re.search(r"\w+$", value).group(0).lower() in IDENTITY


def findings(path, src):
    found = []
    for m in CALL.finditer(src):
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
        calls += len(CALL.findall(src))
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
