"""Сайт: предпросмотр и сервер (П2-09), доступность (П2-10), значок (П2-11), _headers (П2-12), sitemap (П2-13),
имени автора на сайте нет (Д37, Д40), «Экосистема» (Д43), «Путь» (Д44)."""
from __future__ import annotations

import html
import json
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
from tools.cs.platform_checks import page_a11y, page_csp, page_images
from tools.cs.site.build import SiteBuild, build, theme_contrast
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
            self.assertEqual(page_a11y(text) + page_csp(text) + page_images(text), [], page.relative_to(out))
        post = (out / "news/test-news/index.html").read_text(encoding="utf-8")
        self.assertIn('alt="Обложка: Galdhold, C++20 и Vulkan"', post)
        # Размеры обложки — из самого файла (S-02): место занято до загрузки.
        self.assertIn('alt="Обложка: Galdhold, C++20 и Vulkan" width="1280" height="720">', post)
        self.assertIn('aria-current="page"', post)
        self.assertIn('<a class="skip" href="#main">К содержимому</a>', post)

    def test_media_without_alt_is_an_error(self):
        repo = make_repo(git=False)
        with_media(repo, None)
        rep = build(repo, "staging", repo.path("build", "s"))
        self.assertTrue(any("нет media_alt" in e for e in rep.errors), rep.errors)

    def test_unreadable_media_size_is_an_error(self):
        repo = make_repo(git=False)
        with_media(repo, "Обложка")
        (find(repo, "CI-0101").dir / "exports/cover.jpg").write_bytes(b"not an image")
        repo.reset()
        rep = build(repo, "staging", repo.path("build", "s"))
        self.assertTrue(any("размеры exports/cover.jpg не прочитать" in e for e in rep.errors), rep.errors)

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
        # S-02: без width и height — находка; с ними — чисто.
        self.assertEqual(len(page_images(bad)), 2)
        self.assertEqual(page_images('<img src="/x.png" alt="x" width="1280" height="720">'), [])
        self.assertEqual(len(page_images('<img src="/x.png" alt="x" width="1280">')), 1)
        self.assertEqual(page_csp('<script type="application/ld+json">{}</script>'), [])


