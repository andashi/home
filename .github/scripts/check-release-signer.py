#!/usr/bin/env python3
"""Fails unless a release APK is signed by exactly the release key.

    check-release-signer.py CERTS.txt <expected certificate SHA-256>

CERTS.txt is `apksigner verify --print-certs` for the release APK. The
release step used to check only that it named no debug key, and an empty
CERTS.txt passes that, as would an APK signed by any other key: nothing read
is not the release key. So the one signer's SHA-256 digest must equal the
pinned one, whole, and an empty expectation matches nothing.
"""
import re
import sys

# (label, digest). A `Signer #N` label names a signer; `Vn Signer:` lines are
# one signing scheme's view of a signer, so V2 and V3 lines of one key are one
# signer, while Signer #1 and Signer #2 are two even with the same certificate.
DIGEST = re.compile(r"^(Signer #\d+|V\d+ Signer:) certificate SHA-256 digest: ([0-9a-f]+)$", re.MULTILINE)


def problems(certs: str, expected: str) -> list:
    """What is wrong with `certs` for a release signed by `expected`; empty when nothing is."""
    found = []
    expected = expected.strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        found.append(f"no release certificate digest to compare with (got {expected!r})")
    records = DIGEST.findall(certs)
    numbered = {label for label, _ in records if label.startswith("Signer #")}
    digests = sorted({digest for _, digest in records})
    if not records:
        found.append("apksigner printed no signer certificate")
    elif len(numbered) > 1 or len(digests) > 1:
        found.append(f"more than one signer: {', '.join(sorted(numbered) or digests)}")
    elif expected and digests[0] != expected:
        found.append(f"signed by {digests[0]}, not the release key {expected}")
    if re.search(r"androiddebugkey|CN=Android Debug", certs, re.IGNORECASE):
        found.append("signed with a debug key")
    return found


def main(argv: list) -> int:
    if len(argv) != 3:
        print("usage: check-release-signer.py CERTS.txt <expected certificate SHA-256>", file=sys.stderr)
        return 2
    with open(argv[1]) as f:
        certs = f.read()
    found = problems(certs, argv[2])
    for p in found:
        print(f"::error file={argv[1]}::{p}", file=sys.stderr)
    if found:
        return 1
    print(f"{argv[1]}: signed by the pinned release key")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
