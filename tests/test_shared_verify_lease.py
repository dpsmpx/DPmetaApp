"""Межпроцессный контракт lease; тот же файл запускается Windows Python."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from threading import Event
import time
import unittest

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
        with runtime.file_lease(lock) as descriptor:
            return runtime.guarded_process(
                [sys.executable, '-X', 'utf8', str(Path(__file__)), '--child', 'hold-child', str(folder)],
                runtime.clean_environment(), descriptor, lock)
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
