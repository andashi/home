"""Tests for check-release-gate.py, run by the release-gate job in test.yml.

    python3 -m unittest discover -s .github/scripts
"""
import importlib.util
import os
import unittest

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
RELEASE = os.path.join(HERE, "..", "workflows", "release.yml")

spec = importlib.util.spec_from_file_location("gate", os.path.join(HERE, "check-release-gate.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def release_with(edit):
    """release.yml with `edit` applied to each line of every release step's run."""
    with open(RELEASE) as f:
        workflow = yaml.safe_load(f)
    for step in workflow["jobs"]["release"]["steps"]:
        if "run" in step:
            step["run"] = "\n".join(edit(line) for line in step["run"].splitlines())
    return workflow


def comment_out(needle):
    def edit(line):
        return "# " + line if needle in line else line
    return edit


class ReleaseGateTest(unittest.TestCase):
    def test_the_release_workflow_passes(self):
        self.assertEqual(gate.violations(release_with(lambda line: line)), [])

    # A commented-out command is text in `run` but does nothing: the gate must
    # not count it (#160 review). Each case disables one schema command.
    def test_a_commented_out_schema_copy_is_a_violation(self):
        found = gate.violations(release_with(comment_out("docs/configuration/launcher.schema.json")))
        self.assertIn("jobs.release does not take docs/configuration/launcher.schema.json", found)

    def test_a_commented_out_schema_hash_is_a_violation(self):
        found = gate.violations(release_with(comment_out("launcher.schema.json > SHA256SUMS")))
        self.assertIn("jobs.release does not hash launcher.schema.json in SHA256SUMS", found)

    def test_a_commented_out_schema_upload_is_a_violation(self):
        found = gate.violations(release_with(comment_out('"$SCHEMA_PATH"')))
        self.assertIn("jobs.release does not publish launcher.schema.json with the APK", found)

    # The signer check is only worth something while it runs: removing it, or
    # the digest it compares with, must turn the gate red (#204).
    def test_a_commented_out_signer_check_is_a_violation(self):
        found = gate.violations(release_with(comment_out("check-release-signer.py")))
        self.assertIn('jobs.release does not check the signer with check-release-signer.py "$RELEASE_CERT_SHA256"', found)

    def test_a_missing_release_digest_is_a_violation(self):
        workflow = release_with(lambda line: line)
        for step in workflow["jobs"]["release"]["steps"]:
            (step.get("env") or {}).pop("RELEASE_CERT_SHA256", None)
        found = gate.violations(workflow)
        self.assertIn("jobs.release pins no release certificate SHA-256 (RELEASE_CERT_SHA256, 64 hex)", found)

    # A check whose failure is swallowed is the defect #204 fixed, again.
    def test_a_masked_signer_check_is_a_violation(self):
        mask = lambda line: line + " || true" if "check-release-signer.py" in line else line
        found = gate.violations(release_with(mask))
        self.assertIn('jobs.release does not check the signer with check-release-signer.py "$RELEASE_CERT_SHA256"', found)

    def test_a_negated_signer_check_is_a_violation(self):
        negate = lambda line: line.replace("python3", "! python3") if "check-release-signer.py" in line else line
        found = gate.violations(release_with(negate))
        self.assertIn('jobs.release does not check the signer with check-release-signer.py "$RELEASE_CERT_SHA256"', found)

    def test_errexit_turned_off_before_the_signer_check_is_a_violation(self):
        off = lambda line: line.replace("set -euo pipefail", "set +e") if "set -euo pipefail" in line else line
        found = gate.violations(release_with(off))
        self.assertIn('jobs.release does not check the signer with check-release-signer.py "$RELEASE_CERT_SHA256"', found)

    # The digest counts only in the step that runs the check (#204 review).
    def test_a_digest_in_another_step_is_a_violation(self):
        workflow = release_with(lambda line: line)
        steps = workflow["jobs"]["release"]["steps"]
        digest = None
        for step in steps:
            digest = (step.get("env") or {}).pop("RELEASE_CERT_SHA256", None) or digest
        other = next(s for s in steps if "check-release-signer.py" not in str(s.get("run", "")))
        other.setdefault("env", {})["RELEASE_CERT_SHA256"] = digest
        found = gate.violations(workflow)
        self.assertIn("jobs.release pins no release certificate SHA-256 (RELEASE_CERT_SHA256, 64 hex)", found)

    def test_a_short_release_digest_is_a_violation(self):
        workflow = release_with(lambda line: line)
        for step in workflow["jobs"]["release"]["steps"]:
            env = step.get("env") or {}
            if "RELEASE_CERT_SHA256" in env:
                env["RELEASE_CERT_SHA256"] = env["RELEASE_CERT_SHA256"][:40]
        found = gate.violations(workflow)
        self.assertIn("jobs.release pins no release certificate SHA-256 (RELEASE_CERT_SHA256, 64 hex)", found)


if __name__ == "__main__":
    unittest.main()
