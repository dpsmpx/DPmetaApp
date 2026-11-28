"""Повторы подготовки образа не превращают отказ проверки в успешный тест."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "tools/prepare-screenshots.py"
SPEC = importlib.util.spec_from_file_location("prepare_screenshots", SCRIPT)
PREPARATION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARATION)


class PreparationTest(unittest.TestCase):
    def run_preparation(self, results):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        (root / "results.json").write_text(json.dumps(results), encoding="utf-8")
        gradlew = root / "gradlew"
        gradlew.write_text(f"#!{sys.executable}\n" + '''
import json
from pathlib import Path
import sys
assert sys.argv[1:] == ["--console=plain", ":shell:prepareRobolectricImage", "-Pdpmeta.screenshots"]
count = Path("calls")
attempt = int(count.read_text()) if count.exists() else 0
count.write_text(str(attempt + 1))
code, output = json.loads(Path("results.json").read_text())[attempt]
print(output)
sys.exit(code)
''', encoding="utf-8")
        gradlew.chmod(0o700)
        delays = []
        with contextlib.redirect_stdout(io.StringIO()):
            code = PREPARATION.prepare(root, root / "logs", sleep=delays.append)
        logs = [p.read_text(encoding="utf-8") for p in sorted((root / "logs").glob("*.log"))]
        return code, int((root / "calls").read_text()), delays, logs

    def test_success_needs_one_preparation(self):
        self.assertEqual(self.run_preparation([(0, "UP-TO-DATE")]),
                         (0, 1, [], ["UP-TO-DATE\n"]))

    def test_rate_limit_then_success_preserves_both_logs(self):
        failure = "Received status code 429 from server: Too Many Requests"
        self.assertEqual(self.run_preparation([(1, failure), (0, "verified")]),
                         (0, 2, [15], [failure + "\n", "verified\n"]))

    def test_gateway_failure_stops_after_three_attempts(self):
        for status in (429, 502, 503, 504):
            with self.subTest(status=status):
                failure = f"Received status code {status} from server: temporarily unavailable"
                code, count, delays, logs = self.run_preparation([(1, failure)] * 3)
                self.assertEqual((code, count, delays), (1, 3, [15, 30]))
                self.assertEqual(logs, [failure + "\n"] * 3)

    def test_permanent_failure_is_not_retried(self):
        for output in ("Dependency verification failed for configuration ':shell:robolectricAndroid'",
                       "checksum mismatch", "Script compilation error", "e: unresolved reference",
                       "Received status code 404 from server: Not Found", "PKIX path building failed",
                       "No space left on device", "BUILD FAILED"):
            with self.subTest(output=output):
                self.assertEqual(self.run_preparation([(2, output)])[:3], (2, 1, []))

    def test_verification_failure_wins_over_transient_http(self):
        output = "Received status code 429 from server: Too Many Requests\nDependency verification failed"
        self.assertEqual(self.run_preparation([(1, output)])[:3], (1, 1, []))

    def test_mixed_http_failures_are_not_retried(self):
        output = "Received status code 503 from server: Unavailable\nReceived status code 401 from server: Unauthorized"
        self.assertEqual(self.run_preparation([(1, output)])[:3], (1, 1, []))


if __name__ == "__main__":
    unittest.main()
