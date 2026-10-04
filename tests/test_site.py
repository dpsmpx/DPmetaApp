"""Сайт: предпросмотр и сервер (П2-09), доступность (П2-10), значок (П2-11), _headers (П2-12), sitemap (П2-13)."""
from __future__ import annotations

import re
import shutil
import threading
import unittest
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from unittest import mock

from tools.cs.common import ROOT
from tools.cs.core import textdoc
from tools.cs.core.content import approval_state, find
from tools.cs.platform_checks import page_a11y, page_csp
from tools.cs.site.build import build
from tools.cs.site.preview import PAGE_DIR, preview
from tools.cs.site.serve import _Handler, server
from tests.platform_fixture import approve, make_repo

SM = "{http://www.sitemaps.org/schemas/sitemap/0.9}"
XHTML = "{http://www.w3.org/1999/xhtml}"
IMG = ROOT / "content/items/CI-0001-personal-presentation/exports/A-ru-telegram_post.jpg"


def with_media(repo, alt: str | None):
    """Вариант website.ru фикстуры с изображением (и alt, если задан)."""
    v = find(repo, "CI-0101").variants()["website.ru"]
    (v.item.dir / "exports").mkdir(exist_ok=True)
    shutil.copyfile(IMG, v.item.dir / "exports/cover.jpg")
    v.doc.meta["media"] = ["exports/cover.jpg"]
    if alt is not None:
        v.doc.meta["media_alt"] = [alt]
    textdoc.save(v.doc, v.path)
    repo.reset()


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



class Accessibility(unittest.TestCase):
    def test_all_pages_pass_a11y_and_csp(self):
        repo = make_repo(git=False)
        with_media(repo, "Обложка: {{fact:GH-NAME}}, {{fact:GH-TECH}}")
        out = repo.path("build", "s")
        rep = build(repo, "staging", out)
        self.assertEqual(rep.errors, [])
        pages = list(out.rglob("*.html"))
        self.assertGreater(len(pages), 5)
        for page in pages:
            text = page.read_text(encoding="utf-8")
            self.assertEqual(page_a11y(text) + page_csp(text), [], page.relative_to(out))
        post = (out / "news/test-news/index.html").read_text(encoding="utf-8")
        self.assertIn('alt="Обложка: Galdhold, C++20 и Vulkan"', post)
        self.assertIn('aria-current="page"', post)
        self.assertIn('<a class="skip" href="#main">К содержимому</a>', post)

    def test_media_without_alt_is_an_error(self):
        repo = make_repo(git=False)
        with_media(repo, None)
        rep = build(repo, "staging", repo.path("build", "s"))
        self.assertTrue(any("нет media_alt" in e for e in rep.errors), rep.errors)

    def test_alt_is_part_of_what_the_author_approves(self):
        repo = make_repo(git=False)
        with_media(repo, "Обложка")
        approve(repo, "CI-0101", "website.ru")
        v = find(repo, "CI-0101").variants()["website.ru"]
        v.doc.meta["media_alt"] = ["Другая обложка"]
        textdoc.save(v.doc, v.path)
        repo.reset()
        self.assertEqual(approval_state(find(repo, "CI-0101").variants()["website.ru"]), "invalid")

    def test_checks_catch_violations(self):
        bad = ('<html lang="ru"><body><h1>a</h1><h1>b</h1><img src="/x.png"><img src="/y.png" alt="">'
               '<nav><a href="/" class="current">x</a></nav><p style="color:red" onclick="x()">z</p>'
               '<script>alert(1)</script></body></html>')
        a11y = " ".join(page_a11y(bad))
        for part in ("h1 — 2", "без alt", "к содержимому", "aria-current"):
            self.assertIn(part, a11y)
        csp = " ".join(page_csp(bad))
        for part in ("скрипт", "стиль", "on…="):
            self.assertIn(part, csp)
        self.assertEqual(page_csp('<script type="application/ld+json">{}</script>'), [])


class HostFiles(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repo = make_repo(git=False)
        cls.st = cls.repo.path("build", "st")
        cls.pr = cls.repo.path("build", "pr")
        build(cls.repo, "staging", cls.st)
        build(cls.repo, "production", cls.pr)

    def test_favicon_from_brand_tokens(self):
        svg = ET.parse(self.st / "favicon.svg").getroot()
        fills = {e.get("fill") for e in svg.iter()} | {e.get("stroke") for e in svg.iter()}
        self.assertTrue({"#111214", "#EEEEEC", "#FF7A59"} <= fills)
        self.assertIn('<link rel="icon" href="/favicon.svg" type="image/svg+xml">',
                      (self.st / "index.html").read_text(encoding="utf-8"))

    def test_security_headers(self):
        for out in (self.st, self.pr):
            h = (out / "_headers").read_text(encoding="utf-8")
            self.assertTrue(h.startswith("/*\n"))
            for name in ("Content-Security-Policy: default-src 'self'", "X-Content-Type-Options: nosniff",
                         "Referrer-Policy:", "Permissions-Policy:"):
                self.assertIn(name, h)
        self.assertIn("X-Robots-Tag: noindex", (self.st / "_headers").read_text(encoding="utf-8"))
        self.assertNotIn("X-Robots-Tag", (self.pr / "_headers").read_text(encoding="utf-8"))

    def test_sitemap_lastmod_and_hreflang(self):
        root = ET.parse(self.st / "sitemap.xml").getroot()
        urls = {u.find(f"{SM}loc").text: u for u in root.findall(f"{SM}url")}
        gh = urls["http://localhost:8000/projects/galdhold/"]
        facts = self.repo.facts()
        newest = max(str(e["date"]) for fid in ("GH-TECH", "GH-LOC", "GH-GENRE") for e in facts[fid]["evidence"])
        self.assertGreaterEqual(gh.find(f"{SM}lastmod").text, newest)
        alts = {l.get("hreflang"): l.get("href") for l in gh.findall(f"{XHTML}link")}
        self.assertEqual(alts, {"ru": "http://localhost:8000/projects/galdhold/",
                                "en": "http://localhost:8000/en/projects/galdhold/",
                                "x-default": "http://localhost:8000/projects/galdhold/"})
        en = urls["http://localhost:8000/en/projects/galdhold/"]
        self.assertEqual({l.get("hreflang") for l in en.findall(f"{XHTML}link")}, {"ru", "en", "x-default"})
        self.assertNotIn("http://localhost:8000/news/test-news/", urls, "черновик в sitemap не попадает")
        self.assertEqual(urls["http://localhost:8000/news/"].find(f"{SM}lastmod").text, "2026-10-04", "дата записи в ленте")
        prod = {u.find(f"{SM}loc").text: u for u in ET.parse(self.pr / "sitemap.xml").getroot().findall(f"{SM}url")}
        self.assertIsNone(prod["https://dpsmpx.com/news/"].find(f"{SM}lastmod"), "записей нет — lastmod не выдумывается")


if __name__ == "__main__":
    unittest.main()
