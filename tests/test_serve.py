"""Живой режим на синтетических клонах (D-12, F-43…F-46, T-03)."""

import io
import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path

from tests.fixture import base_source, build_ecosystem, model_from, utc
from tools.dpvis import formats, serve

NOW = utc("2026-10-05T12:00:00")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class ServeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        tmp = Path(cls.tmp.name)
        cls.clones = build_ecosystem(tmp)
        # корень сервера: свои viewer/ и data/, рядом — то, что отдавать нельзя
        cls.base = tmp / "site"
        (cls.base / "viewer").mkdir(parents=True)
        (cls.base / "viewer" / "index.html").write_text("<!doctype html><title>t</title>", encoding="utf-8")
        (cls.base / "viewer" / ".hidden.js").write_text("x", encoding="utf-8")
        (cls.base / "data" / "state").mkdir(parents=True)
        (cls.base / "data" / "model.json").write_text("{}", encoding="utf-8")
        (cls.base / "tools").mkdir()
        (cls.base / "tools" / "secret.py").write_text("print()", encoding="utf-8")
        cls.state = cls.base / "data" / "state" / "latest.json"
        cls.log = io.StringIO()
        cls.server = serve.make_server("127.0.0.1", 0, cls.base, cls.state, stream=cls.log)
        cls.url = "http://127.0.0.1:%d" % cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.tmp.cleanup()

    def rebuilder(self, load=None):
        model = model_from(base_source())
        return serve.Rebuilder(load or (lambda: model), self.clones, self.state, days=30, online=False,
                               fetch=False, now=lambda: NOW, stream=self.log)

    def get(self, path, method="GET"):
        opener = urllib.request.build_opener(NoRedirect)
        req = urllib.request.Request(self.url + path, method=method)
        try:
            with opener.open(req, timeout=10) as r:
                return r.status, r.read(), dict(r.headers)
        except urllib.error.HTTPError as e:
            return e.code, e.read(), dict(e.headers)

    def test_rebuild_writes_valid_state_and_server_serves_it(self):
        self.assertTrue(self.rebuilder().once())
        state = json.loads(self.state.read_text(encoding="utf-8"))
        self.assertEqual(formats.validate_document(state), [])
        self.assertEqual(state["as_of"], "2026-10-05T12:00:00Z")
        code, body, headers = self.get(serve.STATE_URL)
        self.assertEqual(code, 200)
        self.assertEqual(json.loads(body), state)
        self.assertEqual(headers.get("Cache-Control"), "no-store")
        self.assertIn("script-src 'self'", headers.get("Content-Security-Policy", ""))
        self.assertFalse(self.state.with_name("latest.json.tmp").exists(), "временный файл заменён")

    def test_failed_rebuild_keeps_previous_state(self):
        self.assertTrue(self.rebuilder().once())
        before = self.state.read_bytes()

        def broken():
            raise RuntimeError("модель не собралась")

        self.assertFalse(self.rebuilder(broken).once())
        self.assertEqual(self.state.read_bytes(), before)
        self.assertIn("остаётся прежний", self.log.getvalue())

    def test_only_viewer_and_data(self):
        self.assertEqual(self.get("/")[0], 302)
        self.assertEqual(self.get("/")[2].get("Location"), "/viewer/")
        self.assertEqual(self.get("/viewer/")[0], 200)
        self.assertEqual(self.get("/data/model.json")[0], 200)
        for bad in ("/tools/secret.py", "/viewer/../tools/secret.py", "/viewer/%2e%2e/tools/secret.py",
                    "/data/", "/viewer/.hidden.js", "/data/../../etc/passwd"):
            self.assertEqual(self.get(bad)[0], 404, bad)

    def test_read_only_methods(self):
        self.assertEqual(self.get("/viewer/index.html", "HEAD")[0], 200)
        for method in ("POST", "PUT", "DELETE"):
            self.assertEqual(self.get("/data/model.json", method)[0], 405, method)

    def test_loop_once_when_every_zero(self):
        stop = threading.Event()
        rb = self.rebuilder()
        serve.loop(rb, 0, stop)
        self.assertEqual(rb.last_ok, "2026-10-05T12:00:00Z")

    def test_host_warning(self):
        self.assertIsNone(serve.host_warning("127.0.0.1"))
        self.assertIsNone(serve.host_warning("::1"))
        self.assertIsNone(serve.host_warning("localhost"))
        self.assertIn("0.0.0.0", serve.host_warning("0.0.0.0"))


if __name__ == "__main__":
    unittest.main()
