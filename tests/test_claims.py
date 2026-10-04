import unittest
from pathlib import Path

from tools.cs.claims import numbers, scan
from tools.cs.facts import registry, rules


def hit_ids(text):
    return {r["id"] for r, _ in scan(text, rules())}


class NumbersTest(unittest.TestCase):
    def test_thousands_forms_are_equal(self):
        self.assertEqual(numbers("150 000+ строк"), {150000.0})
        self.assertEqual(numbers("150,000+ lines"), {150000.0})
        self.assertEqual(numbers("больше 150 тысяч строк"), {150000.0})

    def test_dates_and_years_are_not_numbers(self):
        self.assertEqual(numbers("10.2026 · 04.10.2026 · 4 октября 2026 · Oct 2026"), set())

    def test_tokens_glued_to_words_are_skipped(self):
        self.assertEqual(numbers("C++20 · Poco X7 Pro 5G"), set())

    def test_decimal_comma(self):
        self.assertEqual(numbers("GPU 11,6–12,4 мс"), {11.6, 12.4})

    def test_registry_backs_variant_numbers(self):
        reg = registry()
        allowed = set()
        for fid in ("GH-LOC", "GH-CHECKS"):
            f = reg[fid]
            for t in [f["statement"]] + [w for ws in f["allowed_wording"].values() for w in ws]:
                allowed |= numbers(t)
        self.assertLessEqual(numbers("150 000+ строк C++ · 5 000+ автоматических проверок"), allowed)


class ForbiddenTest(unittest.TestCase):
    def test_known_bad_phrases(self):
        self.assertIn("CL-PATENT", hit_ids("Я запатентовал движок"))
        self.assertIn("CL-WINDOWS-RUNS", hit_ids("игра работает на Windows"))
        self.assertIn("AH-AI-PRAISE", hit_ids("ИИ оценил проект на 9.5/10"))
        self.assertIn("AH-SPEEDUP", hit_ids("в 10 раз быстрее"))
        self.assertIn("CL-CI", hit_ids("каждую правку проверяет CI"))
        self.assertIn("CL-OPEN-SOURCE", hit_ids("open-source движок"))
        self.assertIn("CL-PLATFORM-ARROW", hit_ids("Galdhold · Android → Windows (Steam)"))

    def test_old_name_allowed_with_marker(self):
        self.assertIn("CL-OLD-NAME", hit_ids("Новая сборка VoxelRPG"))
        self.assertNotIn("CL-OLD-NAME", hit_ids("Galdhold (прежде VoxelRPG)"))

    def test_clean_text(self):
        self.assertEqual(hit_ids("Разработчик нативных систем · ИИ как инструмент"), set())


class KeywordsTest(unittest.TestCase):
    def findings(self, text, fids):
        from tools.cs.claims import text_findings
        from tools.cs.common import Item
        item = Item(Path("."), {"id": "CI-0000"})
        return [f.msg for f in text_findings(item, text, fids, "t", False, registry(), rules()) if f.level == "error"]

    def test_word_needs_fact(self):
        self.assertTrue(any("GH-ENGINE" in m for m in self.findings("Игра и движок", [])))
        self.assertEqual(self.findings("Игра и движок", ["GH-ENGINE"]), [])
        self.assertTrue(any("KW-WINDOWS" in m for m in self.findings("версия для Windows", ["GH-NAME"])))


if __name__ == "__main__":
    unittest.main()
