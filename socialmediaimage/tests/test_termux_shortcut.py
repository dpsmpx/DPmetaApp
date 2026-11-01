"""Ярлык Termux:Widget для выгрузки сайта (Д35): только вызывает систему, пробный прогон и «да» — до выгрузки.
Настоящие bash и git; Python системы подменён заглушкой, которая пишет, как её вызвали."""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent.parent / "tools" / "termux" / "dpsmpx-site-deploy.sh"

STUB = """#!/bin/bash
echo "$*" >> "$STUB_LOG"
case "$*" in
  "-m tools.cs site deploy --dry-run")
    echo "пробный прогон: rsync ... <адрес выгрузки>"
    [ "$STUB_CHANGES" = 0 ] || printf '  <fcsT...... .htaccess\\n'
    [ -z "${STUB_DELETE:-}" ] || printf '  *deleting   old.html\\n'
    echo "  изменений: $STUB_CHANGES"
    exit "${STUB_DRY_RC:-0}" ;;
  "-m tools.cs site deploy") exit "${STUB_DEPLOY_RC:-0}" ;;
  -c*) echo "${STUB_HTTP:-200}" ;;
  *) exit 99 ;;
esac
"""

GIT_ENV = {"GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t", "GIT_COMMITTER_NAME": "t",
           "GIT_COMMITTER_EMAIL": "t", "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1"}


class TermuxShortcut(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.home = self.tmp / "home"
        (self.home / ".config" / "dpsmpx").mkdir(parents=True)
        (self.home / ".config" / "dpsmpx" / "deploy.env").write_text("export CS_PROD_SITE_DEPLOY_TARGET=x\n")
        self.repo = self.home / "SocialMediaImage"
        origin = self.tmp / "origin.git"
        env = {**os.environ, **GIT_ENV}
        run = lambda *a, cwd=None: subprocess.run(a, cwd=cwd, env=env, check=True, capture_output=True)
        run("git", "init", "-q", "--bare", "-b", "main", str(origin))
        run("git", "clone", "-q", str(origin), str(self.repo))
        run("git", "checkout", "-q", "-B", "main", cwd=self.repo)
        (self.repo / "README.md").write_text("x\n")
        run("git", "add", ".", cwd=self.repo)
        run("git", "commit", "-q", "-m", "init", cwd=self.repo)
        run("git", "push", "-q", "-u", "origin", "main", cwd=self.repo)
        self.git = run
        self.stub = self.tmp / "py"
        self.stub.write_text(STUB)
        self.stub.chmod(0o755)
        self.log = self.tmp / "calls.log"

    def shortcut(self, answer="", stdin=None, session="", **stub):
        """answer — ответ с переводом строки; stdin — сырой ввод (subprocess.DEVNULL — без ввода);
        session — CLAUDECODE (по умолчанию — терминал автора, не сессия ИИ)."""
        env = {**os.environ, **GIT_ENV, "HOME": str(self.home), "DPSMPX_PY": str(self.stub),
               "DPSMPX_REPO": str(self.repo), "STUB_LOG": str(self.log), "STUB_CHANGES": "1",
               "CLAUDECODE": session, "CLAUDE_CODE_REMOTE": "",
               **{f"STUB_{k.upper()}": str(v) for k, v in stub.items()}}
        feed = {"stdin": stdin} if stdin is not None else {"input": answer + "\n" if stdin is None else None}
        if isinstance(stdin, str):
            feed = {"input": stdin}
        r = subprocess.run(["bash", str(SCRIPT)], env=env, capture_output=True, text=True, timeout=60, **feed)
        calls = self.log.read_text().splitlines() if self.log.exists() else []
        return r.returncode, r.stdout + r.stderr, calls

    def test_deploys_only_after_dry_run_and_yes(self):
        rc, out, calls = self.shortcut("да")
        self.assertEqual(rc, 0, out)
        self.assertEqual(calls[:2], ["-m tools.cs site deploy --dry-run", "-m tools.cs site deploy"])
        self.assertTrue(calls[2].startswith("-c"))
        self.assertIn("✓ готово", out)

    def test_no_changes_no_question(self):
        rc, out, calls = self.shortcut("да", changes=0)
        self.assertEqual(rc, 0, out)
        self.assertEqual(calls, ["-m tools.cs site deploy --dry-run"])
        self.assertIn("выгружать нечего", out)

    def test_anything_but_yes_cancels(self):
        for answer in ("", "нет", "y", "Да "):
            rc, out, calls = self.shortcut(answer)
            self.assertEqual(rc, 1, (answer, out))
            self.assertNotIn("-m tools.cs site deploy", calls, answer)
            self.log.unlink()

    def test_no_input_or_cut_input_is_no(self):
        """Без терминала (как в сессии ИИ) и при оборванном вводе «да» без перевода строки — отказ."""
        for feed in (subprocess.DEVNULL, "да"):
            rc, out, calls = self.shortcut(stdin=feed)
            self.assertEqual(rc, 1, (feed, out))
            self.assertNotIn("-m tools.cs site deploy", calls, feed)
            self.log.unlink(missing_ok=True)

    def test_refuses_in_ai_session(self):
        """Д36: сессия ИИ выгружает только `python3 -m tools.cs site deploy` (ask), не ярлыком."""
        rc, out, calls = self.shortcut("да", session="1")
        self.assertEqual((rc, calls), (1, []), out)
        self.assertIn("только для автора", out)

    def test_failed_dry_run_stops(self):
        rc, out, calls = self.shortcut("да", dry_rc=2)
        self.assertEqual(rc, 1, out)
        self.assertEqual(calls, ["-m tools.cs site deploy --dry-run"])

    def test_deletions_are_flagged(self):
        rc, out, _ = self.shortcut("нет", delete=1)
        self.assertIn("*deleting", out)
        self.assertIn("⚠", out)

    def test_broken_site_after_deploy_fails(self):
        rc, out, calls = self.shortcut("да", http=500)
        self.assertEqual(rc, 1, out)
        self.assertIn("-m tools.cs site deploy", calls)
        self.assertIn("500", out)

    def test_refuses_dirty_tree_and_other_branch(self):
        (self.repo / "README.md").write_text("changed\n")
        rc, out, calls = self.shortcut("да")
        self.assertEqual((rc, calls), (1, []), out)
        self.git("git", "checkout", "-q", "--", "README.md", cwd=self.repo)
        self.git("git", "checkout", "-q", "-b", "feature", cwd=self.repo)
        rc, out, calls = self.shortcut("да")
        self.assertEqual((rc, calls), (1, []), out)

    def test_refuses_without_address_file(self):
        (self.home / ".config" / "dpsmpx" / "deploy.env").unlink()
        rc, out, calls = self.shortcut("да")
        self.assertEqual((rc, calls), (1, []), out)

    def test_only_calls_the_system(self):
        """Д35: ни rsync/scp/sftp/lftp, ни адреса сервера — только python -m tools.cs site deploy."""
        code = [ln for ln in SCRIPT.read_text().splitlines() if not ln.lstrip().startswith("#")]
        text = "\n".join(code)
        for tool in ("rsync", "scp", "sftp", "lftp", "ssh "):
            self.assertNotIn(tool, text)
        self.assertIsNone(re.search(r"[\w.-]+@[\w.-]+:", text))
        subprocess.run(["bash", "-n", str(SCRIPT)], check=True)
        self.assertEqual(SCRIPT.stat().st_mode & 0o111 != 0, True)


if __name__ == "__main__":
    unittest.main()
