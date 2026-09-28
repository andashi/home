"""Tests for check-release-logs.py (#15).

The fixture lines are dexdump output as it came from the release APK built
for this change (build-tools 37.0.0).
"""

import importlib.util
import pathlib
import unittest

_spec = importlib.util.spec_from_file_location(
    "check_release_logs", pathlib.Path(__file__).with_name("check-release-logs.py")
)
check_release_logs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(check_release_logs)
problems = check_release_logs.problems

HEADER = "Processing 'classes.dex'...\nOpened 'classes.dex', DEX version '039'\n"
KEPT = "515a70: 7120 fa05 7200 |005e: invoke-static {v2, v7}, Landroid/util/Log;.d:(Ljava/lang/String;Ljava/lang/String;)I // method@05fa\n"
VERBOSE = "515a72: 7120 fa06 7200 |0060: invoke-static {v2, v7}, Landroid/util/Log;.v:(Ljava/lang/String;Ljava/lang/String;)I // method@05fb\n"
WARNING = "5104e4: 7120 fa07 b100 |013c: invoke-static {v1, v11}, Landroid/util/Log;.w:(Ljava/lang/String;Ljava/lang/String;)I // method@05fc\n"


class CheckReleaseLogsTest(unittest.TestCase):
    def test_a_dex_with_warnings_and_no_debug_calls_passes(self):
        self.assertEqual([], problems(HEADER + WARNING))

    def test_a_debug_call_left_in_the_dex_is_found(self):
        self.assertTrue(problems(HEADER + WARNING + KEPT))

    def test_a_verbose_call_left_in_the_dex_is_found(self):
        self.assertTrue(problems(HEADER + VERBOSE))

    def test_empty_output_is_refused_not_passed(self):
        # A dexdump that failed or read nothing must not read as a clean APK.
        self.assertTrue(problems(""))

    def test_a_dump_without_any_log_call_is_refused(self):
        # The launcher logs warnings; a dump with none did not read its code.
        self.assertTrue(problems(HEADER))


if __name__ == "__main__":
    unittest.main()
