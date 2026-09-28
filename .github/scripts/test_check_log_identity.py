"""Tests for check-log-identity.py (#15).

The leaking fixtures are the lines as they stood on main before the sweep,
verbatim; the clean ones are what those lines became, and other interpolating
calls the sweep reviewed and left.
"""

import importlib.util
import pathlib
import unittest

_spec = importlib.util.spec_from_file_location(
    "check_log_identity", pathlib.Path(__file__).with_name("check-log-identity.py")
)
check_log_identity = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(check_log_identity)
findings = check_log_identity.findings

LEAKING = [
    'Log.w(Tag, "binding $widget failed", e)',
    'Log.e("MM20", "Failed to deserialize app: $serialized", e)',
    'Log.e("MM20", "Failed to deserialize shortcut: $serialized", e)',
    'Log.e("MM20", "Could not remove shortcut ${key}: shortcut query returned null")',
    'Log.i(\n    "MM20",\n    "SearchableDatabase cleanup: removed invalid item ${item.key}"\n)',
    'Log.e(\n    "FeedConnection",\n    "Unknown service descriptor \\"${service.interfaceDescriptor}\\" for intent $serviceIntent"\n)',
    'Log.e("MM20", "Icon pack package $iconPack not found!")',
    'Log.e(\n    "MM20",\n    "Could not install icon pack ${iconPack.packageName}: package not found."\n)',
    'Log.e(\n    "MM20",\n    "appfilter.xml not found in $packageName. Searched locations: res/xml/appfilter.xml"\n)',
    'Log.w(\n    "MM20",\n    "App ${app.label} does not belong to any known profile. Ignoring."\n)',
    'Log.e(\n    "MM20",\n    "Could not parse widget result: widgetId=$widgetId, widgetProviderInfo=$widgetProviderInfo"\n)',
    'Log.w("MM20", "Shortcut result is missing required extras: intent=$intent, name=$name")',
    'Log.e(TAG, "write-back produced a document that does not parse; not written: ${check.diagnostics}")',
    # Not from main: the concatenated form of the same leak, which templates
    # alone would not see.
    'Log.e("FeedConnection", "Unknown service descriptor for intent " + serviceIntent)',
    'Log.w("MM20", widgetProviderInfo.provider.packageName + " failed to bind")',
    # Review on #239: an identity inside a larger expression prints too.
    'Log.e("MM20", "intent=${intent ?: \\"none\\"}")',
    'Log.e("MM20", "target ${intent.toUri(0)}")',
    'Log.w("MM20", "missing ${app.packageName ?: "unknown"}")',
    'Log.w("MM20", "whole ${it}")',
]

CLEAN = [
    'Log.w(Tag, "binding a widget failed", e)',
    'Log.e("MM20", "Icon pack package not found")',
    'Log.i("MM20", "SearchableDatabase cleanup: removed an invalid ${fav.type} item")',
    'Log.e(\n    "MM20",\n    "Could not parse widget result: widgetId missing=${widgetId == null}, " +\n        "provider missing=${widgetProviderInfo == null}"\n)',
    'Log.w("MM20", "Shortcut result is missing required extras: ${missing.joinToString()}")',
    'Log.e(TAG, "not written: " + check.diagnostics.joinToString { "${it.code}@${it.path}" })',
    'Log.w(Tag, "layout ${geometry.layout}: corrected $issue")',
    'Log.w("GlassBackdrop", "refresh failed: ${e.javaClass.simpleName}")',
    'Log.w(TAG, "Unable to unlock profile ${profile.serial}", e)',
    'Log.w(TAG, "Upload of ${tmp.length()} bytes exceeds the limit of $maxBytes, discarded")',
    'Log.e(TAG, "not written: " + check.diagnostics.joinToString { it.code })',
    'Log.w(TAG, "codes ${items.map { it.code }}")',
    # Debug and verbose calls are not this check's: R8 strips them, and
    # check-release-logs.py proves it on the APK.
    'Log.d("MM20", "Icon pack ${pack.packageName} is up to date")',
]


def source(call):
    return "package x\n\nfun f() {\n    " + call + "\n}\n"


class CheckLogIdentityTest(unittest.TestCase):
    def test_every_line_the_sweep_fixed_is_found(self):
        for call in LEAKING:
            with self.subTest(call=call.splitlines()[0]):
                self.assertTrue(findings("X.kt", source(call)), call)

    def test_what_the_lines_became_and_the_reviewed_rest_pass(self):
        for call in CLEAN:
            with self.subTest(call=call.splitlines()[0]):
                self.assertEqual([], findings("X.kt", source(call)))

    def test_a_finding_names_the_file_line_and_expression(self):
        found = findings("a/B.kt", source('Log.e("MM20", "Icon pack package $iconPack not found!")'))
        self.assertEqual(1, len(found))
        self.assertIn("a/B.kt:4", found[0])
        self.assertIn("iconPack", found[0])

    # Review on #239: a commented-out call is no call - neither a finding nor
    # evidence that the scan read something.
    def test_a_commented_out_call_is_not_a_finding(self):
        src = source('// Log.e(TAG, "bad $packageName")\n    /* Log.w(TAG, "$intent") */\n    Log.w(TAG, "fine")')
        self.assertEqual([], findings("X.kt", src))

    def test_a_tree_whose_only_calls_are_comments_read_nothing(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            pathlib.Path(d, "A.kt").write_text(source('// Log.e(TAG, "gone")'))
            with self.assertRaises(SystemExit):
                check_log_identity.scan(pathlib.Path(d))

    def test_the_tree_it_is_run_on_must_have_log_calls(self):
        # A scan that reads nothing must not report that nothing leaks.
        with self.assertRaises(SystemExit):
            check_log_identity.scan(pathlib.Path("/nonexistent-tree"))


if __name__ == "__main__":
    unittest.main()
