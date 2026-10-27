"""Regression checks for release signing and mandatory license packaging."""

import contextlib
import io
import os
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

import build_apk


class BuildApkTests(unittest.TestCase):
    def test_diagnostics_cannot_crash_gbk_console(self):
        raw = io.BytesIO()
        console = io.TextIOWrapper(raw, encoding="gbk", errors="strict")
        completed = subprocess.CompletedProcess([], 0, "", "diagnostic: \u05e2 \U0001f600")
        with patch.object(build_apk.sys, "stdout", console):
            with patch.object(build_apk.subprocess, "run", return_value=completed):
                build_apk.configure_stdio()
                build_apk.run(["javac"])
        console.flush()
        self.assertIn(b"diagnostic:", raw.getvalue())
        self.assertEqual(console.errors, "replace")

    def test_signing_passwords_do_not_enter_argv_or_logs(self):
        store_pass = "store-secret with spaces & symbols"
        key_pass = "key-secret ! unicode-口令"
        output = io.StringIO()
        completed = subprocess.CompletedProcess([], 0, "", "")
        original_env = dict(os.environ)
        with patch.object(build_apk.subprocess, "run", return_value=completed) as child:
            with contextlib.redirect_stdout(output):
                build_apk.sign_apk(Path("release.jks"), "release", store_pass,
                                   key_pass, Path("aligned.apk"))
        args, kwargs = child.call_args
        argv = args[0]
        self.assertNotIn(store_pass, " ".join(argv))
        self.assertNotIn(key_pass, " ".join(argv))
        self.assertNotIn(store_pass, output.getvalue())
        self.assertNotIn(key_pass, output.getvalue())
        self.assertEqual(argv[argv.index("--ks-pass") + 1],
                         "env:PF_APKSIGNER_STORE_PASS")
        self.assertEqual(argv[argv.index("--key-pass") + 1],
                         "env:PF_APKSIGNER_KEY_PASS")
        self.assertEqual(kwargs["env"]["PF_APKSIGNER_STORE_PASS"], store_pass)
        self.assertEqual(kwargs["env"]["PF_APKSIGNER_KEY_PASS"], key_pass)
        self.assertEqual(dict(os.environ), original_env)

    def test_missing_license_fails_before_build_commands(self):
        missing = Path("missing-zxing-license.txt")
        with patch.object(build_apk, "THIRD_PARTY_NOTICES", {missing: "assets/license"}):
            with patch.multiple(build_apk, AAPT2=Path(__file__), ANDROID_JAR=Path(__file__),
                                D8=Path(__file__), ZIPALIGN=Path(__file__),
                                APKSIGNER=Path(__file__)):
                with patch.object(build_apk, "run") as run:
                    with contextlib.redirect_stderr(io.StringIO()) as errors:
                        self.assertEqual(build_apk.main(), 1)
                    self.assertIn(str(missing), errors.getvalue())
                    run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
