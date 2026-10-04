"""Рендер настоящей спецификации: нужен Node + Playwright; пропускается при CS_SKIP_RENDER=1."""
import os
import shutil
import tempfile
import unittest
from pathlib import Path

from tools.cs.common import ROOT, sha256_file
from tools.cs.render import formats, render_one

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


if __name__ == "__main__":
    unittest.main()
