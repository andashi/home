"""Tests for check-backup-off.py (#15).

The fixtures are aapt2 output as it came from the release APK built for this
change (build-tools 37.0.0), trimmed to what the check reads. The resource
path is the shortened one resource shrinking produces (res/4j.xml), which is
why the check resolves the rules through the resource table rather than by
name.
"""

import importlib.util
import pathlib
import unittest

_spec = importlib.util.spec_from_file_location(
    "check_backup_off", pathlib.Path(__file__).with_name("check-backup-off.py")
)
check_backup_off = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(check_backup_off)
problems = check_backup_off.problems

ANDROID = "http://schemas.android.com/apk/res/android"

MANIFEST = f"""N: android={ANDROID} (line=2)
  E: manifest (line=2)
    A: {ANDROID}:versionCode(0x0101021b)=1
      E: uses-permission (line=15)
        A: {ANDROID}:name(0x01010003)="android.permission.VIBRATE" (Raw: "android.permission.VIBRATE")
      E: application (line=43)
        A: {ANDROID}:theme(0x01010000)=@0x7f1200ad
        A: {ANDROID}:name(0x01010003)="de.mm20.launcher2.LauncherApplication" (Raw: "de.mm20.launcher2.LauncherApplication")
        A: {ANDROID}:allowBackup(0x01010280)=false
        A: {ANDROID}:supportsRtl(0x010103af)=true
        A: {ANDROID}:dataExtractionRules(0x0101063e)=@0x7f140002
        A: {ANDROID}:crossProfile(0x0101060e)=true
        E: activity (line=60)
          A: {ANDROID}:name(0x01010003)="x" (Raw: "x")
"""

RESOURCES = """    resource 0x7f140001 xml/backup_rules_other
      () (file) res/hs.xml type=XML
    resource 0x7f140002 xml/data_extraction_rules
      () (file) res/4j.xml type=XML
    resource 0x7f140004 xml/provider_paths
      () (file) res/Zq.xml type=XML
"""

DOMAINS = [
    "root", "file", "database", "sharedpref", "external",
    "device_root", "device_file", "device_database", "device_sharedpref",
]


def section(name, domains=DOMAINS, path=".", extra=""):
    lines = [f"    E: {name} (line=18)"]
    for d in domains:
        lines += [
            "        E: exclude (line=19)",
            f'          A: domain="{d}" (Raw: "{d}")',
            f'          A: path="{path}" (Raw: "{path}")',
        ]
    return "\n".join(lines) + "\n" + extra


def rules(*sections):
    return "E: data-extraction-rules (line=17)\n" + "".join(sections)


RULES = rules(section("cloud-backup"), section("device-transfer"))


def files(rules_text=RULES, path="res/4j.xml"):
    return {path: rules_text}


