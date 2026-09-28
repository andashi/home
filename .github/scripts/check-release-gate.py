#!/usr/bin/env python3
"""Asserts the five properties release.yml must keep (#132, #3, #204, #15).

1. Nothing builds before the full test suite is green: `tests` calls
   test.yml, and every other job needs it. Without that a tag ships commits
   no device test has seen together.
2. The release key is touched only by a tag push: a job that reads a secret
   carries exactly the tag condition as its own `if` and declares
   `environment: release`, and no workflow-level env reads one. Otherwise a
   dispatch on any branch produces an APK signed with the production key,
   and the signer certificate stops telling a release from a branch build.
   The environment is the second, independent mechanism: once its secrets
   are restricted to `v*` tags in the repository settings, GitHub withholds
   them from any other ref before a line of this YAML runs.
3. A release publishes the JSON Schema of launcher.json it reads (ADR 0002)
   next to the APK, hashed in SHA256SUMS: the provisioning host validates a
   zone's file against the version it deploys. The release job runs only on
   a tag, so without this check a lost upload would show only when someone
   looked for the asset.
4. The APK is signed by exactly the pinned release key: the release job
   prints the APK's certificates and runs check-release-signer.py on them
   against RELEASE_CERT_SHA256, a full SHA-256 in the step's env. The check it replaced could never fail (#204), and a removed
   check would read the same as a passing one.
5. What ships is checked, not only what merged: every job that builds the
   release APK runs check-backup-off.py (nothing of the launcher leaves the
   device) and check-release-logs.py (no debug or verbose logging) on it,
   after the build, where their failure fails the job (#15). test.yml checks
   every pull request's source and merged manifest; this keeps the checks
   on the artefact a release publishes.

What this defends against, and what it does not: accidental weakening - a
refactor, a copied line, a `set +e` added while debugging, a `!` nobody knew
could not fail (the defect behind #204). It cannot defend against an author
who means to defeat it, because whoever can edit release.yml can edit this
file in the same commit. Against that, the controls are review, branch
protection, and the pinned digest, which would have to change visibly in the
same diff. So the checks match the lines release.yml writes, verbatim; do not
harden them into a shell parser to catch deliberate evasion.

Exits non-zero naming every violation.
"""
import re
import sys

import yaml

TAG_PUSH = "github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v')"


def normalized(condition):
    text = str(condition or "").strip()
    if text.startswith("${{") and text.endswith("}}"):
        text = text[3:-2].strip()
    return text


def reads_secret(node):
    return "secrets." in yaml.safe_dump(node)


def executable_lines(run):
    """The shell lines of a `run` that execute: a commented-out command is
    still text in the step but ships nothing (#160 review)."""
    return [line for line in str(run or "").splitlines() if not line.lstrip().startswith("#")]


