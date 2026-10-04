import tempfile
import unittest
from pathlib import Path
from unittest import mock

from tools.cs import common, newitem
from tools.cs.validate import item_findings


class NewItemTest(unittest.TestCase):
    def test_create_valid_idea(self):
        with tempfile.TemporaryDirectory() as d:
            items = Path(d)
            with mock.patch.object(common, "ITEMS_DIR", items), mock.patch.object(newitem, "ITEMS_DIR", items):
                iid = newitem.create("post", "RESULT", "first-clip", "Показать новый бой одним клипом")
                self.assertEqual(iid, "CI-0001")
                self.assertEqual(newitem.next_id(), "CI-0002")
                item = common.load_items()[0]
                self.assertEqual(item.data["state"], "IDEA")
                self.assertEqual([f for f in item_findings(item) if f.level == "error"], [])

    def test_rejects_bad_input(self):
        with self.assertRaises(SystemExit):
            newitem.create("tweet", "RESULT", "x", "Цель объекта контента")
        with self.assertRaises(SystemExit):
            newitem.create("post", "RESULT", "Bad Slug", "Цель объекта контента")


class ItemRulesTest(unittest.TestCase):
    def item(self, d, **kw):
        data = {"schema": "content_item/v1", "id": "CI-0009", "slug": "t", "title": "тест",
                "type": "post", "pillar": "RESULT", "objective": "Проверить правила состояний",
                "created": "2026-10-04", "state": "IDEA",
                "history": [{"date": "2026-10-04", "state": "IDEA", "note": "заведён"}]}
        data.update(kw)
        p = Path(d) / "CI-0009-t"
        p.mkdir()
        return common.Item(p, data)

    def errors(self, item):
        return [f.msg for f in item_findings(item) if f.level == "error"]

    def test_skipped_states(self):
        with tempfile.TemporaryDirectory() as d:
            hist = [{"date": "2026-10-04", "state": s, "note": "шаг"} for s in ("IDEA", "BRIEFED")]
            errs = self.errors(self.item(d, state="BRIEFED", history=hist))
            self.assertTrue(any("IDEA → BRIEFED" in e for e in errs))
            self.assertTrue(any("файл брифа" in e for e in errs))

    def test_brand_gate_only_by_author(self):
        with tempfile.TemporaryDirectory() as d:
            errs = self.errors(self.item(d, gates={"brand": {"status": "pass", "by": "session"}}))
            self.assertTrue(any("brand" in e for e in errs))

    def test_publication_requires_published_state(self):
        with tempfile.TemporaryDirectory() as d:
            pub = [{"platform": "telegram", "state": "published", "url": "https://t.me/x/1", "published_at": "2026-10-04"}]
            errs = self.errors(self.item(d, publication=pub))
            self.assertTrue(any("публикация" in e for e in errs))


if __name__ == "__main__":
    unittest.main()
