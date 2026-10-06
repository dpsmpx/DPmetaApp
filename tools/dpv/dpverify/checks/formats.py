"""format.text и format.scripts — кодировка, конец строк, маркеры конфликта, синтаксис скриптов (ТЗ, §8.1)."""
import os
import re
import subprocess

from ..errors import UsageError
from ..globs import MaskSet
from ..model import Finding, Outcome
from .base import Check

CONFLICT = re.compile(r"^(<{7}|>{7}|\|{7})(?: |$)")
WINDOWS = (".bat", ".cmd")  # пакетные файлы Windows — с CRLF по природе
CONFLICT_MID = re.compile(r"^={7}$")
SHEBANG = re.compile(r"^#!\s*(?:/usr/bin/env\s+(?:-\S+\s+)*)?\S*?/?(bash|sh|python3?)\b")


class Text(Check):
    ID = "format.text"
    MANDATORY = True
    SCANNED = "текстовые файлы"
    SUMMARY = "кодировку, BOM, маркеры конфликта, CRLF, конец файла"
    ORIGIN = "новое для всех; DPpass:tools/hostcheck/check_format.py"
    RULES = {
        "not-utf8": ("файл не в UTF-8", "перекодировать в UTF-8", "текст находки"),
        "bom": ("метка порядка байтов в начале", "сохранить без BOM", "текст находки"),
        "conflict-marker": ("строка <<<<<<< , >>>>>>> , |||||||; одиночная ======= — только рядом с ними",
                            "разрешить конфликт", "текст находки"),
        "crlf": ("окончания CRLF при eol = \"lf\"; *.bat и *.cmd — не находка (V-18)", "перевести в LF",
                 "текст находки"),
        "no-final-newline": ("нет перевода строки в конце при final_newline = true", "добавить",
                             "текст находки"),
        "trailing-space": ("пробелы в конце строки — только при trailing_space = true", "убрать", "номер строки"),
    }
    SETTINGS = {"eol": ("enum:lf|any", "lf"), "final_newline": ("bool", True),
                "trailing_space": ("bool", False), "skip": ("list", [])}
    MASK_KEYS = ("skip",)

    def run(self, ctx):
        s, repo, findings, scanned = self.settings, ctx.repo, [], 0
        skip = MaskSet(s["skip"], ctx.profile.modules)
        for path in repo.files():
            if skip.match(path) or repo.is_binary(path):
                continue
            scanned += 1
            raw = repo.read_bytes(path)
            if raw.startswith(b"\xef\xbb\xbf"):
                findings.append(Finding("bom", f"метка порядка байтов (BOM) в начале", path=path, line=1,
                                        hint="сохранить в UTF-8 без BOM"))
            try:
                text = raw.decode("utf-8")
            except UnicodeDecodeError as e:
                line = raw[:e.start].count(b"\n") + 1
                findings.append(Finding("not-utf8", f"не UTF-8 (байт {e.start})", path=path, line=line,
                                        hint="перекодировать в UTF-8"))
                continue
            lines = text.split("\n")
            conflict = [n for n, ln in enumerate(lines, 1) if CONFLICT.match(ln.rstrip("\r"))]
            if conflict:
                conflict += [n for n, ln in enumerate(lines, 1) if CONFLICT_MID.match(ln.rstrip("\r"))]
            for n in sorted(set(conflict)):
                findings.append(Finding("conflict-marker", f"маркер конфликта слияния", path=path, line=n,
                                        hint="разрешить конфликт"))
            if s["eol"] == "lf" and "\r\n" in text and not path.lower().endswith(WINDOWS):
                n = text[:text.index("\r\n")].count("\n") + 1
                findings.append(Finding("crlf", f"окончания строк CRLF (первое — строка {n})", path=path,
                                        line=n, hint="перевести в LF"))
            if s["final_newline"] and raw and not raw.endswith(b"\n"):
                findings.append(Finding("no-final-newline", f"нет перевода строки в конце", path=path,
                                        line=len(lines), hint="добавить перевод строки в конце файла"))
            if s["trailing_space"]:
                for n, ln in enumerate(lines, 1):
                    if ln.rstrip("\r") != ln.rstrip("\r").rstrip(" \t"):
                        findings.append(Finding("trailing-space", f"пробелы в конце строки", path=path,
                                                line=n, evidence=str(n)))
        return Outcome(scanned, findings, f"текстовых файлов {scanned}")


class Scripts(Check):
    ID = "format.scripts"
    MANDATORY = True
    SCANNED = "скрипты: *.sh, *.py и файлы с #! на bash, sh или python"
    SUMMARY = "синтаксис bash и Python"
    ORIGIN = "check_scripts DPlang"
    RULES = {
        "bash-syntax": ("bash -n не прошёл", "исправить синтаксис", "сообщение bash"),
        "python-syntax": ("compile() в памяти не прошёл (.pyc на диск не пишется)", "исправить синтаксис",
                          "сообщение Python"),
        "not-executable": ("#! без бита исполнения — только при executable = true", "chmod +x и git add",
                           "текст находки"),
    }
    SETTINGS = {"executable": ("bool", False), "skip": ("list", [])}
    MASK_KEYS = ("skip",)

    def run(self, ctx):
        repo, findings, scanned = ctx.repo, [], 0
        skip = MaskSet(self.settings["skip"], ctx.profile.modules)
        for path in repo.files():
            if skip.match(path) or repo.is_binary(path):
                continue
            text = repo.text(path)
            first = text.split("\n", 1)[0]
            m = SHEBANG.match(first)
            kind = None
            if path.endswith((".sh", ".bash")) or (m and m.group(1) in ("bash", "sh")):
                kind = "shell"
            elif path.endswith(".py") or (m and m.group(1).startswith("python")):
                kind = "python"
            if kind is None:
                continue
            scanned += 1
            problem = self._shell(repo.abspath(path)) if kind == "shell" else self._python(text, path)
            if problem:
                line, msg = problem
                findings.append(Finding(f"{'bash' if kind == 'shell' else 'python'}-syntax",
                                        f"{msg}", path=path, line=line, evidence=msg,
                                        hint="исправить синтаксис скрипта"))
            if self.settings["executable"] and first.startswith("#!"):
                if not os.stat(repo.abspath(path)).st_mode & 0o111:
                    findings.append(Finding("not-executable", f"есть #!, но нет права на исполнение",
                                            path=path, line=1, hint="chmod +x и git add"))
        return Outcome(scanned, findings, f"скриптов {scanned}")

    @staticmethod
    def _shell(abspath):
        try:
            r = subprocess.run(["bash", "-n", abspath], capture_output=True, stdin=subprocess.DEVNULL)
        except FileNotFoundError:
            raise UsageError("нет bash: он нужен проверке format.scripts (F-13)") from None
        if r.returncode == 0:
            return None
        msg = r.stderr.decode("utf-8", "replace").strip().splitlines()
        first = msg[0] if msg else "bash -n: ошибка"
        m = re.search(r":\s*(?:line|строка)\s*(\d+):", first)
        first = first.replace(abspath + ": ", "")
        return (int(m.group(1)) if m else None, f"bash -n: {first}")

    @staticmethod
    def _python(text, path):
        try:
            compile(text, path, "exec", dont_inherit=True)
        except SyntaxError as e:
            return (e.lineno, f"синтаксис Python: {e.msg}")
        except ValueError as e:  # нулевой байт и подобное
            return (None, f"синтаксис Python: {e}")
        return None
