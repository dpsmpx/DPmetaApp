"""Самотесты tools/activity.py на синтетических репозиториях.

Запуск: python3 -B -m unittest discover -s tests
"""

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))

import activity  # noqa: E402

REGISTRY = """\
schema = 1

[[repo]]
name = "Alpha"
slug = "o/Alpha"
clone = "alpha"

[[repo]]
name = "Beta"
slug = "o/Beta"
clone = "beta"
"""

NOW = "2026-10-05T12:00:00+00:00"


def git(cwd: Path, *args: str, when: str = "2026-10-01T00:00:00+00:00") -> None:
    env = {**os.environ, "GIT_AUTHOR_DATE": when, "GIT_COMMITTER_DATE": when,
           "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@example.invalid",
           "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@example.invalid"}
    subprocess.run(["git", "-C", str(cwd), *args], check=True, env=env,
                   capture_output=True)


def commit(cwd: Path, name: str, text: str, when: str) -> None:
    (cwd / name).write_text(text, encoding="utf-8")
    git(cwd, "add", "-A", when=when)
    git(cwd, "commit", "-q", "-m", name, when=when)


class ActivityTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.registry = self.root / "ecosystem.toml"
        self.registry.write_text(REGISTRY, encoding="utf-8")

        # Alpha: свежий коммит в main, свежая невлитая ветка, влитая ветка,
        # ветка с тем же изменением другим коммитом и невлитая ветка DPmeta.
        up = self.root / "up-alpha"
        up.mkdir()
        git(up, "init", "-q", "-b", "main")
        commit(up, "a.txt", "1\n", "2026-10-01T00:00:00+00:00")
        git(up, "checkout", "-q", "-b", "merged")
        commit(up, "m.txt", "m\n", "2026-10-02T00:00:00+00:00")
        git(up, "checkout", "-q", "main")
        git(up, "merge", "-q", "--ff-only", "merged")
        git(up, "checkout", "-q", "-b", "work")
        commit(up, "w.txt", "w\n", "2026-10-05T06:00:00+00:00")
        git(up, "checkout", "-q", "main")
        git(up, "checkout", "-q", "-b", "redone")
        commit(up, "r.txt", "r\n", "2026-10-05T07:00:00+00:00")
        git(up, "checkout", "-q", "main")
        git(up, "checkout", "-q", "-b", "dpmeta/docs-sync")
        commit(up, "d.txt", "d\n", "2026-10-05T08:00:00+00:00")
        git(up, "checkout", "-q", "main")
        commit(up, "r.txt", "r\n", "2026-10-05T10:00:00+00:00")  # то же, что в redone
        git(self.root, "clone", "-q", str(up), str(self.root / "alpha"))

        # Beta: тихий репозиторий — последний коммит неделю назад.
        upb = self.root / "up-beta"
        upb.mkdir()
        git(upb, "init", "-q", "-b", "main")
        commit(upb, "b.txt", "b\n", "2026-09-28T12:00:00+00:00")
        git(self.root, "clone", "-q", str(upb), str(self.root / "beta"))

    def tearDown(self):
        self.tmp.cleanup()

    def run_activity(self, *args):
        out = self.root / "report.md"
        code = activity.main(["--registry", str(self.registry), "--root", str(self.root),
                              "--now", NOW, "--out", str(out), *args])
        return code, out.read_text(encoding="utf-8")

    def test_verdicts(self):
        code, report = self.run_activity()
        self.assertEqual(code, 0)
        self.assertIn("| Alpha | `main` |", report)
        self.assertIn("2 ч | активен — коммит в `main`, невлитые ветки: 1 |", report)
        self.assertIn("| Beta | `main` |", report)
        self.assertIn("7 дн | признаков нет |", report)

    def test_unmerged_work_and_dpmeta_branches(self):
        _, report = self.run_activity()
        work = report.split("## Ветки с невлитой работой")[1].split("## Ветки DPmeta")[0]
        self.assertIn("| Alpha | `work` |", work)
        self.assertIn("6 ч **свежая** | 1 |", work)
        # Влитая ветка и ветка, чьё изменение внесено другим коммитом, — не
        # невлитая работа; ветка DPmeta — в своём разделе.
        self.assertNotIn("`merged`", work)
        self.assertNotIn("`redone`", work)
        self.assertNotIn("dpmeta/", work)
        self.assertIn("| Alpha | `dpmeta/docs-sync` |", report)
        self.assertIn("не влита (1 коммит.)", report)

    def test_thresholds(self):
        # Коммит в main 2 ч назад: при пороге 1 ч он уже не свежий, но
        # невлитая ветка (6 ч) свежая при пороге веток 24 ч.
        _, report = self.run_activity("--main-hours", "1")
        self.assertIn("| активен — невлитые ветки: 1 |", report)
        _, report = self.run_activity("--main-hours", "1", "--hours", "5")
        self.assertIn("| Alpha | `main` | ", report)
        self.assertNotIn("**свежая**", report)
        self.assertIn("2 ч | признаков нет |", report)

    def test_missing_clone_and_bad_call(self):
        code, report = self.run_activity("--clone", f"Beta={self.root / 'nowhere'}")
        self.assertEqual(code, 0)
        self.assertIn("| Beta | — | — | — | нет клона |", report)
        self.assertEqual(activity.main(["--registry", str(self.registry),
                                        "--clone", "Gamma=x"]), 2)
        self.assertEqual(activity.main(["--registry", str(self.registry),
                                        "--now", "не время"]), 2)


if __name__ == "__main__":
    unittest.main()