class Themes(unittest.TestCase):
    """Светлая тема по настройке системы (Д49): токены site_light, контраст текста в обеих темах."""

    def test_light_theme_follows_system(self):
        repo = make_repo(git=False)
        out = repo.path("build", "s")
        rep = build(repo, "staging", out)
        self.assertEqual(rep.errors, [])
        css = (out / "style.css").read_text(encoding="utf-8")
        self.assertIn("color-scheme: dark light", css)
        self.assertIn("@media (prefers-color-scheme: light) { :root { --ink: #F7F7F5;", css)
        home = (out / "index.html").read_text(encoding="utf-8")
        self.assertIn('<meta name="theme-color" content="#111214" media="(prefers-color-scheme: dark)">', home)
        self.assertIn('<meta name="theme-color" content="#F7F7F5" media="(prefers-color-scheme: light)">', home)

    def test_figure_rows_hold_contrast_in_both_themes(self):
        """Ряды и линии фигур (--c-*, кроме тепловой шкалы) — не ниже 3 к фону и панелям в обеих темах (Д49, WCAG 1.4.11)."""
        from tools.cs.visual import contrast, luminance
        css = (ROOT / "website/static/style.css").read_text(encoding="utf-8")
        light_at = css.index("@media (prefers-color-scheme: light)")
        rows = lambda text: dict(re.findall(r"--c-(gald|core|bg|deferred|periphery|hidden):\s*(#[0-9a-fA-F]{6})", text))
        dark, light = rows(css[:light_at]), rows(css[light_at:css.index("}", light_at)])
        self.assertEqual(set(dark), set(light), "у каждого ряда — значение в обеих темах")
        tokens = json.loads((ROOT / "assets/brand/tokens.json").read_text(encoding="utf-8"))
        for name, vals, pal in (("тёмная", dark, tokens["color"]), ("светлая", light, tokens["site_light"]["color"])):
            for row, hex_ in vals.items():
                for bg in ("ink", "graphite"):
                    k = contrast(luminance(hex_), luminance(pal[bg]["hex"]))
                    self.assertGreaterEqual(k, 3.0, f"{name}: --c-{row} к {bg} — {k:.2f}")

    def test_contrast_rule(self):
        tokens = json.loads((ROOT / "assets/brand/tokens.json").read_text(encoding="utf-8"))
        self.assertEqual(theme_contrast(tokens), [], "токены автора держат контраст в обеих темах")
        bad = json.loads(json.dumps(tokens))
        bad["site_light"]["color"]["mist"]["hex"] = "#A3A6AD"  # тёмный «mist» на светлом фоне
        msgs = " ".join(theme_contrast(bad))
        self.assertIn("светлая: mist к ink", msgs)
        self.assertIn("светлая: mist к graphite", msgs)


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
    """Д37, Д40: на сайте только dpsmpx; имени нет в данных и в настройках сайта."""

    def built(self, repo, env="staging"):
        out = Path(tempfile.mkdtemp(prefix="cs-name-")) / env
        sb = SiteBuild(repo, env)
        return sb, sb.build(out), out

    def test_only_dpsmpx(self):
        repo = make_repo(git=False)
        self.assertNotIn("AU-NAME", repo.facts())
        for env in ("staging", "production"):
            sb, rep, out = self.built(repo, env)
            self.assertEqual(rep.errors, [], env)
            self.assertFalse([w for w in rep.warnings if "личных слов" in w], env)
        home = (out / "index.html").read_text(encoding="utf-8")
        self.assertIn("<h1>dpsmpx</h1>", home)
        person = next(json.loads(m) for m in re.findall(r'<script type="application/ld\+json">(.*?)</script>', home)
                      if '"Person"' in m)
        self.assertEqual(person["name"], "dpsmpx")
        self.assertNotIn("alternateName", person)
        self.assertEqual(json.loads((out / "feed.json").read_text(encoding="utf-8"))["authors"][0]["name"], "dpsmpx")

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

    def test_static_page_without_scripts(self):
        """Д51: «Экосистема» — список по слоям; ни скрипта, ни стилей и данных схемы — на всём сайте."""
        self.assertEqual(self.rep.errors, [])
        page = (self.out / "ecosystem/index.html").read_text(encoding="utf-8")
        self.assertIn("Система по слоям", page)
        self.assertIn("Срез на ", page)
        for gone in ("dpvis", "<script src=", "data-src="):
            self.assertNotIn(gone, page)
        for name in ("dpvis.js", "dpvis.css", "dpvis-data.json"):
            self.assertFalse((self.out / "ecosystem" / name).exists(), f"{name} на сайт не выгружается")
        self.assertIn('href="/ecosystem/"', (self.out / "index.html").read_text(encoding="utf-8"))
        self.assertNotIn("/ecosystem/", (self.out / "en/index.html").read_text(encoding="utf-8"), "раздел только на русском")
        scripted = [f.relative_to(self.out).as_posix() for f in self.out.rglob("*.html") if "<script src=" in f.read_text(encoding="utf-8")]
        self.assertEqual(scripted, [], "скриптов на сайте нет")
        self.assertEqual(page_csp(page), [])
        api = json.loads((self.out / "api/v1/index.json").read_text(encoding="utf-8"))
        self.assertNotIn("ecosystem", api["resources"])
        # Д48: признак сборки — коммит (или null без git), правки, время UTC.
        self.assertEqual(set(api["build"]), {"commit", "dirty", "built"})
        self.assertRegex(api["build"]["built"], r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$")
        self.assertTrue(api["build"]["commit"] is None or re.fullmatch(r"[0-9a-f]{40}", api["build"]["commit"]))

    def test_one_csp_everywhere(self):
        """Д51: своих заголовков у каталога нет — CSP сайта везде `script-src 'none'`."""
        self.assertEqual(self.sb.path_headers(), {})
        h = (self.out / "_headers").read_text(encoding="utf-8")
        self.assertIn("script-src 'none'", h)
        self.assertNotIn("script-src 'self'", h)
        sb, rep, out = self.build(self.repo("beget"), "production")
        self.assertEqual(rep.errors, [])
        self.assertIn("script-src 'none'", (out / ".htaccess").read_text(encoding="utf-8"))
        self.assertFalse((out / "ecosystem/.htaccess").exists())

    @mock.patch.object(_Handler, "log_request", lambda *a, **k: None)
    def test_server_sends_one_csp(self):
        srv = server(self.out, 0, self.sb._headers(), quiet=True, path_headers=self.sb.path_headers())
        port = srv.server_address[1]
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        try:
            def get(path):
                with urllib.request.urlopen(f"http://127.0.0.1:{port}{path}") as r:
                    r.read()  # дочитать тело: иначе сервер пишет в закрытое соединение
                    return r.headers["Content-Security-Policy"]
            self.assertIn("script-src 'none'", get("/"))
            self.assertIn("script-src 'none'", get("/ecosystem/"))
        finally:
            srv.shutdown()
            srv.server_close()

    def test_roles_and_stages_only_from_facts(self):
        page = (self.out / "ecosystem/index.html").read_text(encoding="utf-8")
        self.assertIn(html.escape(self.sb.fact("GH-GENRE", "ru")), page)
        self.assertIn(html.escape(self.sb.fact("GH-STAGES", "ru")), page)

        def drop(cfg, repo):
            del cfg["ecosystem"]["roles"]["market"]
            del cfg["ecosystem"]["stages"]["market"]
        _, rep, out = self.build(self.repo(mutate=drop))
        self.assertTrue(any("market" in w for w in rep.warnings), "нет факта — нет утверждения о проекте, предупреждение")

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


class JourneyPath(unittest.TestCase):
    """Д44: раздел «Путь» — станции пути и конечная цель; тексты только из фактов реестра и вех прогресса."""

    @staticmethod
    def repo(mutate=None):
        repo = make_repo(git=False)
        shutil.copyfile(ROOT / "data/website/path.yaml", repo.path("data", "website", "path.yaml"))
        p = repo.path("data", "website", "site.yaml")
        cfg = yaml.safe_load(p.read_text(encoding="utf-8"))
        real = yaml.safe_load((ROOT / "data/website/site.yaml").read_text(encoding="utf-8"))
        cfg["path_section"], cfg["ecosystem"] = real["path_section"], real["ecosystem"]
        p.write_text(yaml.safe_dump(cfg, allow_unicode=True), encoding="utf-8")
        if mutate:
            dp = repo.path("data", "website", "path.yaml")
            data = yaml.safe_load(dp.read_text(encoding="utf-8"))
            mutate(data)
            dp.write_text(yaml.safe_dump(data, allow_unicode=True), encoding="utf-8")
        repo.reset()
        return repo

    @staticmethod
    def build(repo, env="staging"):
        out = Path(tempfile.mkdtemp(prefix="cs-path-")) / env
        rep = SiteBuild(repo, env).build(out)
        return rep, out

    @classmethod
    def setUpClass(cls):
        cls.rep, cls.out = cls.build(cls.repo())

    def page(self, rel="path/index.html"):
        return (self.out / rel).read_text(encoding="utf-8")

    def test_pages_nav_and_home_block(self):
        self.assertEqual(self.rep.errors, [])
        for rel, href, word in (("path/index.html", "/path/", "Путь"), ("en/path/index.html", "/en/path/", "Path")):
            page = self.page(rel)
            self.assertIn(f'<a href="{href}" class="current" aria-current="page">{word}</a>', page)
            self.assertEqual(page_a11y(page) + page_csp(page), [], "без скриптов и встроенных стилей, один h1")
            self.assertIn('hreflang="en" href="http://localhost:8000/en/path/"', page)
        home = self.page("index.html")
        self.assertIn('<a href="/path/">Путь</a>', home, "пункт меню — на всех страницах")
        self.assertIn('class="route-strip"', home, "на главной — маршрут одной строкой")
        self.assertIn('href="/en/path/"', self.page("en/index.html"))
        self.assertIn('<a href="/path/">Путь</a>.</p>', self.page("ecosystem/index.html"), "из «Экосистемы» — ссылка на путь")

    def test_route_and_goal_drawings(self):
        from tools.cs.site.path import W_NARROW, W_WIDE
        page = self.page()
        self.assertEqual(page.count('class="chart route-svg"'), 1)
        self.assertIn(f'viewBox="0 0 {W_WIDE} ', page)
        self.assertIn(f'viewBox="0 0 {W_NARROW} ', page, "у цели — узкий рисунок для телефона")
        self.assertEqual(page.count('class="chart goal-svg"'), 2)
        self.assertEqual(page.count('<li class="st st-now">'), 1, "«сейчас» — между пройденным и планом")
        self.assertIn("DPmetaApp", page)
        self.assertIn("«Сначала построить качественные самостоятельные системы.", page)

    def test_goal_text_outside_svg(self):
        """P-04 аудита страниц 8.11.2026: у рисунка цели `role="img"` — диктор не читает текст внутри SVG,
        поэтому приложение, оба ядра и основание есть в списке под схемой теми же формулировками."""
        import html as _html
        import re as _re
        facts = {f["id"]: f for f in yaml.safe_load((ROOT / "data/facts/facts.yaml").read_text(encoding="utf-8"))["facts"]}
        goal = yaml.safe_load((ROOT / "data/website/path.yaml").read_text(encoding="utf-8"))["goal"]
        for rel, lang, words in (("path/index.html", "ru", ("Два ядра", "Основание: DPvisualisation")),
                                 ("en/path/index.html", "en", ("Two cores", "Base: DPvisualisation"))):
            outside = _html.unescape(_re.sub(r"<svg\b.*?</svg>", "", self.page(rel), flags=_re.S))
            for word in words:
                self.assertIn(word, outside, (lang, word))
            for c in goal["cores"]:
                self.assertIn(c["title"][lang], outside, (lang, "ядро вне SVG"))
            for src in (goal["base"]["text"], goal["cores"][0]["text"]):
                wording = facts[src["fact"]]["allowed_wording"][lang][src.get("idx", 0)]
                self.assertIn(wording[1:-1], outside, (lang, src))

    def test_texts_only_from_facts_and_milestones(self):
        import html as _html
        facts = {f["id"]: f for f in yaml.safe_load((ROOT / "data/facts/facts.yaml").read_text(encoding="utf-8"))["facts"]}
        ms = {m["id"]: m for m in yaml.safe_load((ROOT / "data/progress/milestones.yaml").read_text(encoding="utf-8"))["milestones"]}
        data = yaml.safe_load((ROOT / "data/website/path.yaml").read_text(encoding="utf-8"))
        page = _html.unescape(self.page())
        for st in data["stations"]:
            for src in st["texts"]:
                text = ms[src["milestone"]]["ru"] if "milestone" in src else \
                    facts[src["fact"]]["allowed_wording"]["ru"][src.get("idx", 0)]
                self.assertIn(text[1:-1], page, f"станция {st['id']}: текст источника на странице")

    def test_undisclosed_project_never_named(self):
        scope = yaml.safe_load((ROOT / "data/progress/scope.yaml").read_text(encoding="utf-8"))
        hidden = [r["slug"].split("/")[1] for r in scope["repos"] if r.get("visibility") == "undisclosed"]
        self.assertTrue(hidden)
        for rel in ("path/index.html", "en/path/index.html", "index.html"):
            for name in hidden:
                self.assertNotIn(name.lower(), self.page(rel).lower())
        self.assertIn("ещё 1 проект без публичного имени", self.page())

    def test_production_has_the_section(self):
        rep, out = self.build(self.repo(), "production")
        self.assertEqual(rep.errors, [])
        self.assertIn("/path/", rep.pages)
        self.assertIn("/en/path/", rep.pages)
        self.assertNotIn('name="robots" content="noindex', (out / "path/index.html").read_text(encoding="utf-8"))

    def test_bad_sources_are_build_errors(self):
        def unknown_fact(d):
            d["stations"][0]["texts"] = [{"fact": "AU-NOPE"}]

        def ms_outside(d):
            d["stations"][1]["texts"].append({"milestone": "M-19"})

        def no_en(d):
            d["stations"][0]["texts"] = [{"fact": "AU-ORIGIN", "idx": 5}]

        def unknown_node(d):
            d["goal"]["domains"][0]["nodes"].append("nobody")

        def plan_before_past(d):
            d["stations"][0]["plan"] = True

        for mutate, word in ((unknown_fact, "AU-NOPE"), (ms_outside, "M-19"), (no_en, "AU-ORIGIN"),
                             (unknown_node, "nobody"), (plan_before_past, "после станций плана")):
            rep, out = self.build(self.repo(mutate))
            self.assertTrue(any(word in e for e in rep.errors), (word, rep.errors))
            self.assertFalse((out / "path/index.html").exists(), "страница с ошибкой данных не собирается")

    def test_anti_hype_on_labels(self):
        def boast(d):
            d["stations"][1]["title"]["ru"] = "Лучший разработчик в мире"
        rep, _ = self.build(self.repo(boast))
        self.assertTrue(any("AH-SUPERLATIVE" in e for e in rep.errors), rep.errors)

    def test_all_in_one(self):
        """П2-28: «всё в одном» (DPmetaApp D-28) — оркестратор входит в DPmetaApp: дальше без своего квадрата,
        в цели — подсистема ИИ, а не отдельный проект; рамка DPmetaApp сплошная (приложение есть, APP-NOW)."""
        import re as _re
        from tools.cs.site.path import route_svg
        data = yaml.safe_load((ROOT / "data/website/path.yaml").read_text(encoding="utf-8"))
        st = next(s for s in data["stations"] if s["id"] == "all-in-one")
        self.assertEqual(st["merged"], {"orchestrator": "dpmetaapp"})
        svg = route_svg(data, "ru", "DPmetaApp")
        titles = _re.findall(r"<title>([^<]+)</title></rect>", svg)
        self.assertEqual(titles.count("DP_AI_Orcestrator"), 1, "квадрат оркестратора — только на станции «Цель записана»")
        ai = next(d for d in data["goal"]["domains"] if d["id"] == "ai")
        self.assertNotIn("orchestrator", ai["nodes"])
        page = self.page()
        self.assertIn("<h3>Всё в одном</h3>", page)
        self.assertIn("<strong>подсистема ИИ в DPmetaApp</strong>", page)
        app_box = _re.search(r'<rect[^>]*class="(g-box[^"]*)"/><text[^>]*class="t-lab g-appt"', page)
        self.assertEqual(app_box.group(1), "g-box", "приложение уже есть — сплошная рамка")

    def test_phase_b_started(self):
        """Ответ автора 25.10.2026: фаза B — «идёт» с 11.10 (DPmetaApp D-29), а не «план»."""
        page = self.page()
        self.assertIn('<li class="st st-plan st-active" id="st-phase-b">', page)
        self.assertIn('идёт с <time datetime="2026-10-11">11.10.2026</time>', page)
        self.assertIn("Фаза B начата", page)
        self.assertIn('class="r-dot r-dot-active"', page)
        self.assertIn('<li class="st st-plan" id="st-phase-a">', page, "фаза A — по-прежнему план")
        self.assertIn('<span class="rs-when">фаза B · идёт</span>', self.page("index.html"))
        self.assertIn("in progress since", self.page("en/path/index.html"))

    def test_bad_started_is_a_build_error(self):
        def on_past(d):
            next(s for s in d["stations"] if s["id"] == "game")["started"] = {"date": "2026-09-11", "text": {"fact": "APP-PHASE-B"}}

        def after_now(d):
            next(s for s in d["stations"] if s["id"] == "phase-b")["started"]["date"] = "2099-01-01"

        for mutate, word in ((on_past, "только у станции плана"), (after_now, "позже «сейчас»")):
            rep, out = self.build(self.repo(mutate))
            self.assertTrue(any(word in e for e in rep.errors), (word, rep.errors))
            self.assertFalse((out / "path/index.html").exists())

    def test_bad_merge_is_a_build_error(self):
        def merge_unknown(d):
            next(s for s in d["stations"] if s["id"] == "all-in-one")["merged"] = {"nobody": "dpmetaapp"}

        def merge_into_future(d):
            d["stations"][0]["merged"] = {"galdhold": "dpmeta"}  # на станции «2023» ни того, ни другого ещё нет

        for mutate, word in ((merge_unknown, "вошедший nobody"), (merge_into_future, "а dpmeta на маршруте ещё нет")):
            rep, out = self.build(self.repo(mutate))
            self.assertTrue(any(word in e for e in rep.errors), (word, rep.errors))
            self.assertFalse((out / "path/index.html").exists())

    def test_dates_and_wrap(self):
        from tools.cs.site.path import sentence, when, when_short, wrap
        st = {"date": "2026-09-25", "date_to": "2026-09-29"}
        self.assertEqual(when(st, "ru"), "25–29.09.2026")
        self.assertEqual(when(st, "en"), "25–29 Sep 2026")
        self.assertEqual(when_short(st, "ru"), "25–29.09")
        self.assertEqual(when_short({"date": "2023-03-03"}, "ru"), "2023")
        self.assertEqual(when_short({"date": "2026-10-05"}, "en"), "Oct 5")
        self.assertEqual(sentence("проекты остаются самостоятельными"), "Проекты остаются самостоятельными.")
        self.assertEqual(sentence("Готово."), "Готово.")
        self.assertEqual(wrap("Новые системы", 60, 13), ["Новые", "системы"])
        self.assertEqual(wrap("Экосистема", 100, 13), ["Экосистема"])


if __name__ == "__main__":
    unittest.main()
