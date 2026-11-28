"""Реальные sh-процессы, детерминированный транспорт: сеть не требуется."""
import hashlib
import fcntl
import os
from pathlib import Path
import signal
import selectors
import subprocess
import sys
import tempfile
import time
import unittest

LIBRARY = Path(sys.argv.pop(1)).resolve()


class CacheTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.payload = self.root / "payload"
        self.payload.write_bytes(b"verified component\n")
        self.digest = hashlib.sha256(self.payload.read_bytes()).hexdigest()
        self.calls = self.root / "calls"
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        DPAI_DOWNLOAD_CACHE=str(self.root / "persistent"),
                        TEST_PAYLOAD=str(self.payload), TEST_CALLS=str(self.calls))
        self.transport()

    def transport(self, before=""):
        curl = self.bin / "curl"
        curl.write_text('#!/bin/sh\nset -eu\nprintf "call\\n" >> "$TEST_CALLS"\n' + before + '\n' +
                        'while test "$#" -gt 0; do if test "$1" = -o; then cp "$TEST_PAYLOAD" "$2"; exit; fi; shift; done\nexit 2\n')
        curl.chmod(0o700)

    def args(self, destination="out", digest=None, algorithm="sha256", url="https://example.invalid/component"):
        return ["/bin/sh", "-c", '. "$1"; dpai_fetch "$2" "$3" "$4" component "$5"',
                "test", str(LIBRARY), url, digest or self.digest, str(self.root / destination), algorithm]

    def fetch(self, **kwargs):
        return subprocess.run(self.args(**kwargs), env=self.env, capture_output=True, text=True, timeout=8)

    def count(self):
        return len(self.calls.read_text().splitlines()) if self.calls.exists() else 0

    def test_second_run_works_without_transport(self):
        self.assertEqual(0, self.fetch().returncode)
        self.transport('exit 47')
        result = self.fetch(destination="second")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("DPAI_PROGRESS cache", result.stdout)
        self.assertEqual(1, self.count())
        self.assertEqual(self.payload.read_bytes(), (self.root / "second").read_bytes())

    def test_download_survives_a_failed_destination_write(self):
        self.assertNotEqual(0, self.fetch(destination="missing/output").returncode)
        self.transport('exit 47')
        (self.root / "missing").mkdir()
        result = self.fetch(destination="missing/output")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(1, self.count())

    def test_corrupt_blob_is_not_reused(self):
        self.assertEqual(0, self.fetch().returncode)
        blob = self.root / "persistent" / ("sha256-" + self.digest)
        blob.write_bytes(b"incomplete")
        self.assertEqual(0, self.fetch(destination="repaired").returncode)
        self.assertEqual(2, self.count())
        self.assertEqual(self.payload.read_bytes(), blob.read_bytes())

    def test_failed_checksum_and_transfer_never_publish(self):
        bad = "00" * 32
        self.assertNotEqual(0, self.fetch(digest=bad).returncode)
        self.assertFalse((self.root / "persistent" / ("sha256-" + bad)).exists())
        self.assertFalse((self.root / "out").exists())
        self.transport('exit 47')
        self.assertNotEqual(0, self.fetch().returncode)
        self.assertFalse((self.root / "persistent" / ("sha256-" + self.digest)).exists())
        self.assertFalse(list((self.root / "persistent").glob("*.part.*")))
        for lock in (self.root / "persistent").glob("*.lock"):
            with lock.open("a") as descriptor:
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def test_versions_and_sha1_platforms_do_not_mix(self):
        self.assertEqual(0, self.fetch().returncode)
        self.payload.write_bytes(b"next version")
        newer = hashlib.sha256(self.payload.read_bytes()).hexdigest()
        self.assertEqual(0, self.fetch(destination="new", digest=newer).returncode)
        platform = hashlib.sha1(self.payload.read_bytes()).hexdigest()
        self.assertEqual(0, self.fetch(destination="platform", digest=platform, algorithm="sha1").returncode)
        self.assertEqual(3, self.count())

    def test_https_and_digest_validation_precede_transport(self):
        self.assertNotEqual(0, self.fetch(url="http://example.invalid/component").returncode)
        self.assertNotEqual(0, self.fetch(digest="invalid").returncode)
        self.assertEqual(0, self.count())

    def wait_for(self, path):
        deadline = time.monotonic() + 4
        while not path.exists():
            if time.monotonic() > deadline:
                self.fail("transport did not start")
            time.sleep(0.01)

    def test_concurrent_callers_share_one_verified_transfer(self):
        gate = self.root / "gate"
        started = self.root / "started"
        self.env.update(TEST_GATE=str(gate), TEST_STARTED=str(started))
        self.transport('touch "$TEST_STARTED"\nwhile ! test -e "$TEST_GATE"; do sleep 0.05; done')
        first = subprocess.Popen(self.args("first"), env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        second = None
        try:
            self.wait_for(started)
            second = subprocess.Popen(self.args("second"), env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            with selectors.DefaultSelector() as selector:
                selector.register(second.stdout, selectors.EVENT_READ)
                self.assertTrue(selector.select(timeout=4), "second caller did not report waiting")
                self.assertIn("DPAI_PROGRESS waiting", second.stdout.readline())
            gate.touch()
            out, err = first.communicate(timeout=8)
            self.assertEqual(0, first.returncode, out + err)
            out, err = second.communicate(timeout=8)
            self.assertEqual(0, second.returncode, out + err)
            self.assertEqual(1, self.count())
            self.assertEqual((self.root / "first").read_bytes(), (self.root / "second").read_bytes())
        finally:
            for process in [first, second]:
                if process is not None and process.poll() is None:
                    process.kill()
                    process.wait()

    def test_sigkill_leaves_no_ready_blob_and_dead_lock_recovers(self):
        started = self.root / "started"
        self.env["TEST_STARTED"] = str(started)
        self.transport('while test "$#" -gt 0; do if test "$1" = -o; then printf incomplete > "$2"; break; fi; shift; done\ntouch "$TEST_STARTED"\nwhile :; do sleep 1; done')
        process = subprocess.Popen(self.args(), env=self.env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        try:
            self.wait_for(started)
        finally:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
        self.assertFalse((self.root / "persistent" / ("sha256-" + self.digest)).exists())
        self.transport()
        result = self.fetch()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(self.payload.read_bytes(), (self.root / "out").read_bytes())
        self.assertFalse(list((self.root / "persistent").glob("*.part.*")))


if __name__ == "__main__":
    unittest.main()
