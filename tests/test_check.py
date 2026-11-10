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
    "core/model/build.gradle.kts": 'plugins {\n    id("dpai.kotlin-library")\n}\n\ndependencies {\n    api("com.dpsmpx.dpai:core")\n}\n',
    "core/formats/build.gradle.kts": 'dependencies {\n    api(project(":core:model"))\n}\n',
    "core/model/src/main/kotlin/M.kt": "package m\n\nfun now(clock: Clock) = clock.now()\n",
    # Экран пульта: на экране — слово для человека, без номера решения (ui.words).
    "app/src/main/kotlin/Screen.kt": 'val title = "Вход по PIN устройства"\n',
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
    # Ядро подсистемы dpai (D-28): каждый тип ядра объявлен один раз.
    "dpai/core/src/main/kotlin/Core.kt": (
        "package core\n\nsealed interface Outcome<out T>\nclass OperationResult<out T>\nclass Diagnostic\n"
        "enum class DiagCode { A }\nclass Secret(private val v: String)\ninterface Clock\n"),
    "dpai/core/src/main/kotlin/Use.kt": "package core\n\nclass ManualClock : Clock\nval x: Outcome<Int>? = null\n",
    # Реестр команд (F-43, D-82): проект подсистемы — из реестра DPmeta, его скрипт — на месте.
    "dpmeta/tools/ecosystem.toml": (
        'schema = 1\n\n[[repo]]\nname = "DPmetaApp"\nslug = "dpsmpx/DPmetaApp"\nplace = "control"\n\n'
        '[[repo]]\nname = "DPmeta"\nslug = "dpsmpx/DPmeta"\nplace = "sync"\ndir = "dpmeta"\n\n'
        '[[repo]]\nname = "Old"\nslug = "dpsmpx/Old"\nplace = "archive"\n\n'
        '[[repo]]\nname = "DPlearn"\nslug = "dpsmpx/DPlearn"\nplace = "core"\n'),
    "dpmeta/tools/check.sh": "#!/bin/sh\nexit 0\n",
    "config/commands.toml": (
        'schema = 1\n\n[[command]]\nid = "dpmeta.check"\ntitle = "Проверки"\nproject = "DPmeta"\npath = "/bin/bash"\n'
        'args = ["tools/check.sh"]\nplan = "background"\nlevel = 0\npackages = ["bash"]\n\n'
        '[[command]]\nid = "dplearn.next"\ntitle = "План"\nproject = "DPlearn"\npath = "tools/dpl"\nplan = "background"\nlevel = 0\n\n'
        '[[command]]\nid = "repo.ff"\ntitle = "Догнать"\nproject = "*"\npath = "/usr/bin/git"\n'
        'args = ["merge", "--ff-only", "@{upstream}"]\nplan = "background"\nlevel = 1\ntimeout = 2\n'),
}
# Wrapper Gradle: копия в корне — та же, что в dpai/ (D-28); пакетный файл — с CRLF, как его пишет Gradle.
for _rel, _text in {"gradlew": "#!/bin/sh\nexec java\n", "gradlew.bat": "@echo off\r\nexit /b 0\r\n",
                    "gradle/wrapper/gradle-wrapper.jar": b"PK\x03\x04\x00jar",
                    "gradle/wrapper/gradle-wrapper.properties": "distributionUrl=x\n"}.items():
    CLEAN[_rel] = _text
    CLEAN["dpai/" + _rel] = _text


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


COMMAND = ('[[command]]\nid = "x.y"\ntitle = "Т"\nproject = "DPmeta"\npath = "/bin/bash"\nargs = ["tools/check.sh"]\n'
           'plan = "background"\nlevel = 0\n')


