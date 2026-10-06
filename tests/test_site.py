"""Сайт: предпросмотр и сервер (П2-09), доступность (П2-10), значок (П2-11), _headers (П2-12), sitemap (П2-13),
имени автора на сайте нет (Д37, Д40)."""
from __future__ import annotations

import json
import os
import re
import shutil
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest import mock

import yaml

from tools.cs.common import ROOT
from tools.cs.core import textdoc
from tools.cs.core.content import approval_state, find
from tools.cs.platform_checks import page_a11y, page_csp
from tools.cs.site.build import SiteBuild, build
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
            with self.assertRaises(urllib.error.HTTPError) as cm:
                urllib.request.urlopen(f"{base}/en/no-such-page/?x=1")
            self.assertEqual(cm.exception.code, 404)
            self.assertIn("Page not found", cm.exception.read().decode("utf-8"), "в /en/ — английская 404 (П2-18)")
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


class AuthorName(unittest.TestCase):
    """Д37, Д40: на сайте только dpsmpx; имени нет в данных, а слово из списка личных слов — ошибка сборки."""

    WORD = "zqxprivat"  # заглушка, не имя (Д40: в тестах нет ни настоящих, ни выдуманных имён)

    def built(self, repo, env="staging", words: str | None = None):
        out = Path(tempfile.mkdtemp(prefix="cs-name-")) / env
        lst = Path(tempfile.mkdtemp(prefix="cs-words-")) / "private-words.txt"
        if words is not None:
            lst.write_text(words, encoding="utf-8")
        with mock.patch.dict(os.environ, {"DPSMPX_PRIVATE_WORDS": str(lst)}):
            sb = SiteBuild(repo, env)
            return sb, sb.build(out), out

    def test_only_dpsmpx(self):
        repo = make_repo(git=False)
        self.assertNotIn("AU-NAME", repo.facts())
        for env in ("staging", "production"):
            sb, rep, out = self.built(repo, env, words=f"# список\n{self.WORD}\n")
            self.assertEqual(rep.errors, [], env)
        home = (out / "index.html").read_text(encoding="utf-8")
        self.assertIn("<h1>dpsmpx</h1>", home)
        person = next(json.loads(m) for m in re.findall(r'<script type="application/ld\+json">(.*?)</script>', home)
                      if '"Person"' in m)
        self.assertEqual(person["name"], "dpsmpx")
        self.assertNotIn("alternateName", person)
        self.assertEqual(json.loads((out / "feed.json").read_text(encoding="utf-8"))["authors"][0]["name"], "dpsmpx")

    def test_private_word_is_a_build_error_and_not_printed(self):
        repo = make_repo(git=False)
        v = find(repo, "CI-0101").variants()["website.ru"]
        v.doc.body = f"Автор — {self.WORD.upper()}.\n\n" + v.doc.body
        textdoc.save(v.doc, v.path)
        repo.reset()
        _, rep, _ = self.built(repo, words=self.WORD + "\n")
        self.assertTrue(any("news/test-news/index.html: слово из списка личных слов" in e for e in rep.errors),
                        rep.errors)
        self.assertFalse([e for e in rep.errors if self.WORD in e.lower()], "слово из списка не печатается")

    def test_production_warns_without_list(self):
        repo = make_repo(git=False)
        _, rep, _ = self.built(repo, "production")
        self.assertEqual(rep.errors, [])
        self.assertTrue(any("списка личных слов нет" in w for w in rep.warnings), rep.warnings)
        _, rep, _ = self.built(repo, "staging")
        self.assertFalse(any("списка личных слов нет" in w for w in rep.warnings))

    def test_real_name_switch_is_gone(self):
        import jsonschema
        schema = json.loads((ROOT / "schemas/site.schema.json").read_text(encoding="utf-8"))
        cfg = yaml.safe_load((ROOT / "data/website/site.yaml").read_text(encoding="utf-8"))
        jsonschema.validate(cfg, schema)
        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate({**cfg, "real_name": True}, schema)


