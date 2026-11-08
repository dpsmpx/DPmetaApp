"""Визуальная регрессия сайта (П2-17): правила снимков ловят поломки, staging фикстуры — чистый.

Нужен Node.js с Playwright и Chromium; без них и при CS_SKIP_RENDER=1 —
пропуск (как tests/test_render.py).
"""
from __future__ import annotations

import os
import shutil
import tempfile
import unittest
from pathlib import Path

from tools.cs.site import shots
from tools.cs.site.build import SECURITY_HEADERS
from tests.platform_fixture import make_repo

SKIP = os.environ.get("CS_SKIP_RENDER") == "1" or not shutil.which("node")

BAD = """<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><link rel="stylesheet" href="/s.css"></head>
<body><header class="site-header"><nav><a href="/">x</a></nav></header><h1>А</h1><h1>Б</h1><div class="wide">широкий блок</div>
<img src="/nope.png" alt=""><p style="color:red">встроенный стиль</p><script>1</script></body></html>"""


@unittest.skipIf(SKIP, "нет Chromium (CS_SKIP_RENDER или Node.js)")
class SiteShots(unittest.TestCase):
    def test_rules_catch_breakage(self):
        d = Path(tempfile.mkdtemp(prefix="cs-shots-"))
        (d / "bad").mkdir()
        (d / "bad/index.html").write_text(BAD, encoding="utf-8")
        (d / "s.css").write_text(".wide { width: 2000px }", encoding="utf-8")
        res = shots.shoot(d, ["/bad/"], d / "out", SECURITY_HEADERS, widths=(390,))
        msgs = " | ".join(f.msg for f in shots.findings(res) if f.level == "error")
        for expected in ("прокрутка вбок", "за краем экрана", "изображение не загрузилось", "нарушение CSP: style-src",
                         "нарушение CSP: script-src", "запрос не удался: /nope.png", "IBM Plex Sans не загружен",
                         "h1: 2", "цель касания меньше 24 px", "пункт меню меньше 44 × 44 px", "@390 light"):
            self.assertIn(expected, msgs)
        self.assertTrue((d / "out/bad-390.png").is_file())
        self.assertTrue((d / "out/bad-390-light.png").is_file(), "светлая тема снимается отдельно (Д49)")

    def test_staging_fixture_clean(self):
        repo = make_repo(git=False)
        out = repo.path("build", "shots")
        r = shots.check(repo, out)
        self.assertEqual(r.errors, [])
        for name in ("home-390.png", "home-1280.png", "news-test-news-390.png", "projects-galdhold-1280.png", "404-html-390.png",
                     "home-390-light.png", "projects-galdhold-1280-light.png"):
            self.assertTrue((out / name).is_file(), name)


class HeaderRule(unittest.TestCase):
    """Правило шапки (Д49) — на данных замера, без браузера: образец, который оно ловит, и чистый."""

    @staticmethod
    def page(path, width, height, theme="dark"):
        return {"path": path, "width": width, "theme": theme, "status": 200, "viewport": width, "scrollWidth": width,
                "overflow": [], "images": [], "csp": [], "console": [], "failed": [], "plex": True, "fontFamily": "",
                "h1": 1, "smallTargets": [], "navTargets": [], "smallText": [], "headerHeight": height}

    def test_jumping_header_is_caught(self):
        res = [self.page("/", 1280, 141), self.page("/ecosystem/", 1280, 85), self.page("/", 390, 150), self.page("/ecosystem/", 390, 110)]
        msgs = [f.msg for f in shots.findings(res) if f.level == "error"]
        self.assertEqual(len(msgs), 1, msgs)
        self.assertIn("@1280: шапка разной высоты (85 px — /ecosystem/; 141 px — /)", msgs[0])

    def test_same_header_is_clean(self):
        res = [self.page("/", 1280, 85), self.page("/ecosystem/", 1280, 85), self.page("/", 1280, 86, "light"), self.page("/x/", 1280, 85, "light")]
        self.assertEqual([f for f in shots.findings(res) if f.level == "error"], [])


if __name__ == "__main__":
    unittest.main()