class CommandsTest(CheckCase):
    """config.commands — реестр команд (F-43, D-82)."""

    def commands(self, body):
        return self.sample({"config/commands.toml": "schema = 1\n\n" + body}).run("config.commands")

    def test_clean_registry(self):
        self.assertClean(self.commands(COMMAND))

    def test_missing_registry(self):
        self.assertCatches(self.sample(remove=("config/commands.toml",)).run("config.commands"), "нет реестра команд")

    def test_level_two(self):
        self.assertCatches(self.commands(COMMAND.replace("level = 0", "level = 2")), "уровней 2 и 3 в реестре нет")

    def test_unknown_key(self):
        self.assertCatches(self.commands(COMMAND + 'env = "X=1"\n'), "неизвестный ключ «env»")

    def test_duplicate_id(self):
        self.assertCatches(self.commands(COMMAND + "\n" + COMMAND), "уже был")

    def test_project_not_in_registry_or_archived(self):
        self.assertCatches(self.commands(COMMAND.replace('"DPmeta"', '"Nope"')), "нет в реестре")
        self.assertCatches(self.commands(COMMAND.replace('"DPmeta"', '"Old"')), "нет в реестре")

    def test_subsystem_script_must_exist(self):
        self.assertCatches(self.commands(COMMAND.replace("tools/check.sh", "tools/gone.sh")), "нет файла `dpmeta/tools/gone.sh`")

    def test_paths_stay_inside(self):
        self.assertCatches(self.commands(COMMAND + 'workdir = "../x"\n'), "workdir")
        self.assertCatches(self.commands(COMMAND.replace('"/bin/bash"', '"/root/x"')), "программа гостя")
        self.assertCatches(self.commands(COMMAND.replace('"/bin/bash"', '"../x.sh"')), "без «..»")

    def test_bad_package_and_timeout(self):
        self.assertCatches(self.commands(COMMAND + 'packages = ["Python3"]\n'), "пакетов Alpine")
        self.assertCatches(self.commands(COMMAND + "timeout = 31\n"), "1…30 минут")


class SubsystemScopeTest(CheckCase):
    def test_subsystem_dir_is_allowed_in_root(self):
        self.assertClean(self.sample().run("repo.layout"))

    def test_subsystem_text_is_left_to_its_own_checks(self):
        # Текст, ссылки и номера подсистемы проверяет её команда (D-28): здесь не находка.
        s = self.sample({"dpai/docs/X.md": "# X\r\nСм. D-55, `docs/NOPE.md`, C-06.\n"})
        for cid in ("format.text", "docs.links", "docs.refs"):
            with self.subTest(cid):
                self.assertClean(s.run(cid))

    def test_dppass_is_a_subsystem_too(self):
        # DPpass — подсистема со своим процессом (D-30): каталог разрешён в корне, текст — её проверкам.
        s = self.sample({"dppass/docs/X.md": "# X\r\nСм. D-52, `docs/NOPE.md`.\n"})
        for cid in ("repo.layout", "format.text", "docs.links", "docs.refs"):
            with self.subTest(cid):
                self.assertClean(s.run(cid))

    def test_socialmediaimage_is_a_subsystem_too(self):
        # SocialMediaImage — подсистема со своим процессом (D-43): номера Д без имени проекта — её проверкам.
        s = self.sample({"socialmediaimage/docs/X.md": "# X\r\nСм. Д35, `docs/NOPE.md`.\n"})
        for cid in ("repo.layout", "format.text", "docs.links", "docs.refs"):
            with self.subTest(cid):
                self.assertClean(s.run(cid))

    def test_dpvisualisation_is_a_subsystem_too(self):
        # DPvisualisation — подсистема со своим процессом (D-43): её номера без имени проекта — её проверкам.
        s = self.sample({"dpvisualisation/docs/X.md": "# X\r\nСм. D-24, `docs/NOPE.md`.\n"})
        for cid in ("repo.layout", "format.text", "docs.links", "docs.refs"):
            with self.subTest(cid):
                self.assertClean(s.run(cid))

    def test_dpmeta_is_a_subsystem_too(self):
        # DPmeta — подсистема со своим процессом (D-43): её номера M-NN без имени проекта — её проверкам.
        s = self.sample({"dpmeta/docs/X.md": "# X\r\nСм. M-14, `docs/NOPE.md`.\n"})
        for cid in ("repo.layout", "format.text", "docs.links", "docs.refs"):
            with self.subTest(cid):
                self.assertClean(s.run(cid))

    def test_dpprofile_is_a_subsystem_too(self):
        # DPprofile — подсистема со своим процессом (D-43): её номера без имени проекта — её проверкам.
        s = self.sample({"dpprofile/docs/X.md": "# X\r\nСм. D-16, `docs/NOPE.md`.\n"})
        for cid in ("repo.layout", "format.text", "docs.links", "docs.refs"):
            with self.subTest(cid):
                self.assertClean(s.run(cid))

    def test_secrets_are_searched_in_subsystem_too(self):
        token = "ghp" + "_" + "C3d4" * 9
        self.assertCatches(self.sample({"dpai/docs/X.md": f"{token}\n"}).run("security.secrets"), "github-token")


