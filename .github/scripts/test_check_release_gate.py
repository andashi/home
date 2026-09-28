"""Tests for check-release-gate.py, run by the release-gate job in test.yml.

    python3 -m unittest discover -s .github/scripts
"""
import importlib.util
import os
import unittest

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
RELEASE = os.path.join(HERE, "..", "workflows", "release.yml")
NO_SIGNER_CHECK = 'jobs.release does not check the signer with check-release-signer.py "$RELEASE_CERT_SHA256"'

spec = importlib.util.spec_from_file_location("gate", os.path.join(HERE, "check-release-gate.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def every_job_with(edit, jobs=None):
    """release.yml with `edit` applied to each line of every step's run, in
    the named jobs, or in all of them."""
    with open(RELEASE) as f:
        workflow = yaml.safe_load(f)
    for name, job in workflow["jobs"].items():
        if jobs is not None and name not in jobs:
            continue
        for step in job.get("steps") or []:
            if "run" in step:
                step["run"] = "\n".join(edit(line) for line in step["run"].splitlines())
    return workflow


def release_with(edit):
    """release.yml with `edit` applied to each line of every release step's run."""
    return every_job_with(edit, jobs=("release",))


def comment_out(needle):
    def edit(line):
        return "# " + line if needle in line else line
    return edit


# From the gate, so a check added there is tested here without a second list.
APK_CHECKS = list(gate.APK_CHECKS)


def unchecked(job, script):
    return f"jobs.{job} builds the release APK without running {script} on it"


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

    # A line that only names the checker runs nothing (#204 review).
    def test_a_printed_signer_check_is_a_violation(self):
        printed = lambda line: (
            """          python3 -c 'print(\"\"\"check-release-signer.py CERTS.txt "$RELEASE_CERT_SHA256"\"\"\")'"""
            if "check-release-signer.py" in line else line)
        found = gate.violations(release_with(printed))
        self.assertIn('jobs.release does not check the signer with check-release-signer.py "$RELEASE_CERT_SHA256"', found)

    def test_an_echoed_signer_check_is_a_violation(self):
        echoed = lambda line: line.replace("python3", "echo") if "check-release-signer.py" in line else line
        found = gate.violations(release_with(echoed))
        self.assertIn('jobs.release does not check the signer with check-release-signer.py "$RELEASE_CERT_SHA256"', found)

    # The gate reads the two lines as written, not as a shell would re-parse
    # them (#204 review): another checker with the same name, a digest Bash
    # does not expand, a check inside a here-document, a CERTS.txt that does
    # not come from the APK.
    def test_a_checker_at_another_path_is_a_violation(self):
        moved = lambda line: line.replace('"$GITHUB_WORKSPACE/.github/scripts/check-release-signer.py"', "/tmp/check-release-signer.py") if "check-release-signer.py" in line else line
        found = gate.violations(release_with(moved))
        self.assertIn(NO_SIGNER_CHECK, found)

    def test_a_single_quoted_digest_is_a_violation(self):
        literal = lambda line: line.replace('"$RELEASE_CERT_SHA256"', "'$RELEASE_CERT_SHA256'")
        found = gate.violations(release_with(literal))
        self.assertIn(NO_SIGNER_CHECK, found)

    def test_a_signer_check_inside_a_here_document_is_a_violation(self):
        def heredoc(line):
            if "check-release-signer.py" in line:
                return "          cat <<'EOF'\n" + line + "\n          EOF"
            return line
        found = gate.violations(release_with(heredoc))
        self.assertIn(NO_SIGNER_CHECK, found)

    def test_certs_not_printed_from_the_apk_is_a_violation(self):
        forged = lambda line: "          printf 'fixture' > CERTS.txt" if "tee CERTS.txt" in line else line
        found = gate.violations(release_with(forged))
        self.assertIn(NO_SIGNER_CHECK, found)

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


class ApkChecksGateTest(unittest.TestCase):
    """Property 5 (#15): every job that builds the release APK runs each APK
    check on it, after the build, where its failure fails the job."""

    def test_both_jobs_that_build_the_apk_run_both_checks(self):
        found = gate.violations(every_job_with(lambda line: line))
        for script in APK_CHECKS:
            self.assertNotIn(unchecked("dry-run", script), found)
            self.assertNotIn(unchecked("release", script), found)

    def test_a_commented_out_check_is_a_violation_in_each_job(self):
        for script in APK_CHECKS:
            with self.subTest(script=script):
                found = gate.violations(every_job_with(comment_out(script)))
                self.assertIn(unchecked("dry-run", script), found)
                self.assertIn(unchecked("release", script), found)

    def test_a_masked_check_is_a_violation(self):
        for script in APK_CHECKS:
            with self.subTest(script=script):
                mask = lambda line, s=script: line + " || true" if s in line else line
                self.assertIn(unchecked("release", script), gate.violations(every_job_with(mask)))

    def test_errexit_turned_off_before_the_checks_is_a_violation(self):
        off = lambda line: line.replace("set -euo pipefail", "set +e") if "set -euo pipefail" in line else line
        found = gate.violations(every_job_with(off))
        for script in APK_CHECKS:
            self.assertIn(unchecked("dry-run", script), found)

    # A here-document's body is data, not commands: a check inside one runs
    # nothing. Property 4 knew this; property 5 did not (review of the sweep).
    def test_a_check_inside_a_here_document_is_a_violation(self):
        def heredoc(line):
            if "check-backup-off.py" in line:
                return "          cat <<'EOF'\n" + line + "\n          EOF"
            return line
        self.assertIn(unchecked("release", "check-backup-off.py"), gate.violations(every_job_with(heredoc)))

    # Review round 2 on #239: the APK that ships is the one built last, and a
    # step that is skipped or whose failure is tolerated checks nothing.
    def test_a_rebuild_after_the_checks_is_a_violation(self):
        with open(RELEASE) as f:
            workflow = yaml.safe_load(f)
        steps = workflow["jobs"]["dry-run"]["steps"]
        build = next(s for s in steps if "assembleDefaultRelease" in s.get("run", ""))
        steps.append(dict(build))
        self.assertIn(unchecked("dry-run", "check-backup-off.py"), gate.violations(workflow))

    def test_a_skipped_or_tolerated_check_step_is_a_violation(self):
        for flag in ({"if": "${{ false }}"}, {"continue-on-error": True}):
            with self.subTest(flag=flag):
                with open(RELEASE) as f:
                    workflow = yaml.safe_load(f)
                step = next(s for s in workflow["jobs"]["release"]["steps"] if "check-backup-off.py" in s.get("run", ""))
                step.update(flag)
                self.assertIn(unchecked("release", "check-backup-off.py"), gate.violations(workflow))

    # Review round 3 on #239: a step that exits before its checks, or turns
    # errexit off in its long form, runs checks whose failure fails nothing.
    def test_an_exit_before_the_checks_is_a_violation(self):
        def exit_first(line):
            if "check-backup-off.py" in line:
                return "          exit 0\n" + line
            return line
        self.assertIn(unchecked("release", "check-backup-off.py"), gate.violations(every_job_with(exit_first)))

    def test_errexit_turned_off_by_name_before_the_checks_is_a_violation(self):
        off = lambda line: line.replace("set -euo pipefail", "set +o errexit") if "set -euo pipefail" in line else line
        found = gate.violations(every_job_with(off))
        for script in APK_CHECKS:
            self.assertIn(unchecked("dry-run", script), found)

    def test_a_check_before_the_build_is_a_violation(self):
        with open(RELEASE) as f:
            workflow = yaml.safe_load(f)
        steps = workflow["jobs"]["dry-run"]["steps"]
        check = next(s for s in steps if "check-backup-off.py" in s.get("run", ""))
        steps.remove(check)
        build = next(i for i, s in enumerate(steps) if "assembleDefaultRelease" in s.get("run", ""))
        steps.insert(build, check)
        self.assertIn(unchecked("dry-run", "check-backup-off.py"), gate.violations(workflow))


if __name__ == "__main__":
    unittest.main()
