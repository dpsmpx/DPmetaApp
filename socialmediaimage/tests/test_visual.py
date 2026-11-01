import unittest

from tools.cs.common import ROOT, load_json
from tools.cs.visual import contrast, layout_checks, luminance

TOK = load_json(ROOT / "assets/brand/tokens.json")
FMT = {"width": 1280, "height": 720}
INK = luminance(TOK["color"]["ink"]["hex"])


def el(role, group, text, x, y, w, h, px=20.0, fg="paper", bg=INK):
    return {"role": role, "group": group, "text": text, "x": x, "y": y, "w": w, "h": h,
            "text_box": {"x": x, "y": y, "w": w, "h": h}, "font_px": px, "font_weight": 400,
            "font_family": "\"IBM Plex Sans\", sans-serif", "fg": fg,
            "bg_luminance": {"p95": bg, "max": bg}, "overflow": False}


def layout(*elements):
    return {"width": 1280, "height": 720, "fonts_loaded": ["IBM Plex Sans", "IBM Plex Mono"],
            "external_requests": [], "elements": list(elements)}


def errors(res):
    return [f.msg for k in res for f in res[k] if f.level == "error"]


class VisualTest(unittest.TestCase):
    def test_good_layout_passes(self):
        res = layout_checks(layout(el("name", "name", "dpsmpx", 600, 100, 300, 60, 61),
                                   el("role", "identity", "Разработчик", 600, 190, 400, 30, 26)), FMT, TOK, "t")
        self.assertEqual(errors(res), [])

    def test_small_text(self):
        res = layout_checks(layout(el("sub", "identity", "мелко", 10, 10, 100, 14, 12)), FMT, TOK, "t")
        self.assertTrue(any("12.0 px" in e for e in errors(res)))

    def test_low_contrast_on_light_background(self):
        light = luminance("#DDDDDD")
        self.assertLess(contrast(luminance(TOK["color"]["paper"]["hex"]), light), 3)
        res = layout_checks(layout(el("role", "identity", "по фото", 10, 10, 200, 30, 20, bg=light)), FMT, TOK, "t")
        self.assertTrue(any("контраст" in e for e in errors(res)))

    def test_too_many_groups_and_overlap(self):
        els = [el("r", f"g{i}", f"текст {i}", 10, 40 * i, 100, 30) for i in range(5)]
        els.append(el("x", "g0", "наложение", 20, 5, 100, 30))
        msgs = errors(layout_checks(layout(*els), FMT, TOK, "t"))
        self.assertTrue(any("групп текста 5" in m for m in msgs))
        self.assertTrue(any("накладывается" in m for m in msgs))

    def test_safe_area_and_foreign_font(self):
        fmt = {"width": 1280, "height": 720, "safe_area": {"x": 200, "y": 200, "w": 800, "h": 300}}
        e = el("name", "name", "dpsmpx", 10, 10, 200, 40, 40)
        e["font_family"] = "Arial"
        res = layout_checks(layout(e), fmt, TOK, "t")
        self.assertTrue(res["platform"] and res["legal"])

    def test_margin(self):
        res = layout_checks(layout(el("ev", "evidence", "у края", 1240, 300, 30, 20)), FMT, TOK, "t")
        self.assertTrue(any("к краю" in m for m in errors(res)))

    def test_more_than_three_sizes(self):
        els = [el("r", "g", f"т{i}", 10, 50 * i, 100, 30, 20 + 4 * i) for i in range(4)]
        self.assertTrue(any("размеров текста 4" in m for m in errors(layout_checks(layout(*els), FMT, TOK, "t"))))

    def test_rejected_variant_is_soft(self):
        res = layout_checks(layout(el("sub", "identity", "мелко", 10, 10, 100, 14, 12)), FMT, TOK, "t", soft=True)
        self.assertEqual(errors(res), [])


if __name__ == "__main__":
    unittest.main()
