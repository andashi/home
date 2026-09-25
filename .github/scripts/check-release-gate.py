#!/usr/bin/env python3
"""Asserts the two properties release.yml must keep (#132).

1. The job that builds and publishes needs the full test suite: `release`
   has `needs: tests`, and `tests` calls test.yml with the emulator suites on.
   Without it a tag ships commits no device test has seen together.
2. The release key is touched only by a tag push. Every step that reads a
   secret carries exactly the tag condition, and no job- or workflow-level
   env reads one. Otherwise a dispatch on any branch produces an APK signed
   with the production key, downloadable as a workflow artifact, and the
   signer certificate stops telling a release from a branch build.

Exits non-zero naming every violation.
"""
import re
import sys

import yaml

TAG_PUSH = "github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v')"
SECRET = re.compile(r"secrets\.")


def normalized(condition):
    text = str(condition or "").strip()
    if text.startswith("${{") and text.endswith("}}"):
        text = text[3:-2].strip()
    return text


def reads_secret(node):
    return SECRET.search(yaml.safe_dump(node)) is not None


def violations(workflow):
    found = []
    jobs = workflow.get("jobs") or {}

    tests = jobs.get("tests") or {}
    if tests.get("uses") != "./.github/workflows/test.yml":
        found.append("jobs.tests does not call ./.github/workflows/test.yml")
    if (tests.get("with") or {}).get("l2") is not True:
        found.append("jobs.tests does not pass l2: true")
    if "secrets" in tests:
        found.append("jobs.tests passes secrets to the test suite")

    release = jobs.get("release")
    if release is None:
        found.append("there is no jobs.release")
    else:
        needs = release.get("needs") or []
        if isinstance(needs, str):
            needs = [needs]
        if "tests" not in needs:
            found.append("jobs.release does not need tests")

    if reads_secret(workflow.get("env") or {}):
        found.append("the workflow-level env reads a secret")
    for name, job in jobs.items():
        if reads_secret(job.get("env") or {}):
            found.append(f"jobs.{name}.env reads a secret")
        for index, step in enumerate(job.get("steps") or []):
            label = step.get("name") or f"step {index}"
            if reads_secret(step) and normalized(step.get("if")) != TAG_PUSH:
                found.append(
                    f"jobs.{name} '{label}' reads a secret without `if: {TAG_PUSH}`"
                )
    return found


def main(path):
    with open(path) as f:
        workflow = yaml.safe_load(f)
    found = violations(workflow)
    for line in found:
        print(f"::error file={path}::{line}")
    if found:
        return 1
    print(f"{path}: release needs the full suite, and only a tag push signs")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else ".github/workflows/release.yml"))
