import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(sys.argv.pop(1)).resolve()


class LatestPrTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "user.name", "Тест")
        (self.repo / "base.txt").write_text("base", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-qm", "base")
        self.base = self.git("rev-parse", "HEAD")
        self.git("switch", "-qc", "build/latest")
        (self.repo / "new.txt").write_text("latest", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-qm", "latest")
        self.head = self.git("rev-parse", "HEAD")
        self.git("switch", "-q", "main")
        self.git("branch", "-D", "build/latest")
        self.remote = self.root / "remote.git"
        subprocess.run(["git", "init", "--bare", "-q", str(self.remote)], check=True)
        self.git("push", "-q", str(self.remote), self.head + ":refs/pull/233/head")
        self.git("remote", "add", "origin", "https://github.com/unit/repo.git")
        self.git("config", "url." + self.remote.as_uri() + ".insteadOf", "https://github.com/unit/repo.git")
        self.response = self.root / "response.json"
        self.pr = dict(number=233, state="open", head=dict(ref="build/latest", sha=self.head, repo=dict(full_name="unit/repo")), base=dict(repo=dict(full_name="unit/repo")))
        self.rows = [self.pr]
        self.bin = self.root / "bin"
        self.bin.mkdir()
        gh = self.bin / "gh"
        gh.write_text("#!" + sys.executable + "\nimport os,sys\nfrom pathlib import Path\nassert sys.argv[1:] == ['api','--hostname','github.com','repos/unit/repo/pulls?state=open&sort=created&direction=desc&per_page=1']\nprint(Path(os.environ['PR_RESPONSE']).read_text())\n", encoding="utf-8")
        gh.chmod(0o755)

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.repo, stderr=subprocess.PIPE).decode().strip()

    def run_script(self):
        self.response.write_text(json.dumps(self.rows), encoding="utf-8")
        env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"], PR_RESPONSE=str(self.response))
        return subprocess.run([sys.executable, str(SCRIPT)], cwd=self.repo, env=env, capture_output=True, text=True, timeout=30)

    def refused(self):
        r = self.run_script()
        self.assertNotEqual(0, r.returncode, r.stdout)
        self.assertNotIn("DPAI_LATEST_PR_READY", r.stdout)
        self.assertEqual("main", self.git("branch", "--show-current"))
        self.assertEqual(self.base, self.git("rev-parse", "HEAD"))
        return r

    def test_new_branch_and_repeat(self):
        for _ in range(2):
            r = self.run_script()
            self.assertEqual(0, r.returncode, r.stderr)
            self.assertEqual("build/latest", self.git("branch", "--show-current"))
            self.assertEqual(self.head, self.git("rev-parse", "HEAD"))
            self.assertIn('"number": 233', r.stdout)
            receipt = json.loads(r.stdout.split("DPAI_LATEST_PR_READY ", 1)[1])
            self.assertEqual(self.head, "".join(receipt["head"]))
            self.assertEqual("build/latest", "".join(receipt["branch"]))
            self.assertTrue(all(len(part) <= 16 for key in ("head", "branch", "repo") for part in receipt[key]))

    def test_existing_branch_fast_forwards(self):
        self.git("branch", "build/latest", self.base)
        self.assertEqual(0, self.run_script().returncode)
        self.assertEqual(self.head, self.git("rev-parse", "HEAD"))

    def test_existing_branch_fast_forwards_with_staged_and_new_files(self):
        self.git("branch", "build/latest", self.base)
        (self.repo / "base.txt").write_text("staged", encoding="utf-8")
        self.git("add", "base.txt")
        (self.repo / "base.txt").write_text("unstaged", encoding="utf-8")
        (self.repo / "notes.txt").write_text("notes", encoding="utf-8")
        cached, unstaged = self.git("diff", "--cached"), self.git("diff")
        r = self.run_script()
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertEqual(self.head, self.git("rev-parse", "HEAD"))
        self.assertEqual(cached, self.git("diff", "--cached"))
        self.assertEqual(unstaged, self.git("diff"))
        self.assertEqual("notes", (self.repo / "notes.txt").read_text())

    def test_dirty_file_is_preserved(self):
        (self.repo / "base.txt").write_text("user change", encoding="utf-8")
        r = self.run_script()
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertEqual(self.head, self.git("rev-parse", "HEAD"))
        self.assertEqual("user change", (self.repo / "base.txt").read_text())

    def test_untracked_file_is_preserved(self):
        (self.repo / "notes.txt").write_text("user new file", encoding="utf-8")
        r = self.run_script()
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertEqual(self.head, self.git("rev-parse", "HEAD"))
        self.assertEqual("user new file", (self.repo / "notes.txt").read_text())
        self.assertIn("notes.txt", self.git("ls-files", "--others", "--exclude-standard"))

    def test_conflicting_untracked_file_is_preserved(self):
        (self.repo / "new.txt").write_text("user new file", encoding="utf-8")
        self.refused()
        self.assertEqual("user new file", (self.repo / "new.txt").read_text())

    def test_staged_and_unstaged_versions_are_both_preserved(self):
        (self.repo / "base.txt").write_text("staged", encoding="utf-8")
        self.git("add", "base.txt")
        (self.repo / "base.txt").write_text("unstaged", encoding="utf-8")
        cached = self.git("diff", "--cached")
        unstaged = self.git("diff")
        r = self.run_script()
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertEqual(cached, self.git("diff", "--cached"))
        self.assertEqual(unstaged, self.git("diff"))

    def test_deleted_local_file_stays_deleted(self):
        (self.repo / "base.txt").unlink()
        r = self.run_script()
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertFalse((self.repo / "base.txt").exists())
        self.assertIn("base.txt", self.git("diff", "--name-only"))

    def test_current_pr_can_be_built_again_with_local_edits(self):
        self.assertEqual(0, self.run_script().returncode)
        (self.repo / "new.txt").write_text("local edit of PR", encoding="utf-8")
        (self.repo / "notes.txt").write_text("notes", encoding="utf-8")
        r = self.run_script()
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertEqual(self.head, self.git("rev-parse", "HEAD"))
        self.assertEqual("local edit of PR", (self.repo / "new.txt").read_text())
        self.assertTrue((self.repo / "notes.txt").is_file())

    def test_ignored_collision_is_not_overwritten(self):
        self.git("config", "core.excludesFile", str(self.root / "ignore"))
        (self.root / "ignore").write_text("new.txt\n", encoding="utf-8")
        (self.repo / "new.txt").write_text("ignored user data", encoding="utf-8")
        r = self.refused()
        self.assertIn("new.txt", r.stderr)
        self.assertEqual("ignored user data", (self.repo / "new.txt").read_text())

    def test_actual_tracked_conflict_is_not_overwritten(self):
        self.git("switch", "-qc", "source")
        (self.repo / "base.txt").write_text("remote edit", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-qm", "remote edit")
        self.head = self.git("rev-parse", "HEAD")
        self.pr["head"]["sha"] = self.head
        self.git("push", "-q", str(self.remote), "+" + self.head + ":refs/pull/233/head")
        self.git("switch", "-q", "main")
        (self.repo / "base.txt").write_text("user edit", encoding="utf-8")
        r = self.refused()
        self.assertIn("base.txt", r.stderr)
        self.assertEqual("user edit", (self.repo / "base.txt").read_text())

    def test_unresolved_merge_is_preserved_and_not_built(self):
        self.git("switch", "-qc", "other")
        (self.repo / "base.txt").write_text("other", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-qm", "other")
        self.git("switch", "-q", "main")
        (self.repo / "base.txt").write_text("main", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-qm", "main")
        conflict = subprocess.run(["git", "merge", "--no-edit", "other"], cwd=self.repo, capture_output=True)
        self.assertEqual(1, conflict.returncode)
        before, index = (self.repo / "base.txt").read_bytes(), self.git("ls-files", "--unmerged")
        r = self.run_script()
        self.assertNotEqual(0, r.returncode)
        self.assertNotIn("DPAI_LATEST_PR_READY", r.stdout)
        self.assertIn("base.txt", r.stderr)
        self.assertEqual(before, (self.repo / "base.txt").read_bytes())
        self.assertEqual(index, self.git("ls-files", "--unmerged"))

    def test_diverged_branch_is_preserved(self):
        self.git("switch", "-qc", "build/latest")
        (self.repo / "local.txt").write_text("own commit", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-qm", "own")
        own = self.git("rev-parse", "HEAD")
        self.git("switch", "-q", "main")
        r = self.refused()
        self.assertIn("свои коммиты", r.stderr)
        self.assertEqual(own, self.git("rev-parse", "build/latest"))

    def test_changed_pr_is_not_checked_out(self):
        self.pr["head"]["sha"] = self.base
        self.refused()

    def test_no_open_pr_keeps_binding(self):
        self.rows = []
        self.refused()

    def test_invalid_branch_is_not_a_git_option(self):
        self.pr["head"]["ref"] = "--force"
        self.refused()

    def test_fork_uses_numbered_branch_and_origin_pull_ref(self):
        self.pr["head"]["repo"]["full_name"] = "someone/repo"
        r = self.run_script()
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertEqual("pr/233", self.git("branch", "--show-current"))
        self.assertEqual(self.head, self.git("rev-parse", "HEAD"))


unittest.main()
