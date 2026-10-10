"""Самотесты tools/check/check.py: у каждого правила — образец, который оно ловит, и чистый образец (CLAUDE.md, «Проверки»).

Образцы — временные репозитории (`git init` без коммитов: проверки читают `git ls-files --others`).
Секреты в образцах склеиваются при запуске, чтобы этот файл не ловился security.secrets.
"""
import importlib.util
import os
import stat
import subprocess
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_spec = importlib.util.spec_from_file_location("dpmetaapp_check", os.path.join(ROOT, "tools", "check", "check.py"))
check = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(check)

ROADMAP_TEXT = """# ROADMAP

## Версия 1.0

### <a id="task-0"></a>[ ] Этап 0. Согласование — `М`

- [+] Постановка.
- [ ] ТЗ влито.
"""

DECISIONS_TEXT = """# Решения

### D-01 — Образец решения

**Статус.** Принято.

**Решение.** Так.

**Почему.** Потому что.

**Проверка.** Этим тестом.

## История
"""

CLEAN = {
    "CLAUDE.md": "# Правила\n\nРешение D-01; цель G-01; чужой контракт `DPmeta:C-06`; путь `docs/PROCESS.md`.\n",
    "TODO.md": "# TODO\n\n- `М` Пункт.\n",
    "LICENSE": "All rights reserved.\n",
    ".gitignore": "build/\n",
    check.DECISIONS: DECISIONS_TEXT,
    check.TZ: "# ТЗ\n\n| № | Что |\n|---|---|\n| G-01 | цель |\n",
    check.ROADMAP: ROADMAP_TEXT,
    "docs/direction/REQUEST.md": "# Постановка\n\nСм. [решения](DECISIONS.md#d-01--образец-решения).\n",
    "docs/PROCESS.md": "# Процесс\n",
    "docs/PROVENANCE.md": "# Происхождение\n",
    ".claude/settings.json": "{}\n",
    ".claude/hooks/session-start.sh": "#!/bin/sh\necho start\n",
    ".github/pull_request_template.md": "## Что\n",
    "tools/check/run.sh": "#!/bin/sh\nexit 0\n",
    "tools/check/check.py": "print('ok')\n",
}


def readme_for(roadmap):
    return "# DPmetaApp\n\n" + check.status_block(roadmap) + "\n"


class Sample:
    """Временный репозиторий-образец: чистый набор файлов и правки к нему."""

    def __init__(self, changes=None, remove=()):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name
        subprocess.run(["git", "init", "-q", self.root], check=True)
        files = dict(CLEAN)
        files[check.README] = readme_for(files[check.ROADMAP])
        for rel, text in (changes or {}).items():
            files[rel] = text
        for rel in remove:
            files.pop(rel, None)
        for rel, text in files.items():
            if text is None:
                continue
            self.write(rel, text)

    def write(self, rel, text):
        path = os.path.join(self.root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        data = text if isinstance(text, bytes) else text.encode("utf-8")
        with open(path, "wb") as f:
            f.write(data)
        if rel.endswith(".sh"):
            os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR)
        return path

    def run(self, cid, planned=None):
        return check.run_one(cid, check.Repo(self.root, planned=planned if planned is not None else {}))

    def close(self):
        self.tmp.cleanup()


class CheckCase(unittest.TestCase):
    def sample(self, changes=None, remove=()):
        s = Sample(changes, remove)
        self.addCleanup(s.close)
        return s

    def assertClean(self, result):
        self.assertIsNone(result.error, result.error)
        self.assertEqual(result.findings, [])
        self.assertGreater(result.scanned, 0, f"{result.id}: пустая проверка")

    def assertCatches(self, result, fragment):
        self.assertIsNone(result.error, result.error)
        self.assertTrue(any(fragment in f for f in result.findings), f"{fragment!r} не найдено в {result.findings}")


class CleanSampleTest(CheckCase):
    def test_every_check_passes_clean_sample(self):
        s = self.sample()
        for cid, _ in check.CHECKS:
            with self.subTest(cid):
                self.assertClean(s.run(cid))


class LayoutTest(CheckCase):
    def test_stray_root_file(self):
        self.assertCatches(self.sample({"notes.md": "x\n"}).run("repo.layout"), "только точки входа")

    def test_master_spec_is_allowed_in_root(self):
        self.assertClean(self.sample({"MESTER_TECHNICAL_TASK_ROADMAP.md": "# Мастер-ТЗ\n"}).run("repo.layout"))

    def test_workflow_is_forbidden(self):
        self.assertCatches(self.sample({".github/workflows/ci.yml": "on: push\n"}).run("repo.layout"), "D-13")


