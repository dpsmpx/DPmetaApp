"""Рендер настоящей спецификации: нужен Node + Playwright; пропускается при CS_SKIP_RENDER=1."""
import os
import re
import shutil
import tempfile
import unittest
from pathlib import Path

from tools.cs.common import ROOT, load_yaml, sha256_file
from tools.cs.render import build_html, formats, render_one

SPEC = ROOT / "content/items/CI-0001-personal-presentation/spec/variant-a.yaml"


@unittest.skipIf(os.environ.get("CS_SKIP_RENDER") or not shutil.which("node"), "рендер пропущен")
class RenderTest(unittest.TestCase):
    def test_render_is_local_and_reproducible(self):
        fmt = formats()["telegram_post"]
        with tempfile.TemporaryDirectory() as d:
            a, b = Path(d) / "a.jpg", Path(d) / "b.jpg"
            lay = render_one(SPEC, "ru", fmt, a)
            render_one(SPEC, "ru", fmt, b)
            self.assertEqual(lay["external_requests"], [])
            self.assertIn("IBM Plex Sans", lay["fonts_loaded"])
            self.assertEqual(sha256_file(a), sha256_file(b), "рендер не воспроизводим (Д11)")
            roles = {e["role"] for e in lay["elements"]}
            self.assertLessEqual({"portrait", "face", "name", "role"}, roles)
            self.assertTrue(all(e.get("bg_luminance") for e in lay["elements"] if e.get("text")))



class EcosystemLayoutTest(unittest.TestCase):
    """Макет ecosystem (CI-0001 v2, вариант E): карта без лица; закрытый проект без имени — только строкой (Д38, Д39)."""
    SPEC_E = ROOT / "content/items/CI-0001-personal-presentation/spec/variant-e.yaml"

    def setUp(self):
        self.spec = load_yaml(self.SPEC_E)

    def test_map_has_named_nodes_origin_and_accent(self):
        for lang in ("ru", "en"):
            t = self.spec["text"][lang]
            for fmt in ("telegram_post", "youtube_banner"):
                page = build_html(self.spec, lang, formats()[fmt])
                nodes = [n for layer in t["ecosystem"]["layers"] for n in layer["nodes"]]
                for n in nodes:
                    self.assertIn(f">{n['name']}", page)
                self.assertEqual(page.count('data-role="eco-node"'), len(nodes))
                self.assertNotIn('data-role="portrait"', page)
                # решение человека — токеном human (E-2, E-10): и в карточке, и в строке роли шапки
                self.assertRegex(page, r'<span style="color:#FF7A59">[^<]*(мои|mine)</span>')

    def test_undisclosed_project_name_never_rendered(self):
        scope = load_yaml(ROOT / "data/progress/scope.yaml")
        hidden = [r["slug"].split("/")[-1] for r in scope["repos"] if r["visibility"] == "undisclosed"]
        self.assertTrue(hidden)
        for lang in ("ru", "en"):
            for name in ("telegram_post", "github_social", "youtube_banner", "master"):
                page = build_html(self.spec, lang, formats()[name])
                for h in hidden:
                    self.assertNotIn(h.lower(), page.lower(), f"{lang}/{name}")

    def test_at_most_four_text_groups(self):
        for name in ("telegram_post", "youtube_banner"):
            page = build_html(self.spec, "ru", formats()[name])
            groups = set(re.findall(r'data-group="([^"]+)"', page))
            self.assertLessEqual(len(groups), 4, groups)


if __name__ == "__main__":
    unittest.main()