def violations(workflow):
    found = []
    jobs = workflow.get("jobs") or {}

    tests = jobs.get("tests") or {}
    if tests.get("uses") != "./.github/workflows/test.yml":
        found.append("jobs.tests does not call ./.github/workflows/test.yml")
    if reads_secret(tests):
        found.append("jobs.tests passes secrets to the test suite")
    if "release" not in jobs:
        found.append("there is no jobs.release")

    if reads_secret(workflow.get("env") or {}):
        found.append("the workflow-level env reads a secret")
    for name, job in jobs.items():
        if name == "tests":
            continue
        needs = job.get("needs") or []
        if "tests" not in ([needs] if isinstance(needs, str) else needs):
            found.append(f"jobs.{name} does not need tests")
        if reads_secret(job) and normalized(job.get("if")) != TAG_PUSH:
            found.append(f"jobs.{name} reads a secret without `if: {TAG_PUSH}`")
        environment = job.get("environment")
        if isinstance(environment, dict):
            environment = environment.get("name")
        if reads_secret(job) and environment != "release":
            found.append(f"jobs.{name} reads a secret outside `environment: release`")

    release_steps = "\n".join(
        line
        for step in (jobs.get("release") or {}).get("steps") or []
        for line in executable_lines(step.get("run", ""))
    )
    if "docs/configuration/launcher.schema.json" not in release_steps:
        found.append("jobs.release does not take docs/configuration/launcher.schema.json")
    if "launcher.schema.json > SHA256SUMS" not in release_steps:
        found.append("jobs.release does not hash launcher.schema.json in SHA256SUMS")
    publish = [line for line in release_steps.splitlines() if '"$APK_PATH"' in line]
    if not any('"$SCHEMA_PATH"' in line for line in publish):
        found.append("jobs.release does not publish launcher.schema.json with the APK")

    signer_steps = [step for step in (jobs.get("release") or {}).get("steps") or [] if checks_signer(step)]
    if not signer_steps:
        found.append('jobs.release does not check the signer with check-release-signer.py "$RELEASE_CERT_SHA256"')
    # Only the signer step's own env reaches the check.
    digests = [str((step.get("env") or {}).get("RELEASE_CERT_SHA256", "")) for step in signer_steps]
    if not any(re.fullmatch(r"[0-9a-f]{64}", d) for d in digests):
        found.append("jobs.release pins no release certificate SHA-256 (RELEASE_CERT_SHA256, 64 hex)")
    for name, job in jobs.items():
        steps = job.get("steps") or []
        builds = [i for i, step in enumerate(steps)
                  if any("assembleDefaultRelease" in line for line in executable_lines(step.get("run", "")))]
        if not builds:
            continue
        after = steps[builds[0] + 1:]
        for script, line in APK_CHECKS.items():
            if not any(runs_unmasked(step, line) for step in after):
                found.append(f"jobs.{name} builds the release APK without running {script} on it")
    return found


# Property 5's lines, as both jobs write them; matched verbatim, like property 4.
APK_CHECKS = {
    "check-backup-off.py":
        'python3 "$GITHUB_WORKSPACE/.github/scripts/check-backup-off.py" "$bt/aapt2" "$apk"',
    "check-release-logs.py":
        'python3 "$GITHUB_WORKSPACE/.github/scripts/check-release-logs.py" "$bt/dexdump" "$apk"',
}


def runs_unmasked(step, command):
    """Whether `step` runs `command` so that its failure fails the step: the
    line exactly as written, with no `set +e` before it."""
    lines = [line.strip() for line in executable_lines(step.get("run", ""))]
    if command not in lines:
        return False
    before = lines[:lines.index(command)]
    return not any(re.search(r"\bset\s+\+e", line) for line in before)


# The two lines that make property 4, as release.yml writes them. Matched
# verbatim rather than re-parsed: a shell-word parse forgets the quoting Bash
# acts on and cannot tell a checker at another path, or a certificate list
# that is not the APK's, from the real one (#204 review).
PRINTS_CERTS = '"$bt/apksigner" verify --print-certs "$apk" | tee CERTS.txt'
RUNS_CHECK = 'python3 "$GITHUB_WORKSPACE/.github/scripts/check-release-signer.py" CERTS.txt "$RELEASE_CERT_SHA256"'


def checks_signer(step):
    """Whether `step` prints the APK's certificates and then runs the signer
    check so that its failure fails the step: both lines exactly as written,
    in that order, with nothing around the check (`!`, `||`, `if` can swallow
    its failure, the defect #204 fixed), no `set +e` and no here-document
    opened before it (its body is data, not commands)."""
    lines = [line.strip() for line in executable_lines(step.get("run", ""))]
    if RUNS_CHECK not in lines:
        return False
    before = lines[:lines.index(RUNS_CHECK)]
    return (PRINTS_CERTS in before
            and not any(re.search(r"\bset\s+\+e", line) for line in before)
            and not any(re.search(r"(?<!<)<<(?!<)", line) for line in before))


def main(path):
    with open(path) as f:
        workflow = yaml.safe_load(f)
    found = violations(workflow)
    for line in found:
        print(f"::error file={path}::{line}")
    if found:
        return 1
    print(f"{path}: every job needs the full suite, only a tag push signs, the schema ships, the signer is pinned, "
          "and every release APK is checked")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else ".github/workflows/release.yml"))