class CoreSingleTest(CheckCase):
    def test_core_type_outside_core(self):
        s = self.sample({"core/model/src/main/kotlin/Result.kt": "package m\n\nsealed class Outcome\n"})
        self.assertCatches(s.run("core.single"), "Outcome объявлен вне")

    def test_nested_redeclaration_outside_core(self):
        s = self.sample({"app/src/main/kotlin/A.kt": "class A {\n    object Clock\n}\n"})
        self.assertCatches(s.run("core.single"), "Clock объявлен вне")

    def test_core_type_twice_in_core(self):
        s = self.sample({"dpai/core/src/main/kotlin/Again.kt": "package core\n\ndata class Secret(val v: String)\n"})
        self.assertCatches(s.run("core.single"), "объявлен в ядре 2 раз")

    def test_core_type_missing_in_core(self):
        core = CLEAN["dpai/core/src/main/kotlin/Core.kt"].replace("interface Clock\n", "")
        self.assertCatches(self.sample({"dpai/core/src/main/kotlin/Core.kt": core}).run("core.single"),
                           "нет объявления Clock")

    def test_own_core_of_dppass_is_allowed(self):
        # Ядро DPpass пока своё (D-30): его типы — не находка, а в остальном коде — по-прежнему.
        s = self.sample({"dppass/diagnostics/src/main/kotlin/Outcome.kt": "package d\n\nsealed interface Outcome\nclass Secret\n"})
        self.assertClean(s.run("core.single"))
        s = self.sample({"dppass/x/Outcome.kt": "sealed interface Outcome\n", "core/m/A.kt": "class Secret\n"})
        self.assertCatches(s.run("core.single"), "Secret объявлен вне")

    def test_similar_names_pass(self):
        s = self.sample({"core/model/src/main/kotlin/M.kt": "class OutcomeView\nclass TestClock\nfun Secret() = 1\n"})
        self.assertClean(s.run("core.single"))


class WrapperTest(CheckCase):
    def test_root_copy_differs(self):
        s = self.sample({"gradle/wrapper/gradle-wrapper.properties": "distributionUrl=y\n"})
        self.assertCatches(s.run("build.wrapper"), "отличается от dpai/gradle/wrapper/gradle-wrapper.properties")

    def test_root_copy_missing(self):
        self.assertCatches(self.sample(remove=["gradlew"]).run("build.wrapper"), "нет копии dpai/gradlew")

    def test_source_missing(self):
        self.assertCatches(self.sample(remove=["dpai/gradlew.bat"]).run("build.wrapper"), "нет wrapper подсистемы")

    def test_batch_file_keeps_crlf_but_other_files_do_not(self):
        s = self.sample({"docs/PROCESS.md": "# П\r\n"})
        found = s.run("format.text").findings
        self.assertFalse(any("gradlew.bat" in f for f in found))
        self.assertTrue(any("docs/PROCESS.md" in f for f in found))