class FormatTest(CheckCase):
    def test_crlf(self):
        self.assertCatches(self.sample({"docs/PROCESS.md": "# П\r\n"}).run("format.text"), "CRLF")

    def test_no_final_newline(self):
        self.assertCatches(self.sample({"docs/PROCESS.md": "# П"}).run("format.text"), "в конце файла")

    def test_trailing_spaces(self):
        self.assertCatches(self.sample({"docs/PROCESS.md": "# П \n"}).run("format.text"), "пробелы в конце")

    def test_not_utf8(self):
        self.assertCatches(self.sample({"docs/PROCESS.md": "# П\n".encode("cp1251")}).run("format.text"), "не UTF-8")

    def test_author_master_spec_is_not_reformatted(self):
        # документ автора (D-25) без перевода строки в конце — не находка; тот же текст в другом файле — находка
        s = self.sample({"MESTER_TECHNICAL_TASK_ROADMAP.md": "# Мастер-ТЗ", "docs/PROCESS.md": "# П"})
        found = s.run("format.text").findings
        self.assertFalse(any("MESTER" in f for f in found))
        self.assertTrue(any("docs/PROCESS.md" in f for f in found))


class ScriptsTest(CheckCase):
    def test_shell_without_shebang(self):
        self.assertCatches(self.sample({"tools/check/run.sh": "exit 0\n"}).run("format.scripts"), "#!")

    def test_shell_not_executable(self):
        s = self.sample()
        path = os.path.join(s.root, "tools/check/run.sh")
        os.chmod(path, 0o644)
        self.assertCatches(s.run("format.scripts"), "нет права на исполнение")

    def test_python_syntax(self):
        self.assertCatches(self.sample({"tools/check/check.py": "def (:\n"}).run("format.scripts"), "не разбирается")


class SecretsTest(CheckCase):
    def test_token_in_text(self):
        token = "ghp" + "_" + "A1b2" * 9
        self.assertCatches(self.sample({"docs/PROCESS.md": f"токен {token}\n"}).run("security.secrets"),
                           "github-token")

    def test_value_is_not_printed(self):
        token = "ghp" + "_" + "Z9y8" * 9
        result = self.sample({"docs/PROCESS.md": f"{token}\n"}).run("security.secrets")
        self.assertFalse(any(token in f for f in result.findings))

    def test_key_file(self):
        self.assertCatches(self.sample({"config/.env": "A=1\n"}).run("security.secrets"), "файл ключа")

    def test_every_key_file_kind(self):
        for name in ("app/release.jks", "app/upload.keystore", "cert.p12", "cert.pfx", "config/server.key",
                     ".git-credentials"):
            with self.subTest(name):
                self.assertCatches(self.sample({name: "x\n"}).run("security.secrets"), "файл ключа")

    def test_every_secret_pattern(self):
        # образцы собираются из частей: этот файл не должен ловиться сам (CLAUDE.md, «Секреты»)
        samples = {
            "github-token": ["ghp" + "_" + "Ab12" * 9, "github" + "_pat_" + "A1" * 21],
            "anthropic-key": ["sk-" + "ant-" + "api03-" + "x" * 24],
            "llm-api-key": ["sk-" + "Q" * 44],
            "aws-key": ["AKIA" + "ABCDEFGHIJKLMNOP"],
            "google-api-key": ["AIza" + "B" * 35],
            "slack-token": ["xox" + "b-" + "1234567890ab"],
            "telegram-bot-token": ["123456789" + ":" + "T" * 35],
            "private-key": ["-----BEGIN " + "RSA PRIVATE " + "KEY-----"],
        }
        for kind, values in samples.items():
            for value in values:
                with self.subTest(kind=kind):
                    self.assertCatches(self.sample({"docs/PROCESS.md": f"x {value}\n"}).run("security.secrets"), kind)


class LinksTest(CheckCase):
    def test_missing_link_target(self):
        self.assertCatches(self.sample({"docs/PROCESS.md": "[x](nope.md)\n"}).run("docs.links"), "несуществующий")

    def test_missing_anchor(self):
        self.assertCatches(self.sample({"docs/PROCESS.md": "[x](PROVENANCE.md#nope)\n"}).run("docs.links"),
                           "нет якоря")

    def test_missing_backtick_path(self):
        self.assertCatches(self.sample({"docs/PROCESS.md": "`docs/NOPE.md`\n"}).run("docs.links"), "нет пути")

    def test_foreign_and_planned_paths_pass(self):
        s = self.sample({"docs/PROCESS.md": "`DPmeta:docs/ECOSYSTEM.md`, `docs/LATER.md`\n"})
        self.assertClean(s.run("docs.links", planned={"docs/LATER.md": "позже"}))

    def test_planned_path_that_exists(self):
        s = self.sample()
        self.assertCatches(s.run("docs.links", planned={"docs/PROCESS.md": "позже"}), "убрать из PLANNED")

    def test_missing_anchor_in_backtick_path(self):
        self.assertCatches(self.sample({"docs/PROCESS.md": "`docs/PROVENANCE.md#nope`\n"}).run("docs.links"),
                           "нет якоря")

    def test_code_block_is_skipped(self):
        self.assertClean(self.sample({"docs/PROCESS.md": "```\n[x](nope.md)\n```\n[p](PROVENANCE.md)\n"})
                         .run("docs.links"))


