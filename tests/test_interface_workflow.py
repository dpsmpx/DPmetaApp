"""Настоящий shell-шаг CI сохраняет только проверенный образ и требует все результаты UI."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[1]


def interface_script():
    lines = (ROOT / ".github/workflows/apk.yml").read_text(encoding="utf-8").splitlines()
    start = lines.index("      - name: Verify interface themes and render screens")
    run = lines.index("        run: |", start)
    end = run + 1
    while end < len(lines) and (not lines[end].strip() or lines[end].startswith("          ")):
        end += 1
    return textwrap.dedent("\n".join(lines[run + 1:end]))


class InterfaceWorkflowTest(unittest.TestCase):
    def run_step(self, **options):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        (root / "tools").mkdir()
        (root / "tools/prepare-screenshots.py").write_text(
            "import os, sys\nsys.exit(int(os.environ.get('PREPARE_EXIT', '0')))\n", encoding="utf-8")
        gradlew = root / "gradlew"
        gradlew.write_text(f"#!{sys.executable}\n" + '''
import os
from pathlib import Path
import sys
case = sys.argv[sys.argv.index("--tests") + 1].rsplit(".", 1)[-1]
with Path("calls").open("a") as trace:
    trace.write(case + "\\n")
out = Path(os.environ["GITHUB_WORKSPACE"]) / "build/interface-screenshots"
count = 8 if case == "AppearanceTest" else int(os.environ.get("SCREEN_COUNT", "8"))
for n in range(count):
    (out / f"{case}-{n}.png").write_bytes(b"synthetic image")
reports = Path("shell/build/test-results/testDebugUnitTest")
reports.mkdir(parents=True, exist_ok=True)
if case != os.environ.get("MISSING_REPORT"):
    (reports / f"TEST-com.dpsmpx.dpmeta.shell.{case}.xml").write_text("<testsuite/>")
sys.exit(int(os.environ.get(case + "_EXIT", "0")))
''', encoding="utf-8")
        gradlew.chmod(0o700)
        output = root / "outputs"
        output.touch()
        env = dict(os.environ, GITHUB_WORKSPACE=str(root), GITHUB_OUTPUT=str(output),
                   **{k: str(v) for k, v in options.items()})
        result = subprocess.run(["bash", "-c", interface_script()], cwd=root, env=env,
                                capture_output=True, text=True, timeout=15)
        return result, output.read_text(), (root / "calls").read_text() if (root / "calls").exists() else ""

    def test_all_results_allow_success_and_verified_cache(self):
        result, outputs, calls = self.run_step()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("image_ready=true\n", outputs)
        self.assertEqual(calls.splitlines(), ["AppearanceTest", "ScreenshotsTest"])

    def test_preparation_failure_prevents_cache_signal_and_tests(self):
        result, outputs, calls = self.run_step(PREPARE_EXIT=2)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(outputs, "")
        self.assertEqual(calls, "")

    def test_test_failure_does_not_revoke_verified_image_or_allow_ui_pass(self):
        for case in ("AppearanceTest", "ScreenshotsTest"):
            with self.subTest(case=case):
                result, outputs, calls = self.run_step(**{case + "_EXIT": 1})
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("image_ready=true\n", outputs)
                self.assertEqual(len(calls.splitlines()), 2)

    def test_missing_frame_fails_with_successful_test_processes(self):
        result, outputs, _ = self.run_step(SCREEN_COUNT=7)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("image_ready=true\n", outputs)

    def test_missing_report_fails_with_sixteen_frames(self):
        result, outputs, _ = self.run_step(MISSING_REPORT="ScreenshotsTest")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("image_ready=true\n", outputs)


if __name__ == "__main__":
    unittest.main()