def quiet_report(results, strict=False):
    import contextlib
    with open(os.devnull, "w") as null, contextlib.redirect_stdout(null):
        return check.report(results, "full", False, 0.0, strict)


class GradleTest(unittest.TestCase):
    """build.gradle (ТЗ, §14): нет JDK — находка, а не зелёный (D-51); нет SDK — названный пропуск."""

    @staticmethod
    def never(cmd, cwd):
        raise AssertionError("Gradle не должен запускаться без JDK")

    @staticmethod
    def answers(rc, out):
        return lambda cmd, cwd: (rc, out)

    def test_no_jdk_is_finding_not_green(self):
        """Без JDK сборки не было: `full` не значит ничего (D-51, блокер аудита 30 октября 2026)."""
        r = check.check_gradle(ROOT, java="", sdk="/sdk", run=self.never)
        self.assertTrue(any("нет JDK" in f for f in r.findings))
        self.assertEqual([], r.skipped)
        self.assertEqual(1, quiet_report([r]))

    def test_no_jdk_is_finding_in_strict_too(self):
        r = check.check_gradle(ROOT, java="", sdk="/sdk", run=self.never)
        self.assertEqual(1, quiet_report([r], strict=True))

    def test_no_sdk_is_named_skip_and_not_a_finding(self):
        """SDK — не про ядро: собрано всё, кроме оболочки; это законный пропуск, в --strict — находка."""
        r = check.check_gradle(ROOT, java="/jdk/bin/java", sdk="", run=self.answers(0, "BUILD SUCCESSFUL\n12 actionable tasks: 12 executed\n"))
        self.assertTrue(any("Android SDK" in w for w in r.skipped))
        self.assertEqual(0, quiet_report([r]))
        self.assertEqual(1, quiet_report([r], strict=True))

    def test_full_build_passes_clean(self):
        r = check.check_gradle(ROOT, java="/jdk/bin/java", sdk="/sdk", run=self.answers(0, "301 actionable tasks: 9 executed, 292 up-to-date\n"))
        self.assertEqual(([], [], 301), (r.findings, r.skipped, r.scanned))
        self.assertEqual(0, quiet_report([r], strict=True))

    def test_build_failure_is_finding(self):
        out = "> Task :dpai:core:test FAILED\n\nBUILD FAILED in 9s\n59 actionable tasks: 50 executed, 9 up-to-date\n"
        r = check.check_gradle(ROOT, java="/jdk/bin/java", sdk="/sdk", run=self.answers(1, out))
        self.assertTrue(any("FAILED" in f for f in r.findings))
        self.assertEqual(1, quiet_report([r]))

    def test_download_failure_is_tool_error_not_code(self):
        out = "> Could not GET 'https://repo.maven.apache.org/x.pom'.\n> Received status code 429 from server: Too Many Requests\nBUILD FAILED\n"
        r = check.check_gradle(ROOT, java="/jdk/bin/java", sdk="/sdk", run=self.answers(1, out))
        self.assertIsNotNone(r.error)
        self.assertEqual([], r.findings)
        self.assertEqual(3, quiet_report([r]))

    def test_strict_only_with_full(self):
        import contextlib
        with open(os.devnull, "w") as null, contextlib.redirect_stdout(null):
            self.assertEqual(2, check.main(["static", "--strict"]))


