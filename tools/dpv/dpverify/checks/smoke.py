"""smoke.cli — дым командной строки проекта: коды выхода и вывод сценариев (ТЗ, §8.2).

Уровень full: бинарник обычно собирает команда проекта — она в `after`, и
проверка идёт после неё; упала или пропущена сборка — ○, а не ✗ (её итог
уже в прогоне). Вывод — stdout и stderr вместе, как видит человек.

Идея — `cli_smoke` в DPpass:tools/hostcheck/run.sh (свой код автора).
"""
import os
import subprocess

from ..commands import kill_group
from ..errors import UsageError
from ..model import Finding, Outcome
from ..profile import REQUIRED
from .base import Check

CASE = {"args": ("list", REQUIRED), "code": ("int", 0), "contains": ("list", []), "not_contains": ("list", []),
        "stdin": ("str", None), "timeout": ("duration", 60.0)}


class Smoke(Check):
    ID = "smoke.cli"
    LEVEL = "full"
    SCANNED = "сценарии [[case]]"
    SUMMARY = "коды выхода и вывод сценариев командной строки проекта"
    ORIGIN = "cli_smoke в DPpass:tools/hostcheck/run.sh"
    SETTINGS = {"binary": ("str", None), "after": ("list", []), "case": (("tables", CASE), [])}
    RULES = {
        "exit-code": ("код выхода сценария не тот (или сценарий не уложился в timeout)",
                      "чинить CLI или ожидание code сценария", "аргументы сценария"),
        "missing-output": ("в выводе нет строки из contains", "чинить вывод CLI или ожидание",
                           "аргументы сценария и строка"),
        "forbidden-output": ("в выводе есть строка из not_contains (часто — секрет или лишнее)",
                             "убрать из вывода", "аргументы сценария и строка"),
        "no-binary": ("нет исполняемого binary — сборка не положила его на место",
                      "проверить binary и команду сборки в after", "путь binary"),
    }

    @classmethod
    def validate(cls, settings, modules, where):
        if settings["enabled"] and not settings["binary"]:
            raise UsageError(f"{where}.binary: нужен путь к исполняемому файлу CLI")

    def run(self, ctx):
        s, root = self.settings, ctx.repo.root
        cases = s["case"]
        binary = os.path.join(root, s["binary"])
        if not (os.path.isfile(binary) and os.access(binary, os.X_OK)):
            return Outcome(len(cases), [Finding(
                "no-binary", f"нет исполняемого {s['binary']}", path=s["binary"], evidence=s["binary"],
                hint="собрать командой из after; путь — binary в профиле")], f"нет {s['binary']}")
        findings = []
        for case in cases:
            label = " ".join([os.path.basename(s["binary"])] + case["args"])
            code, out = self._run(binary, case, root, ctx.log)
            key = "\0".join(case["args"])
            if code is None:
                findings.append(self._finding("exit-code", f"{label}: не уложился в {case['timeout']:g} с", key))
            elif code != case["code"]:
                findings.append(self._finding("exit-code", f"{label}: код {code}, ожидался {case['code']}", key))
            for want in case["contains"]:
                if want not in out:
                    findings.append(self._finding("missing-output", f"{label}: нет «{want}» в выводе",
                                                  f"{key}\0{want}"))
            for bad in case["not_contains"]:
                if bad in out:
                    findings.append(self._finding("forbidden-output", f"{label}: в выводе есть «{bad}»",
                                                  f"{key}\0{bad}"))
        return Outcome(len(cases), findings, f"сценариев {len(cases)}")

    @staticmethod
    def _finding(rule, message, evidence):
        return Finding(rule, message, evidence=evidence, hint="вывод сценария — журнал smoke.cli.log")

    @staticmethod
    def _run(binary, case, root, log):
        """(код или None по тайм-ауту, вывод stdout+stderr)."""
        log.write("$ " + " ".join([binary] + case["args"]) + "\n")
        try:
            proc = subprocess.Popen([binary] + case["args"], cwd=root, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT,
                                    stdin=subprocess.PIPE if case["stdin"] is not None else subprocess.DEVNULL,
                                    start_new_session=True)
        except OSError as e:
            log.write(f"не запустился: {e}\n")
            return -1, ""
        data = case["stdin"].encode() if case["stdin"] is not None else None
        try:
            raw, _ = proc.communicate(data, timeout=case["timeout"])
            code = proc.returncode
        except subprocess.TimeoutExpired:
            kill_group(proc)
            raw, _ = proc.communicate()
            code = None
        out = raw.decode("utf-8", "replace")
        log.write(out + ("" if out.endswith("\n") or not out else "\n") + f"код: {code}\n")
        return code, out