class Ecosystem(unittest.TestCase):
    """Д43: раздел «Экосистема» — комплект DPvisualisation, свой CSP каталога, роли из реестра фактов."""

    @staticmethod
    def repo(hosting: str | None = None, mutate=None):
        repo = make_repo(git=False)
        p = repo.path("data", "website", "site.yaml")
        cfg = yaml.safe_load(p.read_text(encoding="utf-8"))
        cfg["ecosystem"] = yaml.safe_load((ROOT / "data/website/site.yaml").read_text(encoding="utf-8"))["ecosystem"]
        if hosting:
            cfg["hosting"] = hosting
        if mutate:
            mutate(cfg, repo)
        p.write_text(yaml.safe_dump(cfg, allow_unicode=True), encoding="utf-8")
        repo.reset()
        return repo

    @staticmethod
    def build(repo, env="staging"):
        out = Path(tempfile.mkdtemp(prefix="cs-eco-")) / env
        sb = SiteBuild(repo, env)
        return sb, sb.build(out), out

    @classmethod
    def setUpClass(cls):
        cls.sb, cls.rep, cls.out = cls.build(cls.repo())

    @staticmethod
    def reseal(repo, name: str, edit):
        """Правка файла комплекта с пересчётом MANIFEST — как будто так собрал DPvisualisation."""
        import hashlib
        kit = repo.path("website", "ecosystem")
        edit(kit / name)
        man = json.loads((kit / "MANIFEST.json").read_text(encoding="utf-8"))
        man["files"][name] = hashlib.sha256((kit / name).read_bytes()).hexdigest()
        (kit / "MANIFEST.json").write_text(json.dumps(man), encoding="utf-8")

    def test_page_nav_and_script_only_here(self):
        self.assertEqual(self.rep.errors, [])
        page = (self.out / "ecosystem/index.html").read_text(encoding="utf-8")
        self.assertIn('<script src="/ecosystem/dpvis.js" defer></script>', page)
        self.assertIn('<link rel="stylesheet" href="/ecosystem/dpvis.css">', page)
        self.assertIn('data-src="/ecosystem/dpvis-data.json"', page)
        self.assertIn("Система по слоям", page, "без JavaScript — та же система списком")
        for name in ("dpvis.js", "dpvis.css", "dpvis-data.json"):
            self.assertTrue((self.out / "ecosystem" / name).is_file(), name)
        self.assertIn('href="/ecosystem/"', (self.out / "index.html").read_text(encoding="utf-8"))
        self.assertNotIn("/ecosystem/", (self.out / "en/index.html").read_text(encoding="utf-8"), "раздел только на русском")
        others = [f for f in self.out.rglob("*.html") if "<script src=" in f.read_text(encoding="utf-8")]
        self.assertEqual([f.relative_to(self.out).as_posix() for f in others], ["ecosystem/index.html"])
        self.assertEqual(page_csp(page, ("/ecosystem/dpvis.js",)), [])
        self.assertTrue(page_csp(page), "без разрешения каталога скрипт — нарушение")
        api = json.loads((self.out / "api/v1/index.json").read_text(encoding="utf-8"))
        self.assertEqual(api["resources"]["ecosystem"], "/ecosystem/dpvis-data.json")

    def test_csp_by_path(self):
        root = "script-src 'none'"
        eco = self.sb.path_headers()["/ecosystem/"]["Content-Security-Policy"]
        self.assertIn("script-src 'self'", eco)
        self.assertNotIn("unsafe", eco)
        h = (self.out / "_headers").read_text(encoding="utf-8")
        self.assertIn(root, h.split("/ecosystem/*", 1)[0])
        self.assertIn("script-src 'self'", h.split("/ecosystem/*", 1)[1])
        sb, rep, out = self.build(self.repo("beget"), "production")
        self.assertEqual(rep.errors, [])
        self.assertIn(root, (out / ".htaccess").read_text(encoding="utf-8"))
        self.assertIn(f'Content-Security-Policy "{eco}"', (out / "ecosystem/.htaccess").read_text(encoding="utf-8"))

    @mock.patch.object(_Handler, "log_request", lambda *a, **k: None)
    def test_server_sends_path_csp(self):
        srv = server(self.out, 0, self.sb._headers(), quiet=True, path_headers=self.sb.path_headers())
        port = srv.server_address[1]
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        try:
            def get(path):
                with urllib.request.urlopen(f"http://127.0.0.1:{port}{path}") as r:
                    r.read()  # дочитать тело: иначе сервер пишет в закрытое соединение
                    return r.headers["Content-Security-Policy"]
            self.assertIn("script-src 'none'", get("/"))
            self.assertIn("script-src 'self'", get("/ecosystem/"))
            self.assertIn("script-src 'self'", get("/ecosystem/dpvis.js"))
        finally:
            srv.shutdown()
            srv.server_close()

    def test_roles_and_stages_only_from_facts(self):
        data = json.loads((self.out / "ecosystem/dpvis-data.json").read_text(encoding="utf-8"))
        repos = {n["id"]: n for n in data["model"]["nodes"] if n["kind"] == "repo"}
        self.assertEqual(repos["galdhold"]["role"], self.sb.fact("GH-GENRE", "ru"))
        self.assertEqual(repos["galdhold"]["stage"], self.sb.fact("GH-STAGES", "ru"))

        def drop(cfg, repo):
            del cfg["ecosystem"]["roles"]["market"]
            del cfg["ecosystem"]["stages"]["market"]
        _, rep, out = self.build(self.repo(mutate=drop))
        market = next(n for n in json.loads((out / "ecosystem/dpvis-data.json").read_text(encoding="utf-8"))["model"]["nodes"]
                      if n["id"] == "market")
        self.assertNotIn("role", market, "нет факта — нет утверждения о проекте")
        self.assertNotIn("stage", market)
        self.assertTrue(any("market" in w for w in rep.warnings), rep.warnings)

    def test_kit_must_match_manifest(self):
        repo = self.repo()
        css = repo.path("website", "ecosystem", "dpvis.css")
        css.write_text(css.read_text(encoding="utf-8") + "\n/* правка руками */\n", encoding="utf-8")
        _, rep, _ = self.build(repo)
        self.assertTrue(any("dpvis.css не совпадает с MANIFEST.json" in e for e in rep.errors), rep.errors)

    def test_only_public_extract(self):
        repo = self.repo()

        def full(p):
            d = json.loads(p.read_text(encoding="utf-8"))
            d["schema"] = "dpvis-model/1"
            p.write_text(json.dumps(d, ensure_ascii=False), encoding="utf-8")
        self.reseal(repo, "dpvis-data.json", full)
        _, rep, _ = self.build(repo)
        self.assertTrue(any("только публичная выжимка" in e for e in rep.errors), rep.errors)

    def test_anti_hype_on_texts(self):
        repo = self.repo()

        def hype(p):
            d = json.loads(p.read_text(encoding="utf-8"))
            next(n for n in d["model"]["nodes"] if n["kind"] == "service")["role"] = "сервис с CI"
            p.write_text(json.dumps(d, ensure_ascii=False), encoding="utf-8")
        self.reseal(repo, "dpvis-data.json", hype)
        _, rep, _ = self.build(repo)
        self.assertTrue(any(e.startswith("экосистема: «CI» — CL-CI") for e in rep.errors), rep.errors)

    def test_import_checks_kit(self):
        from tools.cs.site import ecosystem
        repo = make_repo(git=False)
        src = Path(tempfile.mkdtemp(prefix="cs-kit-"))
        for name in ("dpvis.js", "dpvis.css", "dpvis-data.json", "MANIFEST.json"):
            shutil.copyfile(ROOT / "website/ecosystem" / name, src / name)
        shutil.rmtree(repo.path("website", "ecosystem"))
        self.assertEqual(ecosystem.import_kit(repo, src), [])
        (src / "dpvis.js").write_text("// не та сборка\n", encoding="utf-8")
        self.assertTrue(ecosystem.import_kit(repo, src), "несовпадение с MANIFEST — отказ до копирования")
        self.assertEqual(ecosystem.kit_problems(repo.path("website", "ecosystem")), [], "прежний комплект не тронут")


if __name__ == "__main__":
    unittest.main()