class SubsystemChecksTest(CheckCase):
    def run_sub(self, script):
        s = self.sample({"sub/verify.sh": script} if script else None)
        return check.check_subsystems(s.root, {"sub": "sub/verify.sh"})[0]

    def test_passing_subsystem(self):
        self.assertClean(self.run_sub("#!/bin/sh\necho '- ✓ формат'\necho '- ✓ ссылки'\n"))

    def test_failing_step_is_reported(self):
        self.assertCatches(self.run_sub("#!/bin/sh\necho '- ✓ формат'\necho '- ✗ ссылки'\nexit 1\n"), "ссылки")

    def test_failure_without_steps_is_reported(self):
        self.assertCatches(self.run_sub("#!/bin/sh\necho 'сломалось'\nexit 2\n"), "сломалось")

    def test_missing_command(self):
        self.assertCatches(self.run_sub(None), "нет команды проверок")

    def test_dp_verify_lines_are_steps(self):
        # Вывод DP Verify (dppass, D-30): «  ✓ id  итог»; строки подробностей и итог прогона — не шаги.
        out = "printf 'dpv · static\\n  ✓ docs.links    ссылок 10\\n  ✓ docs.refs     ссылок 3\\n✓ static пройден\\n'"
        r = self.run_sub(f"#!/bin/sh\n{out}\n")
        self.assertClean(r)
        self.assertEqual(2, r.scanned)

    def test_smi_lines_are_steps(self):
        # Вывод tools/check.sh SocialMediaImage (D-43): «✓ id — что проверено»; подробности и итог — не шаги.
        out = "printf '✓ schemas — данные по JSON Schema\\n    · файлов 36\\n✓ links — ссылки\\n\\n✓ tools/check.sh: всё зелёное\\n'"
        s = self.sample({"sub/check.sh": f"#!/bin/sh\n{out}\n"})
        r = check.check_subsystems(s.root, {"sub": ("sub/check.sh", "--quick")})[0]
        self.assertClean(r)
        self.assertEqual(2, r.scanned)

    def test_smi_failing_check_is_reported(self):
        # Код 0 нарочно: находку должен дать разбор строки «✗ id — …», а не запасной хвост вывода.
        out = "printf '✓ schemas — данные\\n✗ links — ссылки в документах\\n    ✗ a.md:1: нет файла\\n'"
        s = self.sample({"sub/check.sh": f"#!/bin/sh\n{out}\n"})
        self.assertCatches(check.check_subsystems(s.root, {"sub": ("sub/check.sh", "--quick")})[0], "links")

    def test_dpvisualisation_lines_are_steps(self):
        # Вывод tools/check/run.sh DPvisualisation (D-43): «✓ id (0.0 с)»; итог «Итог static: …» — не шаг.
        out = "printf '✓ process.decisions (0.0 с)\\n✓ docs.refs (0.0 с)\\nИтог static: всё прошло (2) — 0.2 с\\n'"
        r = self.run_sub(f"#!/bin/sh\n{out}\n")
        self.assertClean(r)
        self.assertEqual(2, r.scanned)

    def test_dpvisualisation_failing_check_is_reported(self):
        # Код 0 нарочно: находку должен дать разбор строки «✗ id (…)», а не запасной хвост вывода.
        out = "printf '✓ process.decisions (0.0 с)\\n✗ docs.refs (0.0 с)\\n    a.md:1: D-9 нигде не определён\\n'"
        self.assertCatches(self.run_sub(f"#!/bin/sh\n{out}\n"), "docs.refs")

    def test_dpmeta_lines_are_steps(self):
        # Вывод tools/check.sh DPmeta (D-43): «✓ шаг: …»; «○ … пропущено» и итог «✓ DPmeta: …» — не шаги.
        out = ("printf '✓ документы: ссылки, свои номера, корень\\n✓ самотесты инструментов\\n"
               "○ ссылки — пропущено: рядом нет клонов\\n✓ DPmeta: всё зелёное\\n'")
        s = self.sample({"dpmeta/tools/check.sh": f"#!/bin/sh\n{out}\n"})
        r = check.check_subsystems(s.root, {"dpmeta": ("dpmeta/tools/check.sh",)})[0]
        self.assertClean(r)
        self.assertEqual(2, r.scanned)

    def test_dpmeta_failing_step_is_reported(self):
        # Код 0 нарочно: находку должен дать разбор строки «✗ шаг», а не запасной хвост вывода.
        out = "printf '✓ самотесты инструментов\\n✗ документы: ссылки, свои номера, корень\\n    a.md:1: нет файла\\n'"
        s = self.sample({"dpmeta/tools/check.sh": f"#!/bin/sh\n{out}\n"})
        self.assertCatches(check.check_subsystems(s.root, {"dpmeta": ("dpmeta/tools/check.sh",)})[0], "документы")

    def test_dp_verify_failing_check_is_reported(self):
        out = "printf '  ✓ docs.links    ссылок 10\\n  ✗ docs.refs     находок: 1\\n      a.md:1  D-9 не определено\\n'"
        self.assertCatches(self.run_sub(f"#!/bin/sh\n{out}\nexit 1\n"), "docs.refs")


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

    def test_not_path_name_is_skipped(self):
        # Имя ветки `dpmeta/docs-sync` в срезе — не путь (NOT_PATHS); соседний неверный путь — находка.
        s = self.sample({"dpmeta/README.md": "# DPmeta\n", "docs/audits/2026-10-10-x.md": "`dpmeta/docs-sync`\n"})
        self.assertClean(s.run("docs.links"))
        s = self.sample({"dpmeta/README.md": "# DPmeta\n", "docs/audits/2026-10-10-x.md": "`dpmeta/NOPE.md`\n"})
        self.assertCatches(s.run("docs.links"), "нет пути")

    def test_not_path_that_exists(self):
        s = self.sample({"dpmeta/docs-sync/x.md": "# x\n", "docs/PROCESS.md": "`dpmeta/docs-sync`\n"})
        self.assertCatches(s.run("docs.links"), "убрать из NOT_PATHS")

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


