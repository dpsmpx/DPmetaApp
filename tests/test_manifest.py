"""Самотесты tools/manifest.py (M-11, C-13) на синтетических репозиториях.

Запуск: python3 -B -m unittest discover -s tests
"""

import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import manifest  # noqa: E402
import xref  # noqa: E402
from test_activity import commit, git  # noqa: E402

REGISTRY = """\
schema = 1

[[repo]]
name = "Alpha"
slug = "o/Alpha"
clone = "alpha"
place = "core"
decisions = { path = "docs/DECISIONS.md", id = "D-\\\\d+" }

[[repo]]
name = "Beta_Tool"
slug = "o/Beta_Tool"
clone = "beta"
place = "support"

[[repo]]
name = "Old"
slug = "o/Old"
clone = "old"
place = "archive"
"""

QUEUE = """\
# Очередь

## Сводка

| № | репозиторий | что | риск | состояние |
|---|---|---|---|---|
| SQ-01 | Alpha | ссылка `x` устарела | низкий | открыт — активная разработка |
| SQ-02 | Alpha, Beta_Tool | общий адрес | средний (публичный адрес) | открыт — RISK-01 |
| SQ-03 | Alpha | уже сделано | низкий | сделано: `dpmeta/docs-sync` |
| SQ-04 | Old | архивное | высокий | открыт |

## Alpha

### SQ-01 — Ссылка `x` устарела

- **Вид:** наблюдение. **Риск:** низкий.
- **Предложение:** заменить `x` на `y`
  во всех **документах**.
- **Повторный проход:** то же.
- **Состояние:** открыт.

### SQ-02 — Общий адрес

- **Вид:** наблюдение.
- **Состояние:** открыт.
"""

ROOT_FIELDS = {"format", "version", "projects"}
PROJECT_FIELDS = {"key", "name", "repository", "state", "documents", "suggestions"}
SUGGESTION_FIELDS = {"key", "title", "objective", "priority", "operation"}
PRIORITIES = {"CRITICAL", "HIGH", "NORMAL", "LOW", "BACKGROUND"}


def assert_reader_accepts(test: unittest.TestCase, text: str) -> dict:
    """Ограничения строгого чтения `ManifestEcosystem` (`DP_AI_Orcestrator:docs/ECOSYSTEM.md`, §2)."""
    doc = json.loads(text)
    test.assertEqual(set(doc), ROOT_FIELDS)
    test.assertEqual((doc["format"], doc["version"]), ("dpai.ecosystem", 1))
    keys = [p["key"] for p in doc["projects"]]
    test.assertEqual(len(keys), len(set(keys)), "ключи проектов не повторяются")
    for p in doc["projects"]:
        test.assertLessEqual(set(p), PROJECT_FIELDS)
        test.assertTrue(isinstance(p["key"], str) and p["key"] and isinstance(p["name"], str))
        test.assertTrue(all(isinstance(k, str) and isinstance(v, str) for k, v in p["state"].items()))
        test.assertTrue(all(isinstance(d, str) for d in p["documents"]))
        for s in p["suggestions"]:
            test.assertEqual(set(s), SUGGESTION_FIELDS)
            test.assertTrue(all(isinstance(v, str) and v for v in s.values()))
            test.assertIn(s["priority"], PRIORITIES)
    return doc


class ManifestTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        self.clones = base / "clones"
        self.registry = base / "ecosystem.toml"
        self.registry.write_text(REGISTRY, encoding="utf-8")
        self.queue = base / "SYNC_QUEUE.md"
        self.queue.write_text(QUEUE, encoding="utf-8")
        # Alpha — клон с основной веткой; Beta_Tool — без клона; Old — архив.
        origin = base / "origin-alpha"
        origin.mkdir()
        git(origin, "init", "-q", "-b", "main")
        (origin / "docs").mkdir()
        (origin / "docs" / "DECISIONS.md").write_text("# D\n", encoding="utf-8")
        (origin / "CLAUDE.md").write_text("# C\n", encoding="utf-8")
        commit(origin, "README.md", "# A\n", "2026-10-07T10:00:00+00:00")
        self.clones.mkdir()
        git(self.clones, "clone", "-q", str(origin), "alpha")

    def run_tool(self, *extra: str) -> tuple[int, str]:
        out = Path(self.tmp.name) / "m.json"
        code = manifest.main(["--registry", str(self.registry), "--queue", str(self.queue),
                              "--root", str(self.clones), "--out", str(out), *extra])
        return code, out.read_text(encoding="utf-8") if out.exists() else ""

    def test_manifest_is_readable_by_orchestrator(self):
        code, text = self.run_tool()
        self.assertEqual(code, 0)
        doc = assert_reader_accepts(self, text)
        self.assertEqual([p["key"] for p in doc["projects"]], ["alpha", "beta_tool"])

    def test_archive_is_left_out(self):
        _, text = self.run_tool()
        self.assertNotIn("Old", {p["name"] for p in json.loads(text)["projects"]})

    def test_state_and_documents_from_clone(self):
        _, text = self.run_tool()
        alpha, beta = json.loads(text)["projects"]
        self.assertEqual(alpha["state"]["place"], "core")
        self.assertEqual((alpha["state"]["branch"], alpha["state"]["updated"]), ("main", "2026-10-07"))
        self.assertEqual(len(alpha["state"]["head"]), 7)
        self.assertEqual(alpha["documents"], ["CLAUDE.md", "README.md", "docs/DECISIONS.md"])
        self.assertEqual(beta["state"], {"place": "support", "clone": "нет"})
        self.assertEqual(beta["documents"], [])

    def test_suggestions_are_open_queue_items(self):
        _, text = self.run_tool()
        alpha, beta = json.loads(text)["projects"]
        self.assertEqual([s["key"] for s in alpha["suggestions"]], ["sq-01", "sq-02"])
        first, second = alpha["suggestions"]
        self.assertEqual(first["objective"].split(" (DPmeta:")[0], "заменить x на y во всех документах.")
        self.assertEqual((first["priority"], second["priority"]), ("LOW", "NORMAL"))
        self.assertEqual(second["objective"].split(" (DPmeta:")[0], "общий адрес", "без «Предложения» — заголовок")
        self.assertEqual([s["key"] for s in beta["suggestions"]], ["sq-02"])
        self.assertEqual({s["operation"] for s in alpha["suggestions"]}, {"docs"})

    def test_repository_only_with_paths(self):
        _, text = self.run_tool()
        self.assertNotIn("repository", json.loads(text)["projects"][0])
        _, text = self.run_tool("--paths")
        self.assertEqual(json.loads(text)["projects"][0]["repository"], str((self.clones / "alpha").resolve()))

    def test_same_input_same_bytes(self):
        self.assertEqual(self.run_tool()[1], self.run_tool()[1])

    def test_missing_place_is_refused(self):
        self.registry.write_text(REGISTRY.replace('place = "support"\n', ""), encoding="utf-8")
        self.assertEqual(self.run_tool()[0], 2)

    def test_unknown_place_is_refused(self):
        self.registry.write_text(REGISTRY.replace('"support"', '"periphery"'), encoding="utf-8")
        with self.assertRaises(ValueError):
            xref.load_registry(self.registry)
        self.assertEqual(self.run_tool()[0], 2)

    def test_duplicate_keys_are_refused(self):
        self.registry.write_text(REGISTRY.replace('name = "Beta_Tool"', 'name = "alpha"'), encoding="utf-8")
        self.assertEqual(self.run_tool()[0], 2)


class RealRegistryTest(unittest.TestCase):
    def test_every_repo_has_a_place_and_manifest_builds(self):
        repos = xref.load_registry(ROOT / "tools" / "ecosystem.toml")
        self.assertEqual([r.name for r in repos if not r.place], [])
        self.assertEqual({r.name for r in repos if r.place == "meta"}, {"DPmeta"})
        queue = (ROOT / "docs" / "SYNC_QUEUE.md").read_text(encoding="utf-8")
        assert_reader_accepts(self, manifest.build(repos, {}, queue))

    def test_every_queue_row_is_parsed(self):
        text = (ROOT / "docs" / "SYNC_QUEUE.md").read_text(encoding="utf-8")
        rows = [line for line in text.splitlines() if line.startswith("| SQ-")]
        self.assertEqual(len(manifest.read_queue(text)), len(rows))


if __name__ == "__main__":
    unittest.main()
