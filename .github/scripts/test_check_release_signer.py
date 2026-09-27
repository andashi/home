"""Tests for check-release-signer.py, run by the release-gate job in test.yml.

    python3 -m unittest discover -s .github/scripts
"""
import importlib.util
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("signer", os.path.join(HERE, "check-release-signer.py"))
signer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signer)

RELEASE = "e42da3c2cf9e682d67b96f3655b2adf1bb22488a064b6ff833520b3c43f2a5ee"

# apksigner verify --print-certs, as v0.9.0's CERTS.txt has it.
V090 = f"""Signer #1 certificate DN: CN=Andashi Home, O=andashi, L=Heidelberg, C=DE
Signer #1 certificate SHA-256 digest: {RELEASE}
Signer #1 certificate SHA-1 digest: f7974abe866b8a60dbb241712a035b1921321b7c
Signer #1 certificate MD5 digest: c8168a8626eff335bb4248252654d1a4
"""


class CheckReleaseSigner(unittest.TestCase):
    def test_the_release_key_passes(self):
        self.assertEqual(signer.problems(V090, RELEASE), [])

    def test_the_v2_spelling_passes_too(self):
        text = V090.replace("Signer #1 certificate", "V2 Signer: certificate")
        self.assertEqual(signer.problems(text, RELEASE), [])

    def test_an_empty_output_fails(self):
        # The step's old check was "no debug key in it", which an empty
        # CERTS.txt passes: nothing read is not the release key.
        self.assertNotEqual(signer.problems("", RELEASE), [])

    def test_another_key_fails(self):
        other = V090.replace(RELEASE, "0" * 64)
        self.assertNotEqual(signer.problems(other, RELEASE), [])

    def test_a_second_signer_fails(self):
        two = V090 + V090.replace("#1", "#2").replace(RELEASE, "1" * 64)
        self.assertNotEqual(signer.problems(two, RELEASE), [])

    def test_the_debug_key_fails(self):
        debug = V090.replace("CN=Andashi Home, O=andashi, L=Heidelberg, C=DE", "C=US, O=Android, CN=Android Debug")
        self.assertNotEqual(signer.problems(debug, RELEASE), [])

    def test_an_empty_expectation_fails(self):
        # An unset RELEASE_CERT_SHA256 must not match anything.
        self.assertNotEqual(signer.problems(V090, ""), [])

    def test_a_digest_is_compared_whole(self):
        self.assertNotEqual(signer.problems(V090, RELEASE[:40]), [])


if __name__ == "__main__":
    unittest.main()
