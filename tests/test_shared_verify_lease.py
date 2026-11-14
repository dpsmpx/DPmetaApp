"""Межпроцессный контракт lease; тот же файл запускается Windows Python."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import shlex
import sys
import tempfile
from threading import Event
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('lease_runtime', ROOT / 'tools/shared_verify/runtime.py')
runtime = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runtime)


def wait_for(predicate, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        Event().wait(0.02)
    raise AssertionError('дочерний процесс не завершил ожидаемый шаг')


def child_main(args):
    action, folder = args[0], Path(args[1])
    lock = folder / 'shared.lock'
    if action == 'probe':
        try:
            with runtime.file_lease(lock, exclusive=args[2] == 'exclusive'):
                return 0
        except runtime.Busy:
            return 75
    if action == 'hold-child':
        (folder / 'child.pid').write_text(str(os.getpid()))
        wait_for(lambda: (folder / 'release').exists())
        return 0
    if action == 'wrapper':
        exclusive = len(args) > 2 and args[2] == 'exclusive'
        with runtime.file_lease(lock, exclusive=exclusive) as descriptor:
            return runtime.guarded_process(
                [sys.executable, '-X', 'utf8', str(Path(__file__)), '--child', 'hold-child', str(folder)],
                runtime.clean_environment(), descriptor, lock, exclusive=exclusive)
    if action == 'hold-owner':
        with runtime.file_lease(lock, exclusive=True):
            (folder / 'ready').write_text('locked')
            wait_for(lambda: (folder / 'release').exists())
        return 0
    if action == 'blocking-reader':
        (folder / 'attempt').write_text('started')
        with runtime.file_lease(lock, blocking=True):
            (folder / 'ready').write_text('locked')
        return 0
    raise AssertionError(action)


class SharedVerifyLeaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='dp lease ')
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.lock = self.folder / 'shared.lock'

    def child(self, *args):
        return subprocess.Popen([sys.executable, '-X', 'utf8', str(Path(__file__)),
                                 '--child', *args[:1], str(self.folder), *args[1:]],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def probe(self, mode):
        with self.child('probe', mode) as child:
            output, error = child.communicate(timeout=30)
            self.assertIn(child.returncode, (0, 75), (output, error))
            return child.returncode

    def test_readers_share_but_writer_waits_for_every_reader(self):
        with runtime.file_lease(self.lock):
            self.assertEqual(self.probe('shared'), 0)
            self.assertEqual(self.probe('exclusive'), 75)
        self.assertEqual(self.probe('exclusive'), 0)
        with runtime.file_lease(self.lock, exclusive=True):
            self.assertEqual(self.probe('shared'), 75)
            self.assertEqual(self.probe('exclusive'), 75)

    def test_blocking_reader_resumes_after_writer_releases(self):
        with runtime.file_lease(self.lock, exclusive=True):
            child = self.child('blocking-reader')
            self.addCleanup(lambda: child.poll() is None and child.kill())
            wait_for(lambda: (self.folder / 'attempt').exists())
            Event().wait(0.1)
            self.assertFalse((self.folder / 'ready').exists())
        with child:
            output, error = child.communicate(timeout=30)
            self.assertEqual(child.returncode, 0, (output, error))
        self.assertTrue((self.folder / 'ready').exists())

    def test_crashed_owner_without_child_releases_lease(self):
        with self.child('hold-owner') as child:
            wait_for(lambda: (self.folder / 'ready').exists())
            self.assertEqual(self.probe('exclusive'), 75)
            child.kill()
            child.communicate(timeout=30)
        self.assertEqual(self.probe('exclusive'), 0)

    def test_running_tool_keeps_lease_after_wrapper_is_killed(self):
        with self.child('wrapper') as wrapper:
            try:
                wait_for(lambda: (self.folder / 'child.pid').exists())
                self.assertEqual(self.probe('exclusive'), 75)
                wrapper.kill()
                wrapper.wait(timeout=30)
                # Живой инструмент всё ещё использует папку: обновление запрещено.
                self.assertEqual(self.probe('exclusive'), 75)
                self.assertEqual(self.probe('shared'), 0)
            finally:
                (self.folder / 'release').write_text('finish')
                if wrapper.poll() is None:
                    wrapper.wait(timeout=30)
                wrapper.communicate(timeout=30)
        wait_for(lambda: self.probe('exclusive') == 0)

    def test_running_editor_blocks_readers_after_wrapper_is_killed(self):
        with self.child('wrapper', 'exclusive') as wrapper:
            try:
                wait_for(lambda: (self.folder / 'child.pid').exists())
                wrapper.kill()
                wrapper.wait(timeout=30)
                self.assertEqual(self.probe('shared'), 75)
                self.assertEqual(self.probe('exclusive'), 75)
            finally:
                (self.folder / 'release').write_text('finish')
                if wrapper.poll() is None:
                    wrapper.wait(timeout=30)
                wrapper.communicate(timeout=30)
        wait_for(lambda: self.probe('shared') == 0 and self.probe('exclusive') == 0)

    def test_marker_modes_and_legacy_are_fail_closed_for_incompatible_requests(self):
        folder = self.lock.with_name(self.lock.name + '.users')
        folder.mkdir()
        marker = folder / ('f' * 32 + '.json')
        event = runtime.EVENT_PREFIX + 'f' * 32
        with patch.object(runtime, 'event_exists', return_value=True):
            for value, blocks_reader in [({'event': event}, True),
                                         ({'event': event, 'exclusive': True}, True),
                                         ({'event': event, 'exclusive': False}, False)]:
                with self.subTest(value=value):
                    marker.write_text(json.dumps(value))
                    self.assertEqual(runtime.windows_users(self.lock, False), blocks_reader)
                    self.assertTrue(runtime.windows_users(self.lock, True))
            for mode in [None, 0, 1, 'shared']:
                marker.write_text(json.dumps({'event': event, 'exclusive': mode}))
                for exclusive in [False, True]:
                    with self.assertRaises(runtime.Refused):
                        runtime.windows_users(self.lock, exclusive)

    def test_unreadable_marker_directory_cannot_become_a_free_lease(self):
        for exclusive in [False, True]:
            with patch.object(Path, 'iterdir', side_effect=PermissionError('denied')):
                with self.assertRaisesRegex(runtime.Refused, 'tools_lease_unreadable'):
                    runtime.windows_users(self.lock, exclusive)
        self.assertFalse(runtime.windows_users(self.lock, True))  # только отсутствие разрешено

    def test_python_launcher_receives_arguments_without_shell_expansion(self):
        script = self.folder / 'dpv'
        script.write_text('#!' + sys.executable + '\n' +
                          'import json,sys\nfrom pathlib import Path\n' +
                          'Path(__file__).with_name("argv.json").write_text(json.dumps(sys.argv[1:]))\n')
        script.chmod(0o700)
        args = ['run', 'full', 'quote" & pipe| percent% bang! dollar$ tick`', 'путь с пробелом']
        with runtime.file_lease(self.lock) as descriptor:
            code = runtime.guarded_process(runtime.native_tool_command([str(script), *args]),
                                           runtime.clean_environment(), descriptor, self.lock)
        self.assertEqual(code, 0)
        self.assertEqual(json.loads((self.folder / 'argv.json').read_text()), args)

    @unittest.skipUnless(os.name == 'nt', 'launcher Git Bash на Windows')
    def test_mingw_git_location_finds_git_bash_and_quotes_every_argument(self):
        git = self.folder / 'Git' / 'mingw64' / 'bin' / 'git.exe'
        git.parent.mkdir(parents=True)
        git.touch()
        bash = self.folder / 'Git' / 'bin' / 'bash.exe'
        bash.parent.mkdir(parents=True)
        bash.touch()
        args = ['claude', '--print', 'quote\' & | % ! $ `\nкод']
        with patch.object(runtime.shutil, 'which', return_value=str(git)):
            command = runtime.session_command(args)
        self.assertEqual(Path(command[0]), bash)
        self.assertEqual(command[1:4], ['--noprofile', '--norc', '-c'])
        self.assertEqual(shlex.split(command[4]), ['exec', *args])

    @unittest.skipUnless(os.name == 'nt', 'маркеры жизни Windows')
    def test_corrupt_liveness_marker_refuses_update_and_is_preserved(self):
        folder = self.lock.with_name(self.lock.name + '.users')
        folder.mkdir()
        marker = folder / ('f' * 32 + '.json')
        marker.write_text('{"event":"unknown"}')
        with self.assertRaisesRegex(runtime.Refused, 'invalid_tools_lease'):
            with runtime.file_lease(self.lock, exclusive=True):
                self.fail('повреждённый маркер признан безопасным')
        self.assertTrue(marker.exists())
        marker.unlink()
        with runtime.file_lease(self.lock, exclusive=True):
            pass


if __name__ == '__main__':
    if sys.argv[1:2] == ['--child']:
        raise SystemExit(child_main(sys.argv[2:]))
    unittest.main()
