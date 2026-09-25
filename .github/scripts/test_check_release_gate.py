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


if __name__ == "__main__":
    unittest.main()