class ModulesTest(CheckCase):
    def test_wrong_direction(self):
        s = self.sample({"core/model/build.gradle.kts": 'dependencies {\n    api(project(":core:formats"))\n}\n'})
        self.assertCatches(s.run("modules.root"), ":core:model не может зависеть от :core:formats")

    def test_metrics_cannot_use_network(self):
        s = self.sample({"core/metrics/build.gradle.kts": 'dependencies {\n    api(project(":core:net"))\n}\n'})
        self.assertCatches(s.run("modules.root"), ":core:metrics не может зависеть от :core:net")

    def test_subsystem_is_not_core(self):
        s = self.sample({"core/sync/build.gradle.kts": 'dependencies {\n    api("com.dpsmpx.dpai:orchestrator")\n}\n'})
        self.assertCatches(s.run("modules.root"), "зависит от подсистемы dpai:orchestrator")

    def test_unknown_module(self):
        s = self.sample({"core/store/build.gradle.kts": "plugins {}\n"})
        self.assertCatches(s.run("modules.root"), "модуль :core:store не описан")

    def test_shells_depend_on_all(self):
        s = self.sample({"cli/build.gradle.kts": 'dependencies {\n    implementation(project(":core:sync"))\n    implementation("com.dpsmpx.dpai:orchestrator")\n}\n'})
        self.assertClean(s.run("modules.root"))

    def test_code_rules(self):
        for code, why in [
            ("import android.content.Context\n", "Android-типы"),
            ("val t = System.currentTimeMillis()\n", "через Clock"),
            ("fun f() = Thread.sleep(1)\n", "без сна"),
            ('fun f() = println("x")\n', "печать"),
            ("fun f(s: Secret) = s.reveal()\n", "reveal()"),
        ]:
            with self.subTest(why):
                s = self.sample({"core/sync/src/main/kotlin/S.kt": "package s\n\n" + code})
                self.assertCatches(s.run("modules.root"), why)

    def test_reveal_home_and_comments_pass(self):
        s = self.sample({
            "core/net/src/main/kotlin/com/dpsmpx/dpmeta/net/Auth.kt": "fun b(s: Secret) = s.reveal()\n",
            "core/sync/src/main/kotlin/S.kt": "// System.currentTimeMillis — нельзя, берём Clock\nfun printlnLike() = 1\n",
        })
        self.assertClean(s.run("modules.root"))