class RefsTest(CheckCase):
    def test_undefined_own_number(self):
        self.assertCatches(self.sample({"docs/PROCESS.md": "См. D-02.\n"}).run("docs.refs"), "D-02 нигде")

    def test_bare_foreign_number(self):
        self.assertCatches(self.sample({"docs/PROCESS.md": "Контракт C-06.\n"}).run("docs.refs"),
                           "без имени проекта")

    def test_bare_foreign_numbers_of_every_project(self):
        for ref in ("Д12", "Р16", "ОР-6", "RISK-01", "SQ-12", "V-07", "ADR-01", "IC-04", "INV-01", "M-04", "E-06"):
            with self.subTest(ref):
                self.assertCatches(self.sample({"docs/PROCESS.md": f"См. {ref}.\n"}).run("docs.refs"),
                                   "без имени проекта")

    def test_prefixed_foreign_number_passes(self):
        self.assertClean(self.sample({"docs/PROCESS.md": "`DPmeta:C-06`, `DPmeta:SQ-12`, D-01.\n"})
                         .run("docs.refs"))


class DecisionsTest(CheckCase):
    def test_gap_in_numbers(self):
        text = DECISIONS_TEXT.replace("### D-01", "### D-02")
        self.assertCatches(self.sample({check.DECISIONS: text}).run("process.decisions"), "ожидался D-01")

    def test_missing_field(self):
        text = DECISIONS_TEXT.replace("**Почему.** Потому что.\n", "")
        self.assertCatches(self.sample({check.DECISIONS: text}).run("process.decisions"), "**Почему.**")

    def test_missing_journal(self):
        self.assertCatches(self.sample(remove=[check.DECISIONS]).run("process.decisions"), "нет журнала")


class TodoTest(CheckCase):
    def test_checkbox(self):
        self.assertCatches(self.sample({"TODO.md": "- [x] сделано\n"}).run("process.todo"), "флажок")

    def test_no_size_label(self):
        self.assertCatches(self.sample({"TODO.md": "- пункт\n"}).run("process.todo"), "без метки")


class RoadmapTest(CheckCase):
    def run_roadmap(self, text):
        return self.sample({check.ROADMAP: text, check.README: readme_for(text)}).run("process.roadmap")

    def test_bad_heading(self):
        self.assertCatches(self.run_roadmap("### Этап 0 без образца\n\n- [ ] шаг\n"), "не по образцу")

    def test_stage_without_steps(self):
        self.assertCatches(self.run_roadmap('### <a id="task-0"></a>[ ] Этап 0. Пусто — `М`\n\nТекст.\n'),
                           "нет шагов")

    def test_anchor_differs_from_number(self):
        self.assertCatches(self.run_roadmap(ROADMAP_TEXT.replace("task-0", "task-1")), "должны быть 0")

    def test_wrong_number(self):
        self.assertCatches(self.run_roadmap(ROADMAP_TEXT.replace("task-0", "task-1").replace("Этап 0", "Этап 1")),
                           "должны быть 0")

    def test_done_stage_with_open_step(self):
        self.assertCatches(self.run_roadmap(ROADMAP_TEXT.replace("[ ] Этап 0", "[+] Этап 0")), "не все шаги")

    def test_all_steps_done_but_stage_open(self):
        self.assertCatches(self.run_roadmap(ROADMAP_TEXT.replace("- [ ] ТЗ", "- [+] ТЗ")), "отметить этап")


class StatusTest(CheckCase):
    def test_stale_block(self):
        stale = readme_for(ROADMAP_TEXT).replace("1 из 2", "2 из 2")
        self.assertCatches(self.sample({check.README: stale}).run("readme.status"), "устарел")

    def test_no_block(self):
        self.assertCatches(self.sample({check.README: "# DPmetaApp\n"}).run("readme.status"), "нет блока")


class FilesTest(CheckCase):
    def test_missing_required(self):
        self.assertCatches(self.sample(remove=["docs/PROVENANCE.md"]).run("process.files"), "отсутствует")


class ReportTest(unittest.TestCase):
    def test_empty_check_fails(self):
        r = check.Result("x.empty")
        with open(os.devnull, "w") as null:
            import contextlib
            with contextlib.redirect_stdout(null):
                self.assertEqual(check.report([r], "static", False, 0.0), 1)
        self.assertTrue(any("пустая проверка" in f for f in r.findings))

    def test_crash_is_code_3(self):
        r = check.Result("x.crash")
        r.error = "Traceback\nValueError: boom"
        with open(os.devnull, "w") as null:
            import contextlib
            with contextlib.redirect_stdout(null):
                self.assertEqual(check.report([r], "static", False, 0.0), 3)


class RealRepoTest(CheckCase):
    def test_static_checks_pass_on_this_repo(self):
        repo = check.Repo(ROOT)
        for cid, _ in check.CHECKS:
            with self.subTest(cid):
                self.assertClean(check.run_one(cid, repo))


if __name__ == "__main__":
    unittest.main()
