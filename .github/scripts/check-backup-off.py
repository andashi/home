#!/usr/bin/env python3
"""Check that a built APK keeps the launcher's data on the device (#15).

Usage: check-backup-off.py <aapt2> <apk>

Backup is off on both channels. allowBackup="false" covers cloud backup, but
since Android 12 it no longer stops a device-to-device transfer for an app
that targets 31 or later; only dataExtractionRules with a <device-transfer>
section does. So both are checked, in the APK as built: the merged manifest
is what ships, and resource shrinking renames the rules file (res/4j.xml),
so it is found through the resource table, never by name.

Everything fails closed. An attribute that is absent, a resource that does
not resolve, or aapt2 output that is empty is a problem, never a pass:
allowBackup defaults to true, and a check that reads nothing must not report
that it found nothing wrong.
"""

import re
import subprocess
import sys

DOMAINS = [
    "root", "file", "database", "sharedpref", "external",
    "device_root", "device_file", "device_database", "device_sharedpref",
]
SECTIONS = ["cloud-backup", "device-transfer"]


def _tree(text):
    """aapt2 xmltree lines as (depth, kind, body): kind is E or A."""
    out = []
    for line in text.splitlines():
        m = re.match(r"^( *)([EA]): (.*)$", line)
        if m:
            out.append((len(m.group(1)), m.group(2), m.group(3)))
    return out


def _elements(tree, name):
    """Each element called `name`, with the lines nested under it."""
    found = []
    for i, (depth, kind, body) in enumerate(tree):
        if kind == "E" and body.split(" ")[0] == name:
            inner = []
            for d, k, b in tree[i + 1:]:
                if d <= depth:
                    break
                inner.append((d, k, b))
            found.append((depth, inner))
    return found


def _attributes(depth, inner):
    """The element's own attributes: name -> value, the namespace stripped.

    [inner] is what follows the element; its own attributes end where the
    next element at its depth or above begins - not at the end of the list,
    or a sibling's attributes would overwrite its own.
    """
    attrs = {}
    for d, k, b in inner:
        if d <= depth:
            break
        if k == "A" and d == depth + 2:
            m = re.match(r'^(?:\S+:)?([A-Za-z_]+)(?:\(0x[0-9a-f]+\))?=(?:"([^"]*)"|(\S+))', b)
            if m:
                attrs[m.group(1)] = m.group(2) if m.group(2) is not None else m.group(3)
    return attrs


def _resource_path(resources, ref):
    """The file a resource id such as @0x7f140002 resolves to, or None."""
    rid = ref.lstrip("@")
    in_target = False
    for line in resources.splitlines():
        if re.match(r"^\s*resource ", line):
            in_target = bool(re.match(rf"^\s*resource {re.escape(rid)} ", line))
        elif in_target:
            m = re.search(r"\(file\) (\S+)", line)
            if m:
                return m.group(1)
    return None


def problems(manifest, resources, read_file):
    """Everything wrong with the APK's backup settings; empty when it is off.

    manifest: `aapt2 dump xmltree --file AndroidManifest.xml`
    resources: `aapt2 dump resources`
    read_file: path in the APK -> its `aapt2 dump xmltree`, or None
    """
    apps = _elements(_tree(manifest), "application")
    if len(apps) != 1:
        return [f"expected one <application> in the manifest, found {len(apps)}"]
    attrs = _attributes(*apps[0])

    found = []
    if attrs.get("allowBackup") != "false":
        found.append(f"allowBackup is {attrs.get('allowBackup', 'absent (defaults to true)')}, must be false")

    ref = attrs.get("dataExtractionRules")
    if ref is None:
        return found + ["dataExtractionRules is absent: nothing stops a device-to-device transfer"]
    path = _resource_path(resources, ref)
    if path is None:
        return found + [f"dataExtractionRules {ref} does not resolve to a file in the resource table"]
    text = read_file(path)
    if not text:
        return found + [f"dataExtractionRules file {path} is missing or empty"]

    tree = _tree(text)
    if _elements(tree, "include"):
        found.append("the extraction rules include something; nothing may be included")
    for name in SECTIONS:
        sections = _elements(tree, name)
        if len(sections) != 1:
            found.append(f"<{name}>: expected one section, found {len(sections)}")
            continue
        _, inner = sections[0]
        excludes = [_attributes(*e) for e in _elements(inner, "exclude")]
        excluded = {a.get("domain") for a in excludes if a.get("path") == "."}
        missing = [d for d in DOMAINS if d not in excluded]
        if missing:
            found.append(f"<{name}> does not exclude the whole of: {', '.join(missing)}")
    return found


def main(argv):
    if len(argv) != 3:
        print(__doc__.splitlines()[2], file=sys.stderr)
        return 2
    aapt2, apk = argv[1], argv[2]

    def dump(*args):
        run = subprocess.run([aapt2, "dump", *args, apk], capture_output=True, text=True)
        return run.stdout if run.returncode == 0 else ""

    found = problems(
        dump("xmltree", "--file", "AndroidManifest.xml"),
        dump("resources"),
        lambda path: dump("xmltree", "--file", path),
    )
    for p in found:
        print(f"::error::backup: {p}", file=sys.stderr)
    if not found:
        print(f"backup off: allowBackup=false, every domain excluded from cloud backup and device transfer ({apk})")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
