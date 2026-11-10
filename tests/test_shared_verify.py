"""D-89: настоящие Git-репозитории и процессы, без сети и учётных данных."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / 'tools/shared_verify/runtime.py'
spec = importlib.util.spec_from_file_location('shared_verify', SOURCE)
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class SharedVerifyTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.remote, self.repo = self.base / 'remote.git', self.base / 'tools'
        self.state = self.base / 'state'
        self.git(self.base, 'init', '--bare', '--initial-branch=main', str(self.remote))
        self.git(self.base, 'clone', str(self.remote), str(self.repo))
        self.configure(self.repo)
        self.commit(self.repo, 'first')
        self.git(self.repo, 'push', 'origin', 'main')
        self.command = [sys.executable, '-c', 'pass']

    def git(self, path, *args):
        return subprocess.check_output(['git', '-C', str(path), *args], stderr=subprocess.DEVNULL).decode().strip()

    def configure(self, repo):
        self.git(repo, 'config', 'user.name', 'Synthetic')
        self.git(repo, 'config', 'user.email', 'fixture@example.invalid')

    def commit(self, repo, value):
        (repo / 'tool.txt').write_text(value)
        self.git(repo, 'add', 'tool.txt')
        self.git(repo, 'commit', '-m', value)
        return self.git(repo, 'rev-parse', 'HEAD')

    def other(self):
        other = self.base / 'other'
        self.git(self.base, 'clone', str(self.remote), str(other))
        self.configure(other)
        return other

    def sync(self):
        return runtime.sync(self.repo, self.state, str(self.remote), self.command)

    def test_aliases_and_worktrees_share_one_lease(self):
        alias = self.base / 'alias'
        alias.symlink_to(self.repo, target_is_directory=True)
        worktree = self.base / 'worktree'
        self.git(self.repo, 'worktree', 'add', '--detach', str(worktree), 'HEAD')
        with runtime.lease(self.repo):
            for path in (alias, worktree):
                with self.assertRaises(runtime.Busy):
                    with runtime.lease(path, exclusive=True):
                        self.fail('alias bypassed lease')

    def test_state_cannot_pollute_tools(self):
        with self.assertRaisesRegex(runtime.Refused, 'state_must_be_outside_tools'):
            runtime.sync(self.repo, self.repo / 'state', str(self.remote), self.command)
        self.assertFalse((self.repo / 'state').exists())

    def test_checked_local_commit_is_published_without_pr(self):
        revision = self.commit(self.repo, 'local')
        result = self.sync()
        self.assertEqual(result, {'status': 'published', 'revision': revision})
        self.assertEqual(self.git(self.remote, 'rev-parse', 'main'), revision)

    def test_remote_is_checked_before_fast_forward(self):
        other = self.other()
        revision = self.commit(other, 'remote')
        self.git(other, 'push', 'origin', 'main')
        self.assertEqual(self.sync()['status'], 'downloaded')
        self.assertEqual(self.git(self.repo, 'rev-parse', 'HEAD'), revision)

    def test_failed_validation_leaves_both_heads(self):
        original = self.git(self.repo, 'rev-parse', 'HEAD')
        other = self.other()
        remote = self.commit(other, 'broken')
        self.git(other, 'push', 'origin', 'main')
        self.command = [sys.executable, '-c', 'raise SystemExit(1)']
        with self.assertRaisesRegex(runtime.Refused, 'validation_failed'):
            self.sync()
        self.assertEqual(self.git(self.repo, 'rev-parse', 'HEAD'), original)
        self.assertEqual(self.git(self.remote, 'rev-parse', 'main'), remote)
        self.assertFalse((self.state / 'last-sync.json').exists())

    def test_uncommitted_files_are_preserved(self):
        (self.repo / 'tool.txt').write_text('AI edits')
        (self.repo / 'new.txt').write_text('new')
        with self.assertRaisesRegex(runtime.Refused, 'uncommitted_changes'):
            self.sync()
        self.assertEqual((self.repo / 'tool.txt').read_text(), 'AI edits')
        self.assertEqual((self.repo / 'new.txt').read_text(), 'new')

    def test_divergence_does_not_merge_or_reset(self):
        other = self.other()
        remote = self.commit(other, 'remote')
        self.git(other, 'push', 'origin', 'main')
        local = self.commit(self.repo, 'local')
        with self.assertRaisesRegex(runtime.Refused, 'diverged'):
            self.sync()
        self.assertEqual(self.git(self.repo, 'rev-parse', 'HEAD'), local)
        self.assertEqual(self.git(self.remote, 'rev-parse', 'main'), remote)

    def test_active_reader_defers_sync_in_another_process(self):
        with runtime.lease(self.repo):
            code = ('import importlib.util; s=importlib.util.spec_from_file_location("r", '+repr(str(SOURCE))+'); '
                    'r=importlib.util.module_from_spec(s); s.loader.exec_module(r); '
                    'r.sync('+repr(str(self.repo))+', '+repr(str(self.state))+')')
            p = subprocess.run([sys.executable, '-c', code], capture_output=True)
            self.assertNotEqual(p.returncode, 0)
            self.assertIn(b'tools_in_use', p.stderr)
        self.assertEqual(self.sync()['status'], 'unchanged')

    def test_multiple_readers_and_exclusive_writer(self):
        with runtime.lease(self.repo):
            with runtime.lease(self.repo):
                with self.assertRaises(runtime.Busy):
                    with runtime.lease(self.repo, exclusive=True):
                        self.fail('writer acquired during readers')
        with runtime.lease(self.repo, exclusive=True):
            with self.assertRaises(runtime.Busy):
                with runtime.lease(self.repo):
                    self.fail('reader acquired during writer')

    def test_validator_may_not_change_checked_tree(self):
        original = self.git(self.remote, 'rev-parse', 'main')
        self.commit(self.repo, 'new')
        self.command = [sys.executable, '-c', 'from pathlib import Path; Path("tool.txt").write_text("changed")']
        with self.assertRaisesRegex(runtime.Refused, 'validator_changed_tree'):
            self.sync()
        self.assertEqual(self.git(self.remote, 'rev-parse', 'main'), original)

    def test_local_change_during_validation_blocks_publication(self):
        original = self.git(self.remote, 'rev-parse', 'main')
        self.commit(self.repo, 'new')
        code = 'from pathlib import Path; Path('+repr(str(self.repo / 'tool.txt'))+').write_text("concurrent")'
        self.command = [sys.executable, '-c', code]
        with self.assertRaisesRegex(runtime.Refused, 'local_changed_during_validation'):
            self.sync()
        self.assertEqual(self.git(self.remote, 'rev-parse', 'main'), original)
        self.assertEqual((self.repo / 'tool.txt').read_text(), 'concurrent')

    def test_remote_race_is_rejected_without_force(self):
        other = self.other()
        remote = self.commit(other, 'racing remote')
        local = self.commit(self.repo, 'checked local')
        code = 'import subprocess; subprocess.run(["git", "-C", '+repr(str(other))+', "push", "origin", "main"], check=True)'
        self.command = [sys.executable, '-c', code]
        with self.assertRaises(runtime.Refused):
            self.sync()
        self.assertEqual(self.git(self.repo, 'rev-parse', 'HEAD'), local)
        self.assertEqual(self.git(self.remote, 'rev-parse', 'main'), remote)

    def test_wrong_remote_and_non_main_are_rejected(self):
        with self.assertRaisesRegex(runtime.Refused, 'unexpected_remote'):
            runtime.sync(self.repo, self.state)
        self.git(self.repo, 'checkout', '-b', 'work')
        with self.assertRaisesRegex(runtime.Refused, 'main_required'):
            self.sync()

    def test_wrapper_exposes_folder_and_pinned_revision(self):
        output = self.base / 'environment.txt'
        code = ('import os; from pathlib import Path; Path('+repr(str(output))+').write_text('
                'os.environ["DP_VERIFY_HOME"]+"\\n"+os.environ["DP_VERIFY_REVISION"])')
        self.assertEqual(runtime.run(self.repo, [sys.executable, '-c', code]), 0)
        self.assertEqual(output.read_text().splitlines(),
                         [str(self.repo), self.git(self.repo, 'rev-parse', 'HEAD')])


if __name__ == '__main__':
    unittest.main()
