import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import sign_release


class ReleaseSigningTests(unittest.TestCase):
    def test_missing_secrets_cannot_produce_release(self):
        with patch.dict(os.environ, {}, clear=True), tempfile.TemporaryDirectory() as root:
            directory = Path(root)
            with self.assertRaisesRegex(RuntimeError, "Missing release signing configuration"):
                sign_release.sign_artifacts(directory, directory / "output", directory, directory / "policy.json", directory / "report.json")
            self.assertFalse((directory / "output").exists())

    def test_unconfigured_pin_and_compromised_pin_are_rejected(self):
        with patch.dict(os.environ, {name: "not-a-real-secret" for name in sign_release.REQUIRED_ENV}), tempfile.TemporaryDirectory() as root:
            directory = Path(root)
            for current in (None, "", "g" * 64, "a" * 64):
                policy = directory / "policy.json"
                policy.write_text(json.dumps({"minimumSdk": 33, "legacyCertificateSha256": "a" * 64, "certificateSha256": current}))
                with self.assertRaisesRegex(RuntimeError, "initialize and pin"):
                    sign_release.sign_artifacts(directory, directory / "output", directory, policy, directory / "report.json")

    def lineage(self, rollback="false", permission="false", old="a", new="b"):
        return f'''Signer #1 in lineage certificate DN: Legacy
Signer #1 in lineage certificate SHA-256 digest: {old * 64}
Has installed data capability: true
Has shared UID capability: false
Has permission capability: {permission}
Has rollback capability: {rollback}
Has auth capability: false
Signer #2 in lineage certificate DN: New
Signer #2 in lineage certificate SHA-256 digest: {new * 64}
'''

    def test_forward_only_lineage_is_accepted(self):
        with patch.object(sign_release, "run", return_value=self.lineage()):
            self.assertEqual(sign_release.validate_lineage(Path("apksigner"), Path("lineage"), "a" * 64, "b" * 64), ["a" * 64, "b" * 64])

    def test_rollback_and_signature_permission_trust_are_rejected(self):
        for text in (self.lineage(rollback="true"), self.lineage(permission="true")):
            with patch.object(sign_release, "run", return_value=text), self.assertRaisesRegex(RuntimeError, "Unsafe legacy"):
                sign_release.validate_lineage(Path("apksigner"), Path("lineage"), "a" * 64, "b" * 64)

    def test_another_root_or_leaf_cannot_replace_the_pinned_identity(self):
        for text in (self.lineage(old="c"), self.lineage(new="c")):
            with patch.object(sign_release, "run", return_value=text), self.assertRaisesRegex(RuntimeError, "pinned certificates"):
                sign_release.validate_lineage(Path("apksigner"), Path("lineage"), "a" * 64, "b" * 64)

    def test_signing_failure_does_not_echo_tool_output(self):
        import subprocess
        result = subprocess.CompletedProcess([], 1, b"SECRET-CONTENT", b"SECRET-CONTENT")
        with patch.object(sign_release.subprocess, "run", return_value=result), self.assertRaises(RuntimeError) as error:
            sign_release.run(["keytool"])
        self.assertNotIn("SECRET-CONTENT", str(error.exception))


if __name__ == "__main__":
    unittest.main()
