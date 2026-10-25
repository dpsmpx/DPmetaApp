"""Сквозной путь (PLATFORM_VISION §72, §103) и сайт, проверки, миграция, границы модулей.

create canonical news → adapt → approve → publish to mock website → publish to
mock Telegram → verify → analytics event; затем production-сборка сайта
содержит запись, фиды и API читаются.
"""
from __future__ import annotations

import json
import os
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest import mock

import yaml

from tools.cs.common import ROOT
from tools.cs.core import textdoc
from tools.cs.core.analytics import from_metrics, interpretation, totals
from tools.cs.core.content import approval_state, find, stale_reasons
from tools.cs.core.engine import Publisher, Target
from tools.cs.core.ledger import Ledger
from tools.cs.site.build import build
from tests.platform_fixture import approve, git_commit, make_repo, ready_repo, seal_all
from tests.test_publishing import NO_SESSION, Factory


class EndToEnd(unittest.TestCase):
    def setUp(self):
        self.env = mock.patch.dict(os.environ, NO_SESSION)
        self.env.start()

    def tearDown(self):
        self.env.stop()

    def test_news_to_website_and_telegram(self):
        repo = make_repo(git=True)
        # 1. канон создан (фикстура) и проверен; 2. варианты — заготовки, зафиксированы
        seal_all(repo)
        item = find(repo, "CI-0101")
        self.assertTrue(item.canon().sealed)
        self.assertTrue(all(v.m("status") == "ready" for v in item.variants().values()))
        # 3. без одобрения рабочая публикация откажет
        f = Factory(repo)
        pub = lambda: Publisher(repo, "production", adapter_factory=f, sleep=lambda s: None)  # noqa: E731
        self.assertEqual(pub().run(Target("CI-0101", "telegram.ru", "tg-prod")).status, "blocked")
        # 4. автор одобряет обе версии
        approve(repo, "CI-0101", "website.ru")
        approve(repo, "CI-0101", "telegram.ru")
        git_commit(repo, "approved")
        repo.reset()
        # 5. сайт (имитация) и Telegram (имитация)
        site = pub().run(Target("CI-0101", "website.ru", "site-production"))
        tg = pub().run(Target("CI-0101", "telegram.ru", "tg-prod"))
        self.assertEqual((site.status, tg.status), ("published", "published"))
        # 6. проверка: журнал, текст на площадке со ссылкой на канонический адрес
        led = Ledger(repo, "production")
        pubs = {p.first["variant"]: p for p in led.publications().values()}
        self.assertEqual({k: p.state for k, p in pubs.items()}, {"website.ru": "PUBLISHED", "telegram.ru": "PUBLISHED"})
        self.assertTrue(all(p.last("PUBLISHED").get("verified") for p in pubs.values()))
        post = f.posts("tg-prod")[0]["text"]
        self.assertIn("https://dpsmpx.com/news/test-news/", post)
        self.assertIn("<b>Galdhold</b>", post)
        # 7. событие аналитики, привязанное к публикации
        tgp = pubs["telegram.ru"]
        metrics = {"item": "CI-0101", "variant": "telegram.ru", "platform": "telegram", "campaign": None,
                   "publication_id": tgp.id, "period": {"from": "2026-10-05", "to": "2026-10-12"},
                   "values": {"views": 120, "reactions": 9, "shares": 2, "ctr": 0.05}}
        events = from_metrics(metrics)
        self.assertEqual(sorted(e.kind for e in events), ["Reaction", "Share", "View"])
        self.assertTrue(all(e.publication_id == tgp.id for e in events))
        self.assertEqual(totals(events)["telegram"]["View"], 120)
        self.assertEqual(interpretation("технические посты набирают больше просмотров")["class"], "INTERPRETATION")
        # 8. production-сайт содержит одобренную опубликованную запись; фиды и API читаются
        out = repo.path("build", "site", "production-test")
        rep = build(repo, "production", out)
        self.assertEqual(rep.errors, [])
        page = (out / "news/test-news/index.html").read_text(encoding="utf-8")
        self.assertIn('<link rel="canonical" href="https://dpsmpx.com/news/test-news/">', page)
        self.assertIn('og:title', page)
        self.assertIn("BlogPosting", page)
        self.assertNotIn("noindex", page)
        atom = ET.parse(out / "feed.xml").getroot()
        self.assertEqual(len(atom.findall("{http://www.w3.org/2005/Atom}entry")), 1)
        feed = json.loads((out / "feed.json").read_text(encoding="utf-8"))
        self.assertEqual(feed["items"][0]["id"], "https://dpsmpx.com/news/test-news/")
        news = json.loads((out / "api/v1/news.json").read_text(encoding="utf-8"))
        self.assertEqual(news["news"][0]["id"], "CI-0101")
        self.assertIn("https://dpsmpx.com/news/test-news/", (out / "sitemap.xml").read_text())


