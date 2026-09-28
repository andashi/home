#!/usr/bin/env python3
"""Check that a built APK carries no debug or verbose logging (#15).

Usage: check-release-logs.py <dexdump> <apk>

proguard-rules.pro tells R8 that Log.d and Log.v have no side effects, so it
drops the calls. That is a request, not a result: under -dontoptimize it
dropped 100 of 102 and kept two, both in coroutine bodies. So the APK itself
is read: every invoke of android.util.Log.d or .v left in its dex is a
finding.

Fails closed. Empty dexdump output, or a dump with no Log call at all - the
launcher logs warnings, so its code was not read - is a problem, never a pass.
"""

import pathlib
import re
import subprocess
import sys
import tempfile
import zipfile

INVOKE = re.compile(r"invoke-static.*Landroid/util/Log;\.(\w+):")


def problems(dump):
    calls = [m.group(1) for m in INVOKE.finditer(dump)]
    if not calls:
        return ["the dex dump holds no android.util.Log call at all; its code was not read"]
    kept = [c for c in calls if c in ("d", "v")]
    if kept:
        return [f"{len(kept)} Log.d/Log.v call(s) left in the dex; release builds log no debug or verbose lines"]
    return []


def main(argv):
    if len(argv) != 3:
        print(__doc__.splitlines()[2], file=sys.stderr)
        return 2
    dexdump, apk = argv[1], argv[2]
    dump = []
    with tempfile.TemporaryDirectory() as work, zipfile.ZipFile(apk) as z:
        for name in sorted(n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n)):
            path = pathlib.Path(work, name)
            path.write_bytes(z.read(name))
            # dexdump prints string constants as their bytes, not all of them
            # UTF-8; the instructions the check reads are ASCII either way.
            run = subprocess.run(
                [dexdump, "-d", str(path)], capture_output=True, text=True, errors="replace",
            )
            if run.returncode != 0:
                print(f"::error::release logs: dexdump failed on {name}", file=sys.stderr)
                return 1
            dump.append(run.stdout)
    found = problems("".join(dump))
    for p in found:
        print(f"::error::release logs: {p}", file=sys.stderr)
    if not found:
        print(f"no debug or verbose log call in {apk}")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
