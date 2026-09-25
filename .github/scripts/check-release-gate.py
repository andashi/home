#!/usr/bin/env python3
"""Asserts the three properties release.yml must keep (#132, #3).

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

Exits non-zero naming every violation.
"""
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

    release_steps = "\n".join(str(step.get("run", "")) for step in (jobs.get("release") or {}).get("steps") or [])
    if "docs/configuration/launcher.schema.json" not in release_steps:
        found.append("jobs.release does not take docs/configuration/launcher.schema.json")
    if "launcher.schema.json > SHA256SUMS" not in release_steps:
        found.append("jobs.release does not hash launcher.schema.json in SHA256SUMS")
    publish = [line for line in release_steps.splitlines() if '"$APK_PATH"' in line]
    if not any('"$SCHEMA_PATH"' in line for line in publish):
        found.append("jobs.release does not publish launcher.schema.json with the APK")
    return found


def main(path):
    with open(path) as f:
        workflow = yaml.safe_load(f)
    found = violations(workflow)
    for line in found:
        print(f"::error file={path}::{line}")
    if found:
        return 1
    print(f"{path}: every job needs the full suite, only a tag push signs, and the schema ships")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else ".github/workflows/release.yml"))