class Site(unittest.TestCase):
    def test_staging_drafts_noindex_and_production_empty(self):
        repo = make_repo(git=False)
        st = repo.path("build", "s")
        rep = build(repo, "staging", st)
        self.assertEqual(rep.errors, [])
        self.assertIn("Disallow: /", (st / "robots.txt").read_text())
        page = (st / "news/test-news/index.html").read_text(encoding="utf-8")
        self.assertIn("noindex", page)
        self.assertIn("Черновик", page)
        self.assertTrue((st / "projects/galdhold/index.html").is_file())
        self.assertTrue((st / "en/projects/galdhold/index.html").is_file())
        self.assertIn('hreflang="en"', (st / "projects/galdhold/index.html").read_text(encoding="utf-8"))
        pr = repo.path("build", "p")
        rep = build(repo, "production", pr)
        self.assertFalse((pr / "news/test-news").exists(), "черновик не попадает на production")
        self.assertIn("Allow: /", (pr / "robots.txt").read_text())

    def test_project_page_injects_facts(self):
        repo = make_repo(git=False)
        out = repo.path("build", "s")
        build(repo, "staging", out)
        html = (out / "projects/galdhold/index.html").read_text(encoding="utf-8")
        facts = repo.facts()
        self.assertIn(facts["GH-LOC"]["allowed_wording"]["ru"][0], html)
        self.assertIn(facts["GH-TECH"]["allowed_wording"]["en"][0],
                      (out / "en/projects/galdhold/index.html").read_text(encoding="utf-8"))

    def test_real_repo_site_check(self):
        from tools.cs.platform_checks import site_check
        self.assertTrue(site_check().ok)


class Checks(unittest.TestCase):
    def test_destinations_boundaries(self):
        from tools.cs.platform_checks import destinations_check
        repo = make_repo(git=False)
        p = repo.path("data/platforms/destinations.yaml")
        d = yaml.safe_load(p.read_text(encoding="utf-8"))
        d["destinations"][3]["credential_ref"] = "CS_PROD_TELEGRAM_BOT_TOKEN"  # staging с рабочим токеном
        d["destinations"][2]["account"] = {"id": None}
        p.write_text(yaml.safe_dump(d, allow_unicode=True), encoding="utf-8")
        repo.reset()
        errs = " ".join(destinations_check(repo).errors)
        self.assertIn("граница окружений", errs)
        self.assertIn("смешение запрещено", errs)
        self.assertIn("account.id", errs)

    def test_sealed_canon_immutable_and_stale(self):
        from tools.cs.platform_checks import canonical_check
        repo = ready_repo()
        self.assertTrue(canonical_check(repo).ok, canonical_check(repo).errors)
        cv = find(repo, "CI-0101").canon()
        cv.path.write_text(cv.path.read_text(encoding="utf-8").replace("Новая сборка", "Другая сборка"), encoding="utf-8")
        repo.reset()
        r = canonical_check(repo)
        self.assertTrue(any("изменён после фиксации" in e for e in r.errors))
        v = find(repo, "CI-0101").variants()["telegram.ru"]
        self.assertIn("канон v1 изменён после фиксации", stale_reasons(v))

    def test_capabilities_matrix_in_sync(self):
        from tools.cs.capabilities import check
        self.assertTrue(check().ok, check().errors)

    def test_publications_check_real_repo(self):
        from tools.cs.platform_checks import publications_check
        self.assertTrue(publications_check().ok)


class Migration(unittest.TestCase):
    def test_v1_to_v2_moves_copy(self):
        from tools.cs.core import migrate
        repo = make_repo(git=False)
        d = repo.path("content/items/CI-0102-old")
        (d / "copy").mkdir(parents=True)
        (d / "copy/telegram.md").write_text("<!-- facts: GH-NAME -->\nТекст", encoding="utf-8")
        (d / "copy/youtube.md").write_text("<!-- только комментарий -->\n", encoding="utf-8")
        (d / "item.yaml").write_text(yaml.safe_dump({"schema": "content_item/v1", "id": "CI-0102", "slug": "old",
                                                     "languages": ["ru"], "state": "IDEA",
                                                     "history": [{"date": "2026-10-04", "state": "IDEA", "note": "x"}]},
                                                    allow_unicode=True), encoding="utf-8")
        item = find(repo, "CI-0102")
        plan = migrate.plan_v1_to_v2(item)
        self.assertEqual([(a["to"], a["skip"]) for a in plan], [("variants/telegram.ru.md", False), ("variants/youtube.ru.md", True)])
        self.assertTrue((d / "copy").exists(), "пробный прогон ничего не меняет")
        migrate.apply_v1_to_v2(item)
        v = textdoc.load(d / "variants/telegram.ru.md")
        self.assertEqual((v.meta["platform"], v.meta["claims"], v.body), ("telegram", ["GH-NAME"], "Текст"))
        self.assertFalse((d / "copy").exists())
        data = yaml.safe_load((d / "item.yaml").read_text(encoding="utf-8"))
        self.assertEqual(data["schema"], "content_item/v2")
        self.assertIn("миграция", data["history"][-1]["note"])


class Boundaries(unittest.TestCase):
    def test_core_does_not_import_platform_adapters(self):
        """Д23: ядро не знает площадок по имени — только реестр, базу и имитации."""
        bad = re.compile(r"from \.\.adapters\.(telegram|manual|website)\b|import .*TelegramAdapter")
        for p in (ROOT / "tools/cs/core").glob("*.py"):
            self.assertIsNone(bad.search(p.read_text(encoding="utf-8")), p.name)

    def test_no_platform_branches_in_core(self):
        pat = re.compile(r"""==\s*["'](telegram|vk|discord|youtube|github|tiktok)["']""")
        for p in (ROOT / "tools/cs/core").glob("*.py"):
            self.assertIsNone(pat.search(p.read_text(encoding="utf-8")), f"{p.name}: if <площадка> в ядре (§29)")


if __name__ == "__main__":
    unittest.main()
