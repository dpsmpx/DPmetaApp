"""Фильтр чувствительных данных (Д39): ловит утечки в выходе сайта, не трогает чистое и публичное."""
from __future__ import annotations

import json
import os
import shutil
import tempfile
import unittest
import zlib
from pathlib import Path
from unittest import mock

import yaml

from tools.cs import secrets
from tools.cs.core import sensitive, textdoc
from tools.cs.core.content import find
from tools.cs.progress.dataset import Progress
from tools.cs.site.build import SiteBuild
from tests.platform_fixture import make_repo


def png_with_text() -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return len(data).to_bytes(4, "big") + kind + data + zlib.crc32(kind + data).to_bytes(4, "big")
    ihdr = (1).to_bytes(4, "big") + (1).to_bytes(4, "big") + bytes([8, 0, 0, 0, 0])
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"tEXt", b"Author\0someone")
            + chunk(b"IDAT", zlib.compress(b"\0\0")) + chunk(b"IEND", b""))


def jpeg_with_exif() -> bytes:
    exif = b"Exif\0\0" + b"MM\0*" + b"\0" * 8
    return b"\xff\xd8" + b"\xff\xe1" + (len(exif) + 2).to_bytes(2, "big") + exif + b"\xff\xda\0\x02" + b"\xff\xd9"


