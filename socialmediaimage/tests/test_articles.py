"""Раздел статей (Д42): адрес, раздел и меню сайта, разметка статьи; блоки кода канона; номера разделов."""
from __future__ import annotations

import json
import re
import unittest

import yaml

from tools.cs.claims import text_findings
from tools.cs.common import Item
from tools.cs.core import markup
from tools.cs.core.content import find
from tools.cs.site.build import build
from tests.platform_fixture import make_repo


def as_article(repo):
    """Запись фикстуры CI-0101 → статья."""
    p = find(repo, "CI-0101").dir / "item.yaml"
    data = yaml.safe_load(p.read_text(encoding="utf-8"))
    data.update(type="article", category="article")
    p.write_text(yaml.safe_dump(data, allow_unicode=True, sort_keys=False), encoding="utf-8")
    repo.reset()


def jsonld(html: str) -> list[dict]:
    return [json.loads(m.replace("<\\/", "</")) for m in
            re.findall(r'<script type="application/ld\+json">(.*?)</script>', html, re.S)]


class Markup(unittest.TestCase):
    TEXT = "Абзац\n\n```text\nрешения\n   ↓\n<план> & задачи\n```\n\n- пункт\n"

    def test_fenced_block_keeps_indent_and_escapes(self):
        blocks = markup.parse(self.TEXT)
        self.assertEqual([b.kind for b in blocks], ["para", "pre", "list"])
        self.assertIn("<pre><code>решения\n   ↓\n&lt;план&gt; &amp; задачи</code></pre>", markup.to_html(blocks))
        self.assertIn("<pre>решения\n   ↓\n&lt;план&gt;", markup.to_telegram_html(blocks, ""))
        self.assertIn("```\nрешения\n   ↓\n<план> & задачи\n```", markup.to_discord_md(blocks, ""))
        self.assertIn("решения\n   ↓\n<план> & задачи", markup.to_plain(blocks, ""))
        self.assertEqual(markup.links(blocks), [])

    def test_lines_inside_fence_are_not_markup(self):
        blocks = markup.parse("```\n## не заголовок\n- не пункт\n```")
        self.assertEqual([b.kind for b in blocks], ["pre"])

    def test_unclosed_fence_keeps_text(self):
        blocks = markup.parse("текст\n\n```\nстрока")
        self.assertEqual(blocks[-1].kind, "pre")
        self.assertEqual(blocks[-1].lines[0][0].text, "строка")


class SectionNumbers(unittest.TestCase):
    def test_heading_number_is_not_a_claim(self):
        item = Item.__new__(Item)
        item.data = {}
        out = text_findings(item, "## 10. Почему период важнее\n\nТекст.", [], "t", False, {}, [])
        self.assertEqual(out, [])
        out = text_findings(item, "## Раздел\n\nЗа 10 дней.", [], "t", False, {}, [])
        self.assertTrue(any("число 10" in f.msg for f in out), "число в тексте по-прежнему требует факта")


class Section(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repo = make_repo(git=False)
        as_article(cls.repo)
        cls.st = cls.repo.path("build", "st")
        cls.pr = cls.repo.path("build", "pr")
        build(cls.repo, "staging", cls.st)
        build(cls.repo, "production", cls.pr)

    def read(self, rel: str, out=None) -> str:
        return ((out or self.st) / rel).read_text(encoding="utf-8")

    def test_address_and_section(self):
        self.assertEqual(find(self.repo, "CI-0101").url_path("ru"), "/articles/test-news/")
        self.assertTrue((self.st / "articles/test-news/index.html").is_file())
        self.assertFalse((self.st / "news/test-news").exists())
        self.assertIn('href="/articles/test-news/"', self.read("articles/index.html"))

    def test_menu_home_and_news(self):
        home = self.read("index.html")
        self.assertIn('<a href="/articles/">Статьи</a>', home)
        self.assertIn('href="/articles/test-news/"', home, "блок статей на главной")
        self.assertIn('<a href="/articles/">Articles (RU)</a>', self.read("en/index.html"))
        self.assertNotIn("/articles/test-news/", self.read("news/index.html"), "лента записей — без статей")
        page = self.read("articles/test-news/index.html")
        self.assertIn('<a href="/articles/" class="current" aria-current="page">Статьи</a>', page)

    def test_article_page(self):
        page = self.read("articles/test-news/index.html")
        self.assertIn('<nav class="crumbs"', page)
        self.assertRegex(page, r"\d+ мин чтения")
        self.assertIn('<p class="lead">Что вошло в сборку</p>', page, "подзаголовок — summary варианта")
        kinds = {d["@type"]: d for d in jsonld(page)}
        self.assertIn("Article", kinds)
        self.assertGreater(kinds["Article"]["wordCount"], 10)
        crumbs = [i["item"] for i in kinds["BreadcrumbList"]["itemListElement"]]
        self.assertEqual(crumbs, ["http://localhost:8000/", "http://localhost:8000/articles/",
                                  "http://localhost:8000/articles/test-news/"])
        self.assertEqual(jsonld(self.read("articles/index.html"))[0]["@type"], "CollectionPage")

    def test_production_without_approval_has_no_section(self):
        self.assertFalse((self.pr / "articles").exists())
        self.assertNotIn("/articles/", self.read("index.html", self.pr))


if __name__ == "__main__":
    unittest.main()
