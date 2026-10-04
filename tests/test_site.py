"""Сайт: предпросмотр и локальный сервер (П2-09)."""
from __future__ import annotations

import re
import threading
import unittest
import urllib.error
import urllib.request
from unittest import mock

from tools.cs.site.preview import PAGE_DIR, preview
from tools.cs.site.serve import _Handler, server
from tests.platform_fixture import make_repo


class Preview(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repo = make_repo(git=False)
        cls.page = preview(cls.repo, "CI-0101")
        cls.root = cls.page.parent.parent

    def test_site_at_root_preview_page_apart(self):
        self.assertEqual(self.page, self.root / PAGE_DIR / "index.html")
        self.assertTrue((self.root / "index.html").is_file(), "сайт — в корне сборки предпросмотра")
        self.assertTrue((self.root / "style.css").is_file())
        self.assertFalse((self.root / "site").exists())

    def test_iframes_point_to_built_pages(self):
        html = self.page.read_text(encoding="utf-8")
        srcs = re.findall(r'<iframe src="([^"]+)"', html)
        self.assertEqual(srcs, ["/news/test-news/"])
        for src in srcs:
            self.assertTrue((self.root / src.lstrip("/") / "index.html").is_file(), src)
        # страницы сайта ссылаются на стили от корня — тот же корень, что у сервера
        self.assertIn('href="/style.css"', (self.root / "news/test-news/index.html").read_text(encoding="utf-8"))

    def test_card_media_next_to_preview_page(self):
        html = self.page.read_text(encoding="utf-8")
        for src in re.findall(r'<img src="([^"/][^"]*)"', html):
            self.assertTrue((self.page.parent / src).is_file(), src)

    @mock.patch.object(_Handler, "log_request", lambda *a, **k: None)
    def test_server_serves_root_and_404(self):
        srv = server(self.root, 0)
        port = srv.server_address[1]
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        try:
            base = f"http://127.0.0.1:{port}"
            with urllib.request.urlopen(f"{base}/{PAGE_DIR}/") as r:
                self.assertIn("Предпросмотр CI-0101", r.read().decode("utf-8"))
            with urllib.request.urlopen(f"{base}/style.css") as r:
                self.assertEqual(r.status, 200)
            with self.assertRaises(urllib.error.HTTPError) as cm:
                urllib.request.urlopen(f"{base}/no-such-page/")
            self.assertEqual(cm.exception.code, 404)
            self.assertIn("Страница не найдена", cm.exception.read().decode("utf-8"))
        finally:
            srv.shutdown()
            srv.server_close()


if __name__ == "__main__":
    unittest.main()
