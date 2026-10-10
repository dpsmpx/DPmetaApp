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
| SQ-01 | Alpha | ссылка `x` устарела | высокий | открыт — активная разработка |
| SQ-02 | Alpha, Beta_Tool | общий адрес | средний (публичный адрес) | открыт |
| SQ-03 | Alpha | уже сделано | низкий | сделано: `dpmeta/docs-sync` |
| SQ-04 | Old | архивное | высокий | открыт |
| SQ-05 | Alpha | ждёт решения по риску | высокий | открыт — RISK-01 |
| SQ-06 | Alpha | README правит автор | низкий | открыт — за автором |
| SQ-07 | Alpha | вопрос | высокий | открыт |
| SQ-08 | Alpha | без предложения | низкий | открыт |
| SQ-09 | Alpha | сверить с правкой автора | низкий | частично: сверить с правкой автора |

## Alpha

### SQ-01 — Ссылка `x` устарела

- **Вид:** наблюдение. **Риск:** высокий.
- **Где:** `docs/A.md:3`.
- **Предложение:** заменить `x` на `y`
  во всех **документах**.
- **Повторный проход:** то же.
- **Состояние:** открыт — активная разработка.

### SQ-02 — Общий адрес

- **Вид:** наблюдение.
- **Где и что** (сверено на `abc1234`): адрес в двух местах.
- **Предложение:** сессии Alpha — сменить адрес; сессии Beta_Tool — тоже.
- **Состояние:** открыт.

### SQ-05 — Ждёт решения по риску

- **Вид:** наблюдение.
- **Предложение:** что-то сделать.
- **Состояние:** открыт — RISK-01.

### SQ-06 — README правит автор

- **Вид:** наблюдение.
- **Предложение:** убрать значок.
- **Состояние:** открыт — за автором.

### SQ-07 — Вопрос

- **Вид:** открытый вопрос. **Риск:** высокий.
- **Предложение:** решить.
- **Состояние:** открыт.

### SQ-08 — Без предложения

- **Вид:** наблюдение.
- **Состояние:** открыт.

### SQ-09 — Сверить с правкой автора

- **Вид:** наблюдение.
- **Предложение:** сверить черновик.
- **Состояние:** частично: сверить с правкой автора.
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

    def test_suggestions_are_what_a_session_can_do(self):
        # M-11: без RISK-NN, без «за автором», не «открытый вопрос», только с «Предложением»
        _, text = self.run_tool()
        alpha, beta = json.loads(text)["projects"]
        # SQ-09: слово «автора» в состоянии — не «ждёт автора»
        self.assertEqual([s["key"] for s in alpha["suggestions"]], ["sq-01", "sq-02", "sq-09"])
        self.assertEqual([s["key"] for s in beta["suggestions"]], ["sq-02"])
        self.assertEqual({s["operation"] for s in alpha["suggestions"]}, {"docs"})

    def test_priority_is_not_risk(self):
        _, text = self.run_tool()
        alpha = json.loads(text)["projects"][0]
        self.assertEqual({s["priority"] for s in alpha["suggestions"]}, {"LOW"}, "SQ-01 с высоким риском — тоже LOW")

    def test_objective_has_where_and_stable_reference(self):
        _, text = self.run_tool()
        first, second = json.loads(text)["projects"][0]["suggestions"][:2]
        self.assertEqual(first["objective"],
                         "Где: docs/A.md:3. Предложение: заменить x на y во всех документах. (DPmeta:SQ-01)")
        self.assertNotIn("состояние", first["objective"])
        self.assertIn("Где: (сверено на abc1234): адрес в двух местах.", second["objective"])
        self.assertIn("Пункт касается и Beta_Tool: здесь — только часть для Alpha.", second["objective"])

    def test_objective_does_not_change_with_state(self):
        before = self.run_tool()[1]
        self.queue.write_text(QUEUE.replace("- **Состояние:** открыт — активная разработка.",
                                            "- **Состояние:** открыт."), encoding="utf-8")
        self.assertEqual(before, self.run_tool()[1])

    def test_unwritable_out_is_code_2(self):
        code = manifest.main(["--registry", str(self.registry), "--queue", str(self.queue),
                              "--root", str(self.clones), "--out", str(Path(self.tmp.name) / "no" / "m.json")])
        self.assertEqual(code, 2)

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

    def test_summary_agrees_with_item_state(self):
        # сводка и поле «Состояние» пункта говорят одно: закрыт, открыт или ждёт автора
        queue = manifest.read_queue((ROOT / "docs" / "SYNC_QUEUE.md").read_text(encoding="utf-8"))
        for num, item in queue.items():
            with self.subTest(num):
                if "Состояние" in item["fields"]:
                    self.assertEqual(manifest.status(item["state"]), manifest.item_status(item))

    def test_real_suggestions_have_proposal_and_dpmeta_paths(self):
        repos = xref.load_registry(ROOT / "tools" / "ecosystem.toml")
        queue = (ROOT / "docs" / "SYNC_QUEUE.md").read_text(encoding="utf-8")
        doc = json.loads(manifest.build(repos, {}, queue))
        suggestions = [s for p in doc["projects"] for s in p["suggestions"]]
        self.assertTrue(suggestions)
        for s in suggestions:
            with self.subTest(s["key"]):
                self.assertIn("Предложение: ", s["objective"])
                # свои документы DPmeta — только с префиксом проекта (их нет в репозиториях проектов)
                for own in ("docs/NAMING.md", "docs/SYNC_QUEUE.md", "docs/CONTRACTS.md", "docs/RISKS.md",
                            "docs/PRINCIPLES.md"):
                    self.assertNotRegex(s["objective"], rf"(?<![\w:/]){own}")

    def test_every_open_item_has_a_proposal(self):
        # пункт, который сессия может сделать, без «Предложения» молча выпал бы из манифеста (C-13)
        archive = {r.name for r in xref.load_registry(ROOT / "tools" / "ecosystem.toml") if r.place == "archive"}
        queue = manifest.read_queue((ROOT / "docs" / "SYNC_QUEUE.md").read_text(encoding="utf-8"))
        for num, item in queue.items():
            if manifest.item_status(item) == "open" and set(item["repos"]) - archive:
                with self.subTest(num):
                    self.assertTrue(item["proposal"], f"{num}: открыт, а поля «Предложение» нет")

    def test_dpmeta_paths_in_open_and_blocked_items(self):
        # и пункты, которые ждут автора, уйдут в манифест, когда он решит, — пути DPmeta в них с префиксом
        queue = manifest.read_queue((ROOT / "docs" / "SYNC_QUEUE.md").read_text(encoding="utf-8"))
        for num, item in queue.items():
            if manifest.item_status(item) != "closed":
                with self.subTest(num):
                    self.assertNotRegex(item["proposal"], r"(?<![\w:/])docs/NAMING\.md")


if __name__ == "__main__":
    unittest.main()
