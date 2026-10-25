"""Форматы: модель и срез проходят схемы; поломки ловятся (F-39, D-07, A-12)."""

import copy
import json
import unittest
from pathlib import Path

from tests.fixture import base_source, model_from, utc
from tools.dpvis import collect, formats

REPO = Path(__file__).resolve().parents[1]


class FormatsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.model = model_from(base_source())
        cls.state = collect.collect(cls.model, None, utc("2026-10-05T12:00:00"))

    def test_model_and_state_pass(self):
        self.assertEqual(formats.validate_document(self.model), [])
        self.assertEqual(formats.validate_document(self.state), [])
        self.assertEqual(formats.validate_pair(self.model, self.state), [])

    def test_committed_model_passes(self):
        data = json.loads((REPO / "data" / "model.json").read_text(encoding="utf-8"))
        self.assertEqual(formats.validate_document(data), [])

    def test_unknown_schema(self):
        self.assertIn("неизвестная схема", formats.validate_document({"schema": "x/9"})[0])

    def test_wrong_enum_in_model(self):
        bad = copy.deepcopy(self.model)
        bad["flows"][0]["kind"] = "magic"
        self.assertTrue(any("flows[0].kind" in p for p in formats.validate_document(bad)))

    def test_missing_field_in_state(self):
        bad = copy.deepcopy(self.state)
        del bad["nodes"]["alpha"]["activity"]
        self.assertTrue(any("нет поля 'activity'" in p for p in formats.validate_document(bad)))

    def test_bad_time_format(self):
        bad = copy.deepcopy(self.state)
        bad["as_of"] = "вчера"
        self.assertTrue(any("$.as_of" in p for p in formats.validate_document(bad)))

    def test_unknown_diag_code_is_allowed(self):
        future = copy.deepcopy(self.state)
        future["diagnostics"].append({"code": "NEW_CODE_LATER", "subject": "x", "message": "y"})
        self.assertEqual(formats.validate_document(future), [])

    def test_extra_fields_are_allowed(self):
        future = copy.deepcopy(self.state)
        future["something_new"] = {"a": 1}
        future["nodes"]["alpha"]["extra"] = True
        self.assertEqual(formats.validate_document(future), [])

    def test_pair_detects_unknown_node_and_bad_days(self):
        bad = copy.deepcopy(self.state)
        bad["nodes"]["zzz"] = {"activity": {"state": "idle", "last": None, "reasons": []}}
        bad["flows"]["work.alpha"]["days"] = [0, 1]
        found = formats.validate_pair(self.model, bad)
        self.assertTrue(any("'zzz'" in p for p in found))
        self.assertTrue(any("work.alpha" in p for p in found))

    def test_unsupported_schema_keyword_is_error(self):
        with self.assertRaises(formats.SchemaError):
            formats.check(1, {"oneOf": []}, {})


if __name__ == "__main__":
    unittest.main()
