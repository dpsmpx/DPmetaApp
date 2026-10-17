"""Команды проекта `[[command]]` и инструменты окружения `[[tool]]` (ТЗ, §6.6, §8.3)."""
import os
import re
import signal
import subprocess
import time

from . import version
from .errors import UsageError
from .model import CheckResult, Finding, Status

OUTPUT_TAIL = 40  # строк хвоста на красном — как BRIEF_TAIL VoxelRPG (F-27)
EXCERPT = 5
# Признаки сбоя — набор brief.py VoxelRPG (F-28).
FAILURE = re.compile(r"error:|ошибка:|^\s*FAIL\b|^\s*✗|Traceback|undefined reference|Sanitizer|"
                     r"Segmentation fault", re.I)
PROBE_TIMEOUT = 30
# Находки команды проекта — как у встроенных проверок: {правило: (что ловит, как чинить, улика для match)}.
RULES = {
    "exit-code": ("команда завершилась с ненулевым кодом; на экране — первые строки сбоя или хвост вывода",
                  "читать журнал build/dpv/last/<id>.log и чинить по выводу команды", "код возврата"),
    "timeout": ("команда не уложилась в timeout; процесс и его потомки остановлены (F-14)",
                "ускорить команду или поднять timeout в профиле", "«timeout»"),
    "not-started": ("команда не запустилась: нет файла, нет прав",
                    "проверить путь и права; инструмент окружения — [[tool]] и requires", "тип ошибки ОС"),
    "forbidden-skip": ("CI-режим, а команду пришлось пропустить: нет инструмента из requires или пропущена "
                       "команда из after", "поставить инструмент хуком начала сессии или allow_skip_in_ci "
                       "с причиной", "причина пропуска"),
}


class Tools:
    """Проверка инструментов по запросу; результат — на прогон (F-12)."""

    def __init__(self, tools, cwd):
        self.tools, self.cwd, self._seen, self.versions = tools, cwd, {}, {}

    def missing(self, name):
        """None — инструмент есть; иначе причина, почему его нет. Прочитанная версия — versions[name]."""
        if name not in self._seen:
            self._seen[name] = self._probe(self.tools[name])
        return self._seen[name]

    def _probe(self, tool):
        try:
            r = subprocess.run(tool.probe, cwd=self.cwd, capture_output=True, timeout=PROBE_TIMEOUT,
                               stdin=subprocess.DEVNULL)
        except (OSError, subprocess.TimeoutExpired) as e:
            return f"нет {tool.name} ({type(e).__name__})"
        if r.returncode != 0:
            return f"нет {tool.name} (проба: код {r.returncode})"
        if tool.version_regex is None:
            return None
        text = (r.stdout + r.stderr).decode("utf-8", "replace")
        m = tool.version_regex.search(text)
        if not m:
            return f"{tool.name}: версия не прочитана"
        self.versions[tool.name] = m.group(1) if m.re.groups else m.group(0)
        if tool.min is not None:
            try:
                ok = version.parse(m.group(1)) >= version.parse(tool.min)
            except (IndexError, UsageError):
                return f"{tool.name}: версия «{m.group(0)}» не разобрана"
            if not ok:
                return f"{tool.name} {m.group(1)} ниже {tool.min}"
        return None


def run_command(cmd, args, root, log_path, env_extra, log_hint):
    """Запускает команду; вывод (stdout и stderr вместе) — в журнал. → CheckResult."""
    env = dict(os.environ, **env_extra)
    start = time.monotonic()
    result = CheckResult(cmd.id, "command", Status.PASS, scanned=1)
    with open(log_path, "wb") as log:
        log.write(("$ " + " ".join(args) + "\n").encode())
        log.flush()
        try:
            proc = subprocess.Popen(args, cwd=root, env=env, stdin=subprocess.DEVNULL, stdout=log,
                                    stderr=subprocess.STDOUT, start_new_session=True)
        except OSError as e:
            result.status = Status.FAIL
            result.findings.append(Finding(
                "not-started", f"команда не запустилась: {type(e).__name__}: {e.strerror or e}",
                hint="проверить путь и права; инструмент окружения — [[tool]] и requires",
                evidence=type(e).__name__))
            result.duration = time.monotonic() - start
            return result
        try:
            code = proc.wait(timeout=cmd.timeout)
        except subprocess.TimeoutExpired:
            kill_group(proc)
            code = None
    result.duration = time.monotonic() - start
    with open(log_path, "rb") as f:
        lines = f.read().decode("utf-8", "replace").splitlines()[1:]
    result.summary = _summary(cmd.summary, lines)
    if code is None:
        result.status = Status.FAIL
        result.findings.append(Finding(
            "timeout", f"не уложилась в {_seconds(cmd.timeout)}: процесс и его потомки остановлены",
            hint=log_hint, evidence="timeout"))
        result.excerpt = _excerpt(lines)
    elif code != 0:
        result.status = Status.FAIL
        result.findings.append(Finding(
            "exit-code", f"команда завершилась с кодом {code}", hint=log_hint, evidence=str(code)))
        result.excerpt = _excerpt(lines)
    return result


def kill_group(proc):
    for sig, wait in ((signal.SIGTERM, 5), (signal.SIGKILL, 5)):
        try:
            os.killpg(proc.pid, sig)
        except ProcessLookupError:
            return
        try:
            proc.wait(timeout=wait)
            return
        except subprocess.TimeoutExpired:
            continue


def _summary(rx, lines):
    matches = [ln.strip() for ln in lines if rx.search(ln)]
    if matches:
        return matches[-1]
    tail = [ln.strip() for ln in lines if ln.strip()]
    return tail[-1] if tail else ""


def _excerpt(lines):
    """Первые строки сбоя по признакам; не опознан — так и сказано, и хвост."""
    hits = [i for i, ln in enumerate(lines) if FAILURE.search(ln)]
    if hits:
        first = hits[0]
        return [ln.rstrip() for ln in lines[first:first + EXCERPT]]
    tail = [ln.rstrip() for ln in lines[-OUTPUT_TAIL:]]
    return ["признак сбоя не опознан — хвост вывода:"] + tail[-EXCERPT:]


def _seconds(value):
    value = int(value)
    if value % 3600 == 0:
        return f"{value // 3600} ч"
    if value % 60 == 0:
        return f"{value // 60} мин"
    return f"{value} с"
