"""Правила tools/check: у каждого — образец, который оно ловит, и чистый образец (CLAUDE.md, «Проверки»).

Образцы — маленькие репозитории во временном каталоге; образцы секретов
собираются из частей, чтобы в самом репозитории их не было.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.check import checks

REPO = Path(__file__).resolve().parents[1]

DECISIONS_OK = """# Решения

### D-01 — Первое

**Статус.** Принято.

**Решение.** Так.

**Почему.** Потому.

**Проверка.** Тест.

### D-02 — Второе

**Статус.** Принято.

**Решение.** Так.

**Почему.** Потому.

**Проверка.** Тест.

## История

| дата | что |
"""

ROADMAP_OK = """# ROADMAP

### <a id="task-0"></a>[+] Этап 0. Постановка — `М`

- [+] Шаг.

### <a id="task-1"></a>[ ] Этап 1. Модель — `С`

- [+] Шаг.
- [ ] Шаг.
"""


class Sample:
    """Маленький репозиторий-образец (без git — обход каталога)."""

    def __init__(self) -> None:
        self.tmp = tempfile.TemporaryDirectory(prefix="dpvis-check-")
        self.root = Path(self.tmp.name)

    def write(self, rel: str, text: str) -> Path:
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def close(self) -> None:
        self.tmp.cleanup()


class CheckCase(unittest.TestCase):
    def setUp(self) -> None:
        self.s = Sample()
        self.addCleanup(self.s.close)


class TestDecisions(CheckCase):
    def test_clean(self):
        self.s.write(checks.DECISIONS, DECISIONS_OK)
        self.assertEqual(checks.check_decisions(self.s.root), [])

    def test_gap_and_missing_field(self):
        text = DECISIONS_OK.replace("### D-02", "### D-03").replace("**Почему.** Потому.\n\n**Проверка.** Тест.\n\n## История", "## История")
        self.s.write(checks.DECISIONS, text)
        problems = checks.check_decisions(self.s.root)
        self.assertTrue(any("не сплошная" in p for p in problems), problems)
        self.assertTrue(any("нет поля «Проверка»" in p for p in problems), problems)


class TestRoadmap(CheckCase):
    def test_clean_and_version(self):
        self.s.write(checks.ROADMAP, ROADMAP_OK)
        self.s.write(checks.VERSION_FILE, 'VERSION = "0.0.0"\n')
        self.assertEqual(checks.check_roadmap(self.s.root), [])

    def test_flags_and_version_mismatch(self):
        self.s.write(checks.ROADMAP, ROADMAP_OK.replace("[ ] Этап 1", "[+] Этап 1"))
        self.s.write(checks.VERSION_FILE, 'VERSION = "0.3.0"\n')
        problems = checks.check_roadmap(self.s.root)
        self.assertTrue(any("этап 1 отмечен сделанным" in p for p in problems), problems)
        self.assertTrue(any("VERSION '0.3.0'" in p for p in problems), problems)

    def test_expected_version(self):
        st = lambda *done: [{"n": i, "done": d} for i, d in enumerate(done)]
        self.assertEqual(checks.expected_version(st(False, True, True)), "0.0.0")
        self.assertEqual(checks.expected_version(st(True, True, False)), "0.1.0")
        self.assertEqual(checks.expected_version(st(True, True, True)), "1.0.0")


class TestStatus(CheckCase):
    def test_written_block_passes_and_stale_fails(self):
        self.s.write(checks.ROADMAP, ROADMAP_OK)
        self.s.write(checks.VERSION_FILE, 'VERSION = "0.0.0"\n')
        self.s.write(checks.README, "# Проект\n")
        self.assertTrue(checks.check_status(self.s.root))
        self.s.write(checks.README, checks.render_readme(self.s.root))
        self.assertEqual(checks.check_status(self.s.root), [])
        self.s.write(checks.ROADMAP, ROADMAP_OK.replace("- [ ] Шаг.", "- [+] Шаг.").replace("[ ] Этап 1", "[+] Этап 1"))
        self.assertTrue(any("отстал" in p for p in checks.check_status(self.s.root)))


class TestRefs(CheckCase):
    def setUp(self):
        super().setUp()
        self.s.write(checks.DECISIONS, DECISIONS_OK)
        self.s.write(checks.TZ, "| F-01 | требование |\n")

    def test_clean(self):
        self.s.write("docs/a.md", "По D-01 и F-01; чужое — `DPmeta:C-05`, DPmetaApp:D-18; файл `docs/a.md`; нельзя `.github/workflows/`.\n")
        self.assertEqual(checks.check_refs(self.s.root), [])

    def test_unknown_number_and_missing_file(self):
        self.s.write("docs/a.md", "По D-07 — см. `docs/нет.md`.\n")
        problems = checks.check_refs(self.s.root)
        self.assertTrue(any("D-07 нигде не определён" in p for p in problems), problems)
        self.assertTrue(any("нет файла `docs/нет.md`" in p for p in problems), problems)


class TestLayout(CheckCase):
    def test_clean(self):
        self.s.write("README.md", "#\n")
        self.s.write("tools/x.py", "")
        self.assertEqual(checks.check_layout(self.s.root), [])

    def test_forbidden(self):
        self.s.write("notes.txt", "")
        self.s.write(".github/workflows/ci.yml", "")
        self.s.write("viewer/package-lock.json", "{}")
        problems = checks.check_layout(self.s.root)
        self.assertTrue(any(p.startswith("notes.txt") for p in problems), problems)
        self.assertTrue(any("GitHub Actions" in p for p in problems), problems)
        self.assertTrue(any("npm" in p for p in problems), problems)


class TestDeps(CheckCase):
    def test_python(self):
        self.s.write("tools/a.py", "import json\nfrom pathlib import Path\nfrom . import b\nfrom tools.dpvis import model\n")
        self.assertEqual(checks.check_python_deps(self.s.root), [])
        self.s.write("tools/b.py", "import requests\n")
        self.assertTrue(any("requests" in p for p in checks.check_python_deps(self.s.root)))

    def test_viewer(self):
        self.s.write("viewer/src/a.js", 'import { b } from "./b.js";\n')
        self.assertEqual(checks.check_viewer_deps(self.s.root), [])
        self.s.write("viewer/src/c.js", 'import * as d3 from "https://cdn.example/d3.js";\nconst m = import("./x.js");\n')
        problems = checks.check_viewer_deps(self.s.root)
        self.assertTrue(any("cdn.example" in p for p in problems), problems)
        self.assertTrue(any("динамический" in p for p in problems), problems)


class TestSecrets(CheckCase):
    def test_clean(self):
        self.s.write("tools/a.py", 'token = os.environ.get("GH_TOKEN")\n')
        self.assertEqual(checks.check_secrets(self.s.root), [])

    def test_catches(self):
        gh = "gh" + "p_" + "a1B2" * 9
        key = "-----BEGIN " + "OPENSSH PRIVATE KEY-----"
        self.s.write("tools/a.py", f'X = "{gh}"\n')
        self.s.write("docs/b.md", key + "\n")
        self.s.write("tools/c.py", "api_key = " + '"' + "s3cr3t" + "Value99" + '"\n')
        self.s.write(".env", "A=1\n")
        problems = checks.check_secrets(self.s.root)
        for what in ("токен GitHub", "закрытый ключ", "пароль или токен", "файл ключей"):
            self.assertTrue(any(what in p for p in problems), (what, problems))


class TestCommands(CheckCase):
    def test_clean(self):
        self.s.write("tools/dpvis/a.py", 'TRANSPORTS = {"rsync": "rsync"}\nurllib.request.Request(url, method="GET")\n')
        self.assertEqual(checks.check_commands(self.s.root), [])

    def test_catches(self):
        self.s.write("tools/dpvis/a.py", 'subprocess.run(["rsync", "-a", src, dst])\nRequest(url, method="POST")\n')
        self.s.write("tools/dpvis/b.sh", "ssh host true\n")
        self.s.write("tools/dpvis/c.py", 'os.system("scp a b")\n')
        problems = checks.check_commands(self.s.root)
        self.assertEqual(len(problems), 3, problems)


class TestRepoFiles(unittest.TestCase):
    """Правила модели, среза и выжимки — на настоящем репозитории и его испорченной копии."""

    def test_real_repo_model_and_public(self):
        self.assertEqual(checks.check_model_valid(REPO), [])
        self.assertEqual(checks.check_model_compiled(REPO), [])
        self.assertEqual(checks.check_public(REPO), [])

    def test_stale_build_and_old_state(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for rel in ("model", "data", "schemas"):
                shutil.copytree(REPO / rel, root / rel)
            src = root / checks.MODEL_SRC
            src.write_text(src.read_text(encoding="utf-8").replace('title = "', 'title = "Изменено: ', 1), encoding="utf-8")
            self.assertTrue(any("отстал" in p for p in checks.check_model_compiled(root)))
            subprocess.run(["python3", "-m", "tools.dpvis", "model", "build", "--source", str(src),
                            "--out", str(root / checks.MODEL_OUT)], cwd=REPO, check=True, capture_output=True)
            self.assertTrue(any("старой модели" in p for p in checks.check_state(root)))

    def test_public_leak_is_caught(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "data").mkdir()
            model = json.loads((REPO / checks.MODEL_OUT).read_text(encoding="utf-8"))
            for n in model["nodes"]:
                if n["id"] == "galdhold":
                    n["public_role"] = "собирается через " + "Ter" + "mux"
            (root / checks.MODEL_OUT).write_text(json.dumps(model, ensure_ascii=False), encoding="utf-8")
            self.assertTrue(checks.check_public(root))


if __name__ == "__main__":
    unittest.main()