class UiWordsTest(CheckCase):
    """ui.words — номеров решений в строках экранов нет (UX-13, AI:A-10)."""

    def test_catches_decision_number_in_screen_string(self):
        for rel, code in [
            ("app/src/main/kotlin/P.kt", 'Text("Токен только для чтения (D-17).")\n'),
            ("dpai/android/src/main/kotlin/A.kt", 'val hint = "Класс — маршрут модели (D-40)"\n'),
            ("dpai/ui/src/main/kotlin/G.kt", 'GuideStep("Разрешите (DPmetaApp:D-46), затем «Разрешить»")\n'),
        ]:
            with self.subTest(rel):
                s = self.sample({rel: "package p\n\n" + code})
                self.assertCatches(s.run("ui.words"), "номер решения")

    def test_comments_tests_and_other_modules_pass(self):
        s = self.sample({
            # KDoc и комментарии — место номера; тесты и модули без экранов правило не смотрит.
            "app/src/main/kotlin/P.kt": '/** Вход по PIN (D-59). */\nval t = "Вход по PIN" // D-59\n// Text("(D-17)")\n',
            "app/src/test/kotlin/T.kt": 'val s = "(D-17)"\n',
            "core/sync/src/main/kotlin/S.kt": 'val why = "клоны (D-65)"\n',
            "dpai/ui/src/main/kotlin/W.kt": 'val name = "D-day"\nval v = "RD-12x"\n',
        })
        self.assertClean(s.run("ui.words"))


class ForbiddenTest(CheckCase):
    """code.forbidden — запрещённое ТЗ, §6.5 (A-06, A-07)."""

    def test_catches_each_sign(self):
        for code, why in [
            ('val cmd = listOf("rsync", "-a", "dist/", host)\n', "выгрузка сайта"),
            ('const val HOST = "ssh.beget.com"\n', "выгрузка сайта"),
            ('val url = "https://api.anthropic.com/v1/organizations/invites"\n', "Admin API"),
            # Лимиты workspace меняются POST — пути нет в списке чтения (D-44, D-81).
            ('val url = "https://api.anthropic.com/v1/organizations/workspaces/w/rate_limits/r"\n', "Admin API"),
            ('val url = "https://api.anthropic.com/v1/organizations/analytics/usage_admin"\n', "Admin API"),
            ("import org.jsoup.Jsoup\n", "скрейпинга"),
            ('fun f(w: WebView) = w.evaluateJavascript("x", null)\n', "автоматизации браузера"),
        ]:
            with self.subTest(code):
                s = self.sample({"dpai/providers/src/main/kotlin/P.kt": "package p\n\n" + code})
                self.assertCatches(s.run("code.forbidden"), why)

    def test_allowed_places_and_reports_pass(self):
        s = self.sample({
            # Выгрузка — у подсистемы сайта (D-40, D-48); отчёты Usage и Cost — только чтение (D-08).
            "socialmediaimage/app/src/main/kotlin/Deploy.kt": 'val cmd = listOf("rsync", "-a")\n',
            "dpai/providers/src/main/kotlin/Usage.kt": 'val u = "https://api.anthropic.com/v1/organizations/usage_report/messages"\n',
            # Список чтения D-81: аналитика расхода и стоимости, лимиты скорости организации.
            "dpai/providers/src/main/kotlin/Admin.kt": 'val a = "/v1/organizations/analytics/usage"\nval c = "/v1/organizations/analytics/cost"\nval l = "/v1/organizations/rate_limits"\n',
            "core/sync/src/main/kotlin/S.kt": "// без rsync и jsoup — D-20, D-40\nval x = 1 // не rsync: D-40\n",
            "core/sync/src/test/kotlin/T.kt": 'val sample = "rsync"\n',
        })
        self.assertClean(s.run("code.forbidden"))
