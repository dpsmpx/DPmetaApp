"""Выбор по изменениям для уровня affected (ТЗ, §6.5).

База (F-07): `--base`; начало задачи (`dpv scope start`); merge-base с
`origin/<main_branch>`; с `<main_branch>`. Нет ни одной — полный прогон с
причиной.

Изменения (F-08): от базы до рабочего дерева — коммиты, правки, новые
неигнорируемые файлы; переименование — оба пути (удалённый и новый).
Принесённое влитым `main` после начала задачи в изменения не идёт — как
`task_changes` в VoxelRPG:tools/hostcheck/scope_check.py: если ветка
задачи ушла от `main` своими коммитами, а общий предок с `main` новее
базы, остаются только файлы, изменённые и от общего предка. Условие
«предок новее базы» — не «база — предок общего предка», как у VoxelRPG:
так закрыт и случай задачи, начатой коммитом на своей ветке. Ветка без
своих коммитов (перемотана на `main` или работа прямо в `main`)
неотличима от `main` — её изменения берутся целиком: ошибка в сторону
большего выбора.
"""
import os
import posixpath
from dataclasses import dataclass, field

from .errors import UsageError
from .repo import git


@dataclass
class Changes:
    base: str = None  # SHA базы или None
    source: str = None  # откуда база: --base, scope, origin/main, main; --files — без базы
    files: list = field(default_factory=list)  # пути от корня, по байтам (N-04)
    note: str = ""  # почему базы нет


@dataclass
class Selection:
    changes: Changes
    full: bool = False
    reason: str = ""  # почему полный прогон (F-11)
    commands: dict = field(default_factory=dict)  # id команды full → (путь или None, правило)


def _decode(raw):
    return raw.decode("utf-8", "surrogateescape")


def _byte_order(paths):
    return sorted(paths, key=lambda p: p.encode("utf-8", "surrogateescape"))


def commit(repo, ref):
    """SHA коммита или None."""
    if not ref or ref.startswith("-"):
        return None  # не ключ git
    r = git(repo.root, "rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}", check=False)
    return r.stdout.decode().strip() if r.returncode == 0 else None


def is_ancestor(repo, a, b):
    return git(repo.root, "merge-base", "--is-ancestor", a, b, check=False).returncode == 0


def main_base(repo, main_branch):
    """(ref основной ветки, merge-base с HEAD) — по порядку F-07 — или (None, None)."""
    for ref in (f"origin/{main_branch}", main_branch):
        if commit(repo, ref) is None:
            continue
        r = git(repo.root, "merge-base", "HEAD", ref, check=False)
        if r.returncode == 0 and r.stdout.strip():
            return ref, r.stdout.decode().strip()
    return None, None


def changed_since(repo, profile, base):
    """Файлы, изменённые от базы: коммиты, правки рабочего дерева, новые неигнорируемые.
    Без --no-renames переименование дало бы один путь; так — оба (F-08)."""
    out = git(repo.root, "diff", "--name-only", "-z", "--no-renames", "--no-ext-diff", base, "--").stdout
    out += git(repo.root, "ls-files", "-z", "--others", "--exclude-standard").stdout
    logs = profile.logs.rstrip("/") + "/"
    return {_decode(p) for p in out.split(b"\0") if p and not _decode(p).startswith(logs)}


def task_files(repo, profile, base):
    """Изменения задачи от базы — без принесённого влитым main (F-08)."""
    files = changed_since(repo, profile, base)
    ref, shared = main_base(repo, profile.main_branch)
    if (shared and shared != base and not is_ancestor(repo, "HEAD", ref)
            and not is_ancestor(repo, shared, base)):
        files &= changed_since(repo, profile, shared)
    return _byte_order(files)


def changes(repo, profile, base_ref=None, files=None, scope_start=None):
    """Изменения для affected (F-07, F-08). Неверная база — UsageError (код 2)."""
    if files is not None:
        return Changes(source="--files", files=_byte_order({_normalize(repo, f) for f in files}))
    if base_ref:
        base = commit(repo, base_ref)
        if base is None:
            raise UsageError(f"--base: {base_ref!r} не найден в git")
        return Changes(base, "--base", task_files(repo, profile, base))
    if repo.head() is None:
        return Changes(note="нет базы: в репозитории нет коммитов")
    if scope_start:
        base = commit(repo, scope_start)
        if base is None:
            raise UsageError(f"начало задачи {scope_start[:7]} (dpv scope start) не найдено в git — "
                             "записать границы заново: dpv scope start")
        return Changes(base, "scope", task_files(repo, profile, base))
    ref, base = main_base(repo, profile.main_branch)
    if base is None:
        return Changes(note=f"нет базы: нет origin/{profile.main_branch} и {profile.main_branch}, "
                            "не заданы --base и начало задачи (F-07)")
    return Changes(base, ref, task_files(repo, profile, base))


def _normalize(repo, path):
    """--files: путь от корня репозитория."""
    if os.path.isabs(path):
        path = os.path.relpath(path, repo.root)
    path = posixpath.normpath(path.replace(os.sep, "/"))
    if path == "." or path == ".." or path.startswith("../"):
        raise UsageError(f"--files: {path!r} — нужен файл внутри репозитория")
    return path


def select(repo, profile, changes):
    """Каждый изменённый файл — по порядку F-09; первая причина полного прогона — в итог."""
    sel = Selection(changes)
    if changes.base is None and changes.source != "--files":
        sel.full, sel.reason = True, changes.note
        return sel
    own = {profile.baseline}
    rel = os.path.relpath(profile.path, repo.root).replace(os.sep, "/")
    if not rel.startswith("../"):
        own.add(rel)
    for path in changes.files:
        reason = _full_reason(path, profile, own)
        if reason:
            sel.full, sel.reason, sel.commands = True, f"{path}: {reason}", {}
            return sel
        hit = False
        for cmd in profile.commands:
            mask = cmd.paths.matching_mask(path)
            if mask is None:
                continue
            hit = True
            if cmd.level == "full" and cmd.id not in sel.commands:
                sel.commands[cmd.id] = (path, f"paths «{mask}»")
        if hit or profile.affected["static_only"].match(path):
            continue
        if profile.affected["on_unmapped"] == "full":
            sel.full, sel.reason, sel.commands = True, f"{path}: не узнан — [affected].on_unmapped = \"full\"", {}
            return sel
    _add_after(profile, sel)
    return sel


def _full_reason(path, profile, own):
    if "\n" in path or "\r" in path:
        return "перевод строки в имени — список изменений не передать командам (F-10)"
    mask = profile.affected["full_on"].matching_mask(path)
    if mask is not None:
        return f"[affected].full_on «{mask}»"
    if path in own:
        return "изменён профиль или базовая линия"
    if path.startswith("tools/dpv/"):
        return "изменена копия DP Verify"
    return None


def _add_after(profile, sel):
    """Предшественники выбранных команд из after — тоже в выбор: без них команда не запустится как надо."""
    by_id = {c.id: c for c in profile.commands}
    queue = sorted(sel.commands)
    while queue:
        cid = queue.pop(0)
        for dep in by_id[cid].after:
            if dep in by_id and by_id[dep].level == "full" and dep not in sel.commands:
                sel.commands[dep] = (None, f"after: нужна для {cid}")
                queue.append(dep)


def write_changed(path, files):
    """Список изменённых файлов для DPV_CHANGED — по пути на строку (F-10)."""
    with open(path, "w", encoding="utf-8", errors="surrogateescape", newline="\n") as f:
        for p in files:
            f.write(p + "\n")