class CheckBackupOffTest(unittest.TestCase):
    def test_the_check_requires_every_domain_the_platform_has(self):
        # Written out here, not taken from the script: a domain dropped from
        # the script's list would stop being required, and nothing else sees it.
        self.assertEqual(sorted(DOMAINS), sorted(check_backup_off.DOMAINS))

    def test_the_release_apk_as_built_passes(self):
        self.assertEqual([], problems(MANIFEST, RESOURCES, files().get))

    def test_backup_allowed_is_refused(self):
        manifest = MANIFEST.replace("allowBackup(0x01010280)=false", "allowBackup(0x01010280)=true")
        self.assertTrue(any("allowBackup" in p for p in problems(manifest, RESOURCES, files().get)))

    def test_an_absent_allow_backup_is_refused_because_it_defaults_to_true(self):
        manifest = "\n".join(l for l in MANIFEST.splitlines() if "allowBackup" not in l)
        self.assertTrue(any("allowBackup" in p for p in problems(manifest, RESOURCES, files().get)))

    def test_no_data_extraction_rules_is_refused(self):
        # allowBackup=false alone does not stop a device-to-device transfer on Android 12+.
        manifest = "\n".join(l for l in MANIFEST.splitlines() if "dataExtractionRules" not in l)
        self.assertTrue(any("dataExtractionRules" in p for p in problems(manifest, RESOURCES, files().get)))

    def test_rules_that_cannot_be_resolved_are_refused(self):
        self.assertTrue(problems(MANIFEST, RESOURCES.replace("0x7f140002", "0x7f140009"), files().get))
        self.assertTrue(problems(MANIFEST, RESOURCES, files(path="res/other.xml").get))

    def test_a_missing_device_transfer_section_is_refused(self):
        found = problems(MANIFEST, RESOURCES, files(rules(section("cloud-backup"))).get)
        self.assertTrue(any("device-transfer" in p for p in found), found)

    def test_a_domain_left_out_of_one_section_is_named(self):
        partial = rules(section("cloud-backup"), section("device-transfer", DOMAINS[:-1]))
        found = problems(MANIFEST, RESOURCES, files(partial).get)
        self.assertTrue(any("device-transfer" in p and "device_sharedpref" in p for p in found), found)

    def test_an_exclude_of_a_sub_path_does_not_count(self):
        narrow = rules(section("cloud-backup"), section("device-transfer", path="datastore"))
        self.assertTrue(problems(MANIFEST, RESOURCES, files(narrow).get))

    def test_an_include_is_refused(self):
        included = rules(
            section("cloud-backup", extra='        E: include (line=30)\n          A: domain="file" (Raw: "file")\n'),
            section("device-transfer"),
        )
        found = problems(MANIFEST, RESOURCES, files(included).get)
        self.assertTrue(any("include" in p for p in found), found)

    def test_rules_under_another_root_are_refused(self):
        # Android reads only a <data-extraction-rules> document; the same
        # sections under another root are ignored, and transfer stays on.
        found = problems(MANIFEST, RESOURCES, files(RULES.replace("data-extraction-rules", "unrelated-root")).get)
        self.assertTrue(any("data-extraction-rules" in p for p in found), found)

    def test_a_backup_agent_is_refused(self):
        # The extraction rules govern the files Auto Backup takes, not what a
        # custom agent sends; a library could merge one in without a conflict.
        manifest = MANIFEST.replace(
            "        A: {0}:supportsRtl".format(ANDROID),
            '        A: {0}:backupAgent(0x0101027f)="com.example.Agent" (Raw: "com.example.Agent")\n'
            "        A: {0}:supportsRtl".format(ANDROID),
        )
        found = problems(manifest, RESOURCES, files().get)
        self.assertTrue(any("backupAgent" in p for p in found), found)

    # The next cases change the dump, not the manifest: aapt2's text format is
    # no contract, and a parser that stops matching must say it did not
    # understand, never judge what it could not read (review of #236).
    def test_empty_input_is_refused_as_unrecognised(self):
        found = problems("", "", {}.get)
        self.assertTrue(any("not recognised" in p for p in found), found)

    def test_a_dump_without_its_markers_is_refused_as_unrecognised(self):
        # A format change that renames the element and attribute markers.
        changed = MANIFEST.replace("E: ", "ELEMENT ").replace("A: ", "ATTR ")
        found = problems(changed, RESOURCES, files().get)
        self.assertTrue(any("not recognised" in p for p in found), found)

    def test_attributes_it_cannot_read_are_refused_as_unrecognised_not_as_absent(self):
        # The elements still match, the attribute lines no longer do: without
        # the anchor this read as "allowBackup is absent", a wrong diagnosis.
        changed = MANIFEST.replace(f"{ANDROID}:", "android#").replace("(0x", "[0x")
        changed = "\n".join(l.replace("A: android#", "A: @android#") for l in changed.splitlines())
        found = problems(changed, RESOURCES, files().get)
        self.assertTrue(any("not recognised" in p for p in found), found)
        self.assertFalse(any("allowBackup is" in p for p in found), found)

    def test_rules_whose_excludes_it_cannot_read_are_refused_as_unrecognised(self):
        changed = RULES.replace('A: domain="', 'A: scope="')
        found = problems(MANIFEST, RESOURCES, files(changed).get)
        self.assertTrue(any("not recognised" in p for p in found), found)

    def test_a_failing_aapt2_aborts_before_anything_is_judged(self):
        # A non-zero exit or empty stdout must not flow into the parser as
        # "nothing found".
        import os
        import subprocess
        import sys
        import tempfile
        script = pathlib.Path(__file__).with_name("check-backup-off.py")
        with tempfile.TemporaryDirectory() as d:
            for body in ("exit 3", "exit 0"):  # a failure, and success with no output
                fake = pathlib.Path(d, "aapt2")
                fake.write_text(f"#!/bin/sh\n{body}\n")
                os.chmod(fake, 0o755)
                run = subprocess.run([sys.executable, str(script), str(fake), "app.apk"],
                                     capture_output=True, text=True)
                self.assertNotEqual(0, run.returncode, body)
                # The abort's own words: "not recognised" also names aapt2, and
                # an assertion on "aapt2" alone passed with the abort removed.
                self.assertIn("nothing was judged", run.stderr, body)
                self.assertNotIn("not recognised", run.stderr, body)


if __name__ == "__main__":
    unittest.main()
