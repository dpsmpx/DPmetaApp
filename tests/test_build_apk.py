"""Единая упаковка APK (D-173): выбор shell и ранний отказ снятого варианта ai."""
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(os.name == "posix" and shutil.which("sh") and shutil.which("sha256sum"), "сценарий сборки требует POSIX sh и sha256sum")
class BuildApkDispatch(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        tools = self.root / "tools"
        tools.mkdir()
        self.script = tools / "build-apk.sh"
        shutil.copyfile(ROOT / "tools/build-apk.sh", self.script)
        self.trace = self.root / "gradle-call"
        # Обе старые ветки могли бы выполниться: тест ai не должен пройти
        # просто потому, что каталога dpai или прежнего APK в образце нет.
        for base, module in [(self.root, "shell"), (self.root / "dpai", "android-app")]:
            base.mkdir(exist_ok=True)
            (base / "gradlew").write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$APK_TEST_TRACE"\n')
            out = base / module / "build/outputs/apk/debug"
            out.mkdir(parents=True)
            (out / "sample.apk").write_bytes(b"synthetic apk")
        sdk = self.root / "sdk"
        sdk.mkdir()
        self.env = dict(os.environ, ANDROID_HOME=str(sdk), APK_TEST_TRACE=str(self.trace))

    def run_script(self, *args):
        return subprocess.run(["sh", str(self.script), *args], cwd=self.root, env=self.env, text=True, capture_output=True, timeout=10)

    def test_default_app_builds_shell(self):
        result = self.run_script("debug")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.trace.read_text().splitlines(), ["--console=plain", ":shell:assembleDebug"])
        sums = self.root / "shell/build/outputs/apk/debug/SHA256SUMS"
        self.assertTrue(sums.is_file())
        self.assertIn("sample.apk", sums.read_text())

    def test_retired_ai_is_rejected_before_gradle(self):
        result = self.run_script("debug", "ai")
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertFalse(self.trace.exists(), "снятый вариант не должен запускать Gradle")


if __name__ == "__main__":
    unittest.main()
