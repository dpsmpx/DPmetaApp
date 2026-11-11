"""D-89: настоящие Git-репозитории и процессы, без сети и учётных данных."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

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

    def test_push_url_cannot_publish_to_another_repository(self):
        redirected = self.base / 'redirected.git'
        self.git(self.base, 'clone', '--bare', str(self.remote), str(redirected))
        before = self.git(redirected, 'rev-parse', 'main')
        self.git(self.repo, 'remote', 'set-url', '--push', 'origin', str(redirected))
        self.commit(self.repo, 'candidate')
        with self.assertRaisesRegex(runtime.Refused, 'unexpected_remote'):
            self.sync()
        self.assertEqual(self.git(redirected, 'rev-parse', 'main'), before)

    def test_validator_cannot_redirect_a_checked_publish(self):
        redirected = self.base / 'redirected.git'
        self.git(self.base, 'clone', '--bare', str(self.remote), str(redirected))
        self.commit(self.repo, 'candidate')
        args = ['git', '-C', str(self.repo), 'remote', 'set-url', '--push', 'origin', str(redirected)]
        self.command = [sys.executable, '-c', 'import subprocess; subprocess.run(' + repr(args) + ', check=True)']
        with self.assertRaisesRegex(runtime.Refused, 'unexpected_remote'):
            self.sync()

    def test_initial_clone_cannot_be_rewritten_by_global_configuration(self):
        redirected = self.base / 'redirected.git'
        self.git(self.base, 'clone', '--bare', str(self.remote), str(redirected))
        config = self.base / 'rewrite.gitconfig'
        self.git(self.base, 'config', '--file', str(config), 'url.' + str(redirected) + '.insteadOf', str(self.remote))
        target = self.base / 'new-tools'
        with patch.dict(os.environ, {'GIT_CONFIG_GLOBAL': str(config)}):
            with self.assertRaisesRegex(runtime.Refused, 'unexpected_remote'):
                runtime.install(target, self.state, str(self.remote), self.command)
        self.assertFalse(target.exists())

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

    def test_uncommitted_files_are_committed_checked_and_published(self):
        (self.repo / 'tool.txt').write_text('AI edits')
        (self.repo / 'new.txt').write_text('new')
        self.assertEqual(self.sync()['status'], 'published')
        self.assertEqual((self.repo / 'tool.txt').read_text(), 'AI edits')
        self.assertEqual((self.repo / 'new.txt').read_text(), 'new')
        self.assertTrue(runtime.clean(self.repo))
        self.assertEqual(self.git(self.remote, 'show', 'main:new.txt'), 'new')

    def test_failed_auto_commit_candidate_stays_local(self):
        remote = self.git(self.remote, 'rev-parse', 'main')
        (self.repo / 'tool.txt').write_text('unverified edit')
        self.command = [sys.executable, '-c', 'raise SystemExit(1)']
        with self.assertRaisesRegex(runtime.Refused, 'validation_failed'):
            self.sync()
        self.assertEqual(self.git(self.remote, 'rev-parse', 'main'), remote)
        self.assertEqual((self.repo / 'tool.txt').read_text(), 'unverified edit')
        self.assertNotEqual(self.git(self.repo, 'rev-parse', 'HEAD'), remote)
        self.assertTrue(runtime.clean(self.repo))

    def test_dirty_local_and_new_remote_are_not_overwritten(self):
        other = self.other()
        remote = self.commit(other, 'remote')
        self.git(other, 'push', 'origin', 'main')
        local = self.git(self.repo, 'rev-parse', 'HEAD')
        (self.repo / 'tool.txt').write_text('pending local')
        with self.assertRaisesRegex(runtime.Refused, 'local_remote_changes'):
            self.sync()
        self.assertEqual(self.git(self.repo, 'rev-parse', 'HEAD'), local)
        self.assertEqual((self.repo / 'tool.txt').read_text(), 'pending local')
        self.assertEqual(self.git(self.remote, 'rev-parse', 'main'), remote)

    def test_init_validates_before_exposing_folder(self):
        destination = self.base / 'installed'
        result = runtime.install(destination, self.state, str(self.remote), self.command)
        self.assertEqual(result['status'], 'installed')
        self.assertEqual(self.git(destination, 'rev-parse', 'HEAD'), result['revision'])
        self.assertEqual(self.git(destination, 'remote', 'get-url', 'origin'), str(self.remote))
        self.assertEqual((destination / 'tool.txt').read_text(), 'first')
        self.assertEqual(list(destination.parent.glob('.dpv-install-*')), [])

    def test_failed_init_never_activates_candidate(self):
        destination = self.base / 'installed'
        with self.assertRaisesRegex(runtime.Refused, 'validation_failed'):
            runtime.install(destination, self.state, str(self.remote), [sys.executable, '-c', 'raise SystemExit(1)'])
        self.assertFalse(destination.exists())
        self.assertEqual(list(destination.parent.glob('.dpv-install-*')), [])

    def test_init_does_not_replace_existing_directory(self):
        destination = self.base / 'installed'
        destination.mkdir()
        (destination / 'mine.txt').write_text('keep')
        with self.assertRaisesRegex(runtime.Refused, 'tools_already_exist'):
            runtime.install(destination, self.state, str(self.remote), self.command)
        self.assertEqual((destination / 'mine.txt').read_text(), 'keep')

    def test_two_installers_share_destination_lock(self):
        destination = self.base / 'installed'
        with runtime.file_lease(self.base / '.installed.install.lock', exclusive=True):
            with self.assertRaises(runtime.Busy):
                runtime.install(destination, self.state, str(self.remote), self.command)
        self.assertFalse(destination.exists())

    def test_unchanged_poll_avoids_repeating_full_validation(self):
        self.sync()
        with patch.object(runtime, 'validate', side_effect=AssertionError('needless validation')):
            self.assertEqual(self.sync()['status'], 'unchanged')

    def test_cached_validation_never_authorizes_publication(self):
        local = self.commit(self.repo, 'local')
        self.state.mkdir()
        runtime.write_state(self.state, 'last-validation.json', runtime.validation_key(local, self.command))
        with patch.object(runtime, 'validate', side_effect=runtime.Refused('validation_failed')):
            with self.assertRaisesRegex(runtime.Refused, 'validation_failed'):
                self.sync()
        self.assertNotEqual(self.git(self.remote, 'rev-parse', 'main'), local)

    def test_missing_or_corrupt_validation_stamp_is_not_trusted(self):
        self.sync()
        (self.state / 'last-validation.json').write_text('broken')
        with patch.object(runtime, 'validate', side_effect=runtime.Refused('validation_failed')):
            with self.assertRaisesRegex(runtime.Refused, 'validation_failed'):
                self.sync()

    def test_validator_has_timeout_and_candidate_is_removed(self):
        with patch.object(runtime, 'VALIDATION_TIMEOUT', 0.05):
            self.command = [sys.executable, '-c', 'import time; time.sleep(5)']
            with self.assertRaisesRegex(runtime.Refused, 'validation_timeout'):
                self.sync()
        self.assertEqual(list(self.state.glob('candidate-*')), [])

    def test_watch_retries_after_busy_and_error(self):
        events, waits = [], []
        def pause(seconds):
            waits.append(seconds)
            return len(waits) == 3
        with patch.object(runtime, 'sync', side_effect=[runtime.Busy(), runtime.Refused('git_failed'), {'status': 'unchanged'}]):
            runtime.watch(self.repo, self.state, 10, pause=pause, emit=events.append)
        self.assertEqual(events, [{'status': 'deferred', 'reason': 'tools_in_use'},
                                  {'status': 'refused', 'reason': 'git_failed'}, {'status': 'unchanged'}])
        self.assertEqual(waits, [10, 10, 10])

    def test_watch_rejects_nonpositive_interval(self):
        with self.assertRaisesRegex(runtime.Refused, 'positive_interval_required'):
            runtime.watch(self.repo, self.state, 0)

    def test_waiting_ai_session_does_not_block_update(self):
        argv = [sys.executable, str(SOURCE), '--repo', str(self.repo), 'session', '--',
                sys.executable, '-c', 'print("ready", flush=True); input()']
        process = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            self.assertEqual(process.stdout.readline().strip(), 'ready')
            self.assertEqual(self.sync()['status'], 'unchanged')
        finally:
            process.communicate('\n', timeout=20)
        self.assertEqual(process.returncode, 0)

    def test_dpv_gateway_holds_lease_only_while_tool_runs(self):
        dpv = self.repo / 'dpv'
        dpv.write_text('#!' + sys.executable + '\nprint("ready", flush=True)\ninput()\n')
        dpv.chmod(0o700)
        argv = [sys.executable, str(SOURCE), '--repo', str(self.repo), 'session', '--', 'dpv']
        process = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            self.assertEqual(process.stdout.readline().strip(), 'ready')
            with self.assertRaises(runtime.Busy):
                self.sync()
        finally:
            process.communicate('\n', timeout=20)
        self.assertEqual(process.returncode, 0)
        with runtime.lease(self.repo, exclusive=True):
            pass

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


    def test_validator_never_inherits_connected_account(self):
        revision = self.git(self.repo, 'rev-parse', 'HEAD')
        self.state.mkdir()
        command = [sys.executable, '-c',
                   'import os; assert "DPMETA_VERIFY_GITHUB_TOKEN" not in os.environ; print("clean")']
        with patch.object(runtime, 'ACCOUNT', ''.join(('synthetic', '-', 'account'))):
            runtime.validate(self.repo, revision, self.state, command)
        self.assertEqual((self.state / 'validation.log').read_text().strip(), 'clean')
        self.assertEqual(self.git(self.repo, 'config', 'user.name'), 'Synthetic')


if __name__ == '__main__':
    unittest.main()
