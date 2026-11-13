#!/usr/bin/env python3
"""Порядок реального run.sh на поддельном Gradle: замеры не теряются (D-121)."""
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent


class VerifyFlowTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="dpai-verify-flow-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        verify = self.root / "tools/verify"
        verify.mkdir(parents=True)
        script = (HERE / "run.sh").read_text(encoding="utf-8")
        shutil.copyfile(HERE / "run.sh", verify / "run.sh")
        shutil.copyfile(HERE / "gradle_log.py", verify / "gradle_log.py")
        # Статические проверки здесь поддельные: проверяем сам сценарий,
        # порядок вызовов, коды отказа и итоговый отчёт, а не их правила.
        steps = script.split("STATIC_STEPS=(", 1)[1].split(")", 1)[0]
        for name in re.findall(r':([\w.]+\.py)', steps):
            (verify / name).write_text("raise SystemExit(0)\n", encoding="utf-8")
        (verify / "check_android_api.py").write_text("raise SystemExit(0)\n", encoding="utf-8")
        (verify / "affected.py").write_text(
            'import os\nprint(os.environ.get("DPAI_FLOW_TASKS", ""))\n', encoding="utf-8")
        gradle = self.root / "gradlew"
        gradle.write_text('''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
with open("calls.jsonl", "a", encoding="utf-8") as out:
    out.write(json.dumps(sys.argv[1:]) + "\\n")
perf = ":tests:integration:performanceTest" in sys.argv
failure = os.environ.get("DPAI_FLOW_FAIL")
if failure == ("performance" if perf else "build"):
    print("There were failing tests.")
    raise SystemExit(1)
cli = Path("cli/build/install/dpai/bin/dpai")
cli.parent.mkdir(parents=True, exist_ok=True)
cli.write_text("#!/bin/sh\\ncase \\"$1\\" in version) echo 'dpai 0.0.0';; help) exit 0;; *) exit 2;; esac\\n")
cli.chmod(0o700)
''', encoding="utf-8")
        gradle.chmod(0o700)
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)

    def run_verify(self, level, **settings):
        env = dict(os.environ, **settings)
        # Наличие SDK текущего исполнителя не влияет на образец.
        env.pop("ANDROID_HOME", None)
        env.pop("ANDROID_SDK_ROOT", None)
        result = subprocess.run(["bash", "tools/verify/run.sh", level], cwd=self.root,
                                env=env, capture_output=True, text=True, timeout=30)
        calls = self.root / "calls.jsonl"
        report = json.loads((self.root / "build/verify/report.json").read_text(encoding="utf-8"))
        return result, [json.loads(line) for line in calls.read_text().splitlines()] if calls.exists() else [], report

    def test_full_build_finishes_before_separate_measurements(self):
        result, calls, report = self.run_verify("full")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(len(calls), 2)
        self.assertIn("build", calls[0])
        self.assertIn(":tests:integration:performanceTest", calls[1])
        self.assertIn("--no-parallel", calls[1])
        self.assertIn("--max-workers=1", calls[1])
        self.assertEqual(report["result"], "pass")
        self.assertEqual(report["steps"][-1]["name"], "замеры производительности")

    def test_failed_build_does_not_start_measurements(self):
        result, calls, report = self.run_verify("full", DPAI_FLOW_FAIL="build")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(len(calls), 1)
        self.assertEqual(report["result"], "fail")

    def test_failed_budget_fails_full_without_retry(self):
        result, calls, report = self.run_verify("full", DPAI_FLOW_FAIL="performance")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(len(calls), 2)
        self.assertEqual(report["result"], "fail")
        self.assertEqual(report["steps"][-1]["status"], "fail")
        self.assertEqual(report["steps"][-1]["downloadRetries"], 0)

    def test_affected_integration_keeps_budget_check(self):
        result, calls, _ = self.run_verify("affected", DPAI_FLOW_TASKS=":core:check :tests:integration:check")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(len(calls), 2)
        self.assertIn(":tests:integration:performanceTest", calls[1])

    def test_affected_other_module_needs_no_measurements(self):
        result, calls, _ = self.run_verify("affected", DPAI_FLOW_TASKS=":terminal:check")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(len(calls), 1)

    def test_documents_need_no_gradle(self):
        result, calls, _ = self.run_verify("affected")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(calls, [])

    def test_audit_uses_separate_measurements_instead_of_old_results(self):
        spec = importlib.util.spec_from_file_location("final_stability", HERE.parent / "audit/final_stability.py")
        audit = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(audit)
        audit.ROOT = str(self.root)
        results = self.root / "tests/integration/build/test-results"
        old = results / "test"
        old.mkdir(parents=True)
        xml = '<testsuite><testcase name="eventIngest()" classname="x.PerformanceTest">{}</testcase></testsuite>'
        (old / "TEST-x.PerformanceTest.xml").write_text(xml.format(""), encoding="utf-8")
        self.assertNotIn("PerformanceTest.eventIngest", audit.load_results())
        separate = results / "performanceTest"
        separate.mkdir()
        (separate / "TEST-x.PerformanceTest.xml").write_text(xml.format('<failure message="budget"/>'), encoding="utf-8")
        self.assertEqual(audit.load_results()["PerformanceTest.eventIngest"], "FAIL")


if __name__ == "__main__":
    unittest.main()