class Filter(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repo = make_repo(git=False)
        cls.f = sensitive.Filter(cls.repo, private_words=["zqxprivat"])  # заглушка, не имя (Д40)
        data = json.loads(cls.repo.path("data/progress/datasets/2026-10-05.json").read_text(encoding="utf-8"))
        cls.head = data["repos"]["galdhold"]["head"]
        cls.public_head = data["repos"]["tanks"]["head"]  # открытый репозиторий — не секрет

    def rules(self, text: str) -> set[str]:
        return {h.rule for h in self.f.scan_text(text, "x.html")}

    def test_catches_each_kind(self):
        token = "ghp_" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8"
        cases = {
            "SD-EMAIL": "<p>пишите: someone.private" + "@" + "mail.ru</p>",
            "SD-PHONE": "<p>звоните +7 912 345-67-89</p>",
            "SD-IP": "<p>сервер 185.12.64.7</p>",
            "SD-PATH": "<p>/home/dpsmpx/projects</p>",
            "SD-HOST": '<a href="http://login.beget.tech/">x</a>',
            "SD-SESSION": "<p>https://claude.ai/code/session_01ABCDEFGHIJKLMNOPQRSTUV</p>",
            "SD-BRANCH": "<p>ветка ccr-16a9602f-9ogizk</p>",
            "SD-SECRET": f"<p>{token}</p>",
            "SD-PRIVATE": "<h1>ZQXPRIVAT</h1>",
            "SD-UNDISCLOSED": "<li>DPpass</li>",
            "SD-PERSONAL": "<code>dayplan_neocities</code>",
            "SD-REF": f"<p>коммит {self.head[:7]}</p>",
        }
        for rule, text in cases.items():
            self.assertIn(rule, self.rules(text), rule)

    def test_secret_value_not_printed(self):
        hit = next(h for h in self.f.scan_text("a someone.private" + "@mail.ru b", "x.html") if h.rule == "SD-EMAIL")
        self.assertNotIn("mail.ru", hit.msg)
        self.assertIn("x.html: адрес почты", hit.msg)

    def test_clean_and_public_pass(self):
        svg = '<svg viewBox="0 0 640 300"><path d="M8 300 200 10 20 L 185 12 64 7"/><title>8 октября</title></svg>'
        text = (f'<p>dpsmpx · 981 коммит · 08:00 МСК · Galdhold · DPlang · {self.public_head[:7]}</p>{svg}'
                '<a href="https://github.com/dpsmpx">GitHub</a> <a href="http://localhost:8000/">staging</a>')
        self.assertEqual(self.f.scan_text(text, "x.html"), [])

    def test_private_word_not_printed(self):
        hit = next(h for h in self.f.scan_text("<p>Zqxprivatа</p>", "x.html") if h.rule == "SD-PRIVATE")
        self.assertNotIn("zqxprivat", hit.msg.lower())
        self.assertIn("x.html: слово из списка личных слов", hit.msg)

    def test_without_list_private_check_is_limited(self):
        missing = Path(tempfile.mkdtemp(prefix="cs-words-")) / "нет.txt"
        with mock.patch.dict(os.environ, {"DPSMPX_PRIVATE_WORDS": str(missing)}):
            f = sensitive.Filter(self.repo)
        self.assertTrue(f.private_limited)
        self.assertEqual(f.scan_text("<h1>zqxprivat</h1>", "x"), [])
        self.assertFalse(sensitive.Filter(self.repo, private_words=[]).private_limited)

    def test_allow_list(self):
        repo = make_repo(git=False)
        pol = repo.path("data/policy/sensitive.yaml")
        cfg = yaml.safe_load(pol.read_text(encoding="utf-8"))
        cfg["allow"] = [{"rule": "SD-EMAIL", "value": "press" + "@dpsmpx.com", "reason": "публичный адрес для прессы"}]
        pol.write_text(yaml.safe_dump(cfg, allow_unicode=True), encoding="utf-8")
        repo.reset()
        self.assertEqual(sensitive.Filter(repo).scan_text("<p>press" + "@dpsmpx.com</p>", "x"), [])

    def test_image_metadata(self):
        d = Path(tempfile.mkdtemp(prefix="cs-sd-"))
        (d / "a.png").write_bytes(png_with_text())
        (d / "b.jpg").write_bytes(jpeg_with_exif())
        shutil.copyfile(self.repo.path("assets/brand/fonts").glob("*.woff2").__next__(), d / "f.woff2")
        hits = {(h.where, h.rule, h.found) for h in self.f.scan_tree(d)}
        self.assertIn(("a.png", "SD-META", "текст PNG (tEXt)"), hits)
        self.assertIn(("b.jpg", "SD-META", "EXIF"), hits)
        self.assertFalse([h for h in hits if h[0] == "f.woff2"])


class SiteOutput(unittest.TestCase):
    def test_fixture_site_is_clean(self):
        repo = make_repo(git=False)
        for env in ("staging", "production"):
            out = Path(tempfile.mkdtemp(prefix="cs-sd-site-")) / env
            rep = SiteBuild(repo, env).build(out)
            self.assertEqual(rep.errors, [], env)
            self.assertEqual(sensitive.scan(repo, out), [], env)

    def test_leak_in_content_fails_the_build(self):
        repo = make_repo(git=False)
        v = find(repo, "CI-0101").variants()["website.ru"]
        v.doc.body = "Рядом в работе — DPpass.\n\n" + v.doc.body
        textdoc.save(v.doc, v.path)
        repo.reset()
        rep = SiteBuild(repo, "staging").build(Path(tempfile.mkdtemp(prefix="cs-sd-site-")) / "s")
        self.assertTrue(any("news/test-news/index.html: имя закрытого проекта" in e for e in rep.errors), rep.errors)


class ProgressPublic(unittest.TestCase):
    def test_public_is_allow_list(self):
        repo = make_repo(git=False)
        p = Progress(repo, "2026-10-05")
        pub = p.public()
        text = json.dumps(pub, ensure_ascii=False)
        for word in ("undisclosed", "DPpass", "head", "branches_now", "history"):
            self.assertNotIn(word, text)
        self.assertEqual(pub["only_in_totals"], 1)
        self.assertEqual(len(pub["repositories"]), len(p.keys) - 1)
        self.assertEqual(sum(pub["commits"]["total"]), p.value("commits"), "суммы — со всеми репозиториями")
        self.assertEqual(set(pub["period"]), {"start", "end", "timezone", "cutoff"})


if __name__ == "__main__":
    unittest.main()
