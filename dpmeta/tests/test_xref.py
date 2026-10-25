"""Самотесты tools/xref.py на синтетических репозиториях.

Запуск: python3 -B -m unittest discover -s tests
"""

import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))

import xref  # noqa: E402

REGISTRY = """\
schema = 1

[[repo]]
name = "Alpha"
slug = "o/Alpha"
clone = "alpha"
aliases = ["OldAlpha"]
decisions = { path = "DECISIONS.md", id = "D-\\\\d+" }

[[repo]]
name = "Beta"
slug = "o/Beta"
clone = "beta"
names = ["Beta Tool"]
decisions = { path = "docs/DECISIONS.md", id = "Р\\\\d+" }
defs = [{ path = "docs/SPEC.md", id = "F-\\\\d+" }]
"""


def make_repo(path: Path, files: dict[str, str]) -> None:
    path.mkdir(parents=True)
    for rel, text in files.items():
        p = path / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(textwrap.dedent(text), encoding="utf-8")
    subprocess.run(["git", "init", "-q", str(path)], check=True)
    subprocess.run(["git", "-C", str(path), "add", "-A"], check=True)


class XrefTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.registry = self.root / "ecosystem.toml"
        self.registry.write_text(REGISTRY, encoding="utf-8")
        make_repo(self.root / "alpha", {
            "DECISIONS.md": "### D-01 — первое\n\n### D-02 — второе\n",
            "README.md": "Строка без ссылок.\n",
            "docs/guide.md": "Справка.\n",
        })
        make_repo(self.root / "beta", {
            "docs/DECISIONS.md": "### Р7. Решение\n",
            "docs/SPEC.md": "| F-03 | требование |\n**F-04** другое\n",
            "notes.md": """\
                Ссылки: `Alpha:D-01`, Alpha D-02, D-01 Alpha.
                Несуществующий: `Alpha:D-09`.
                Чужой номер в скобках не считается: решение Alpha (Р7).
                Путь есть: `Alpha:README.md`; пути нет: `Alpha:docs/NONE.md`.
                Прежнее имя: OldAlpha:D-02.
                Команда: `Alpha:README.md --flag`; маска `Alpha:docs/**`.
                """,
        })

    def tearDown(self):
        self.tmp.cleanup()

    def run_xref(self, *args):
        out = self.root / "report.md"
        code = xref.main(["--registry", str(self.registry), "--root", str(self.root),
                          "--out", str(out), *args])
        return code, out.read_text(encoding="utf-8")

    def test_ids_are_collected_from_definitions_only(self):
        repos = {r.name: r for r in xref.load_registry(self.registry)}
        ids = xref.collect_ids(self.root / "beta", [repos["Beta"].decisions, *repos["Beta"].defs])
        self.assertEqual(ids, {"Р7", "F-03", "F-04"})

    def test_report_counts_and_unresolved(self):
        code, report = self.run_xref()
        self.assertEqual(code, 0)
        # Строка 1: D-01 (`:`) и D-02 (пробел); D-01 перед именем — тот же номер
        # в той же строке, не повторяется. Строка 2: D-09. Строка 5: D-02 через
        # прежнее имя.
        self.assertIn("Ссылок распознано: 4; нет такого номера: 1.", report)
        self.assertIn("Beta `notes.md:2` → Alpha `D-09`", report)
        self.assertNotIn("`Р7`", report.split("## Пути")[0].split("## Номера")[1])
        # README.md есть; docs/NONE.md нет; `--flag` и маска — не пропажа.
        self.assertIn("пути нет в клоне: 1.", report)
        self.assertIn("Alpha:`docs/NONE.md`", report)
        self.assertIn("| Beta | Alpha | `OldAlpha` | 1 | 1 |", report)

    def test_strict_fails_only_for_named_source(self):
        code, _ = self.run_xref("--strict", "Beta")
        self.assertEqual(code, 1)
        code, _ = self.run_xref("--strict", "Alpha")
        self.assertEqual(code, 0)

    def test_abbreviated_path_is_not_missing(self):
        # `Alpha:docs/…` и `Alpha:…/guide.md` — сокращения, как маска `**`.
        (self.root / "beta" / "short.md").write_text(
            "Сокращения: `Alpha:docs/…`, `Alpha:…/guide.md`.\n", encoding="utf-8")
        _, report = self.run_xref("--only", "Beta")
        self.assertIn("пути нет в клоне: 1.", report)
        self.assertNotIn("…", report.split("## Пути")[1].split("## Прежние")[0])

    def test_untracked_files_are_scanned(self):
        (self.root / "beta" / "new.md").write_text("Ссылка `Alpha:D-02`.\n", encoding="utf-8")
        _, report = self.run_xref("--only", "Beta")
        self.assertIn("Ссылок распознано: 5;", report)

    def test_missing_clone_is_reported_not_fatal(self):
        code, report = self.run_xref("--clone", f"Alpha={self.root / 'nowhere'}")
        self.assertEqual(code, 0)
        self.assertIn("| Alpha | нет клона | — |", report)

    def test_clone_is_found_by_repo_name(self):
        # Реестр ждёт `alpha`, а клон лежит как `o/Alpha` — так сессии кладут
        # публичные репозитории.
        (self.root / "o").mkdir()
        (self.root / "alpha").rename(self.root / "o" / "Alpha")
        code, report = self.run_xref()
        self.assertEqual(code, 0)
        self.assertNotIn("нет клона", report)
        self.assertIn("Ссылок распознано: 4; нет такого номера: 1.", report)

    def test_subsystem_directory_is_a_clone(self):
        # Подсистема в чужом репозитории (DPmeta в `DPmetaApp:dpmeta/`, M-14):
        # своего `.git` нет, но каталог — внутри рабочего дерева.
        host = self.root / "host"
        host.mkdir()
        subprocess.run(["git", "init", "-q", str(host)], check=True)
        (self.root / "alpha" / ".git").rename(self.root / "alpha-git")
        (self.root / "alpha").rename(host / "alpha")
        subprocess.run(["git", "-C", str(host), "add", "-A"], check=True)
        code, report = self.run_xref("--clone", f"Alpha={host / 'alpha'}")
        self.assertEqual(code, 0)
        self.assertNotIn("нет клона", report)
        self.assertIn("Ссылок распознано: 4; нет такого номера: 1.", report)
        self.assertIn("пути нет в клоне: 1.", report)
        # Ссылка из подсистемы считается только за неё, а не ещё раз за
        # репозиторий, в каталоге которого она лежит.
        (host / "alpha" / "ref.md").write_text("`Alpha:D-01`, `Beta:Р7`.\n", encoding="utf-8")
        (self.root / "beta" / ".git").rename(self.root / "beta-git")
        for item in (self.root / "beta").iterdir():
            item.rename(host / item.name)
        subprocess.run(["git", "-C", str(host), "add", "-A"], check=True)
        _, report = self.run_xref("--clone", f"Alpha={host / 'alpha'}", "--clone", f"Beta={host}")
        self.assertIn("Ссылок распознано: 5; нет такого номера: 1.", report)
        self.assertNotIn("`alpha/ref.md", report)
        # Простой каталог вне рабочего дерева — не клон.
        plain = self.root / "plain"
        plain.mkdir()
        _, report = self.run_xref("--clone", f"Alpha={plain}")
        self.assertIn("| Alpha | нет клона | — |", report)

    def test_bad_registry_and_unknown_name_give_code_2(self):
        self.registry.write_text("schema = 2\n", encoding="utf-8")
        self.assertEqual(xref.main(["--registry", str(self.registry)]), 2)
        self.registry.write_text(REGISTRY, encoding="utf-8")
        self.assertEqual(xref.main(["--registry", str(self.registry), "--only", "Gamma"]), 2)


if __name__ == "__main__":
    unittest.main()
