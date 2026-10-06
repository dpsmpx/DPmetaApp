"""Границы задачи: `dpv scope start|check|show` и проверка process.scope (ТЗ, §8.2, §9.1; TR-8).

Перенос VoxelRPG:tools/hostcheck/scope_check.py (свой код автора) с
настройками `[scope]` вместо постоянных VoxelRPG:

- запись — `<logs>/scope.json` (только в этом контейнере), а не
  `build/task-scope.txt`;
- `TODO.md` — `[scope].always_allow`;
- «ROADMAP — только смена флажков» — `[scope].flag_only`: в этих файлах без
  записи в границах разрешена только смена флажков `[ ]` ↔ `[+]`;
- блок готовности README — `[scope].blocks`: участок между метками всегда
  в границах;
- путь с якорем (`ROADMAP/ROADMAP.md#task-3`) — правки только внутри
  раздела: от заголовка с этим якорем до следующего заголовка того же
  уровня или выше; у VoxelRPG — только ROADMAP, здесь — любой файл.

Изменённое с начала задачи — select.task_files: без принесённого влитым
`main`. Проверка только находит и ничего не запрещает (L-12): посторонний
файл — убрать, объяснить в PR или спросить автора.
"""
import json
import os
import re
from dataclasses import dataclass

from . import select, textscan
from .errors import UsageError
from .globs import MaskSet, compile_mask
from .repo import git

RECORD = "scope.json"
FLAG = re.compile(r"\[[ +xX]\]")
HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


@dataclass
class Record:
    task: str
    start: str
    paths: list


def record_path(repo, profile):
    return repo.abspath(f"{profile.logs}/{RECORD}")


def load(repo, profile):
    """Запись о начале задачи или None."""
    path = record_path(repo, profile)
    if not os.path.isfile(path):
        return None
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        record = Record(data["task"], data["start"], list(data["paths"]))
    except (ValueError, KeyError, TypeError) as e:
        raise UsageError(f"{profile.logs}/{RECORD} не разобран ({e}) — записать границы заново: "
                         "dpv scope start") from None
    if not (isinstance(record.start, str) and record.paths and all(isinstance(p, str) for p in record.paths)):
        raise UsageError(f"{profile.logs}/{RECORD} не разобран — записать границы заново: dpv scope start")
    return record


def _clean(path):
    return path[2:] if path.startswith("./") else path


def _validate(pattern):
    """Путь границ: файл, каталог, маска (§7.2) или файл#якорь."""
    file_part, sep, anchor = _clean(pattern).partition("#")
    if not file_part or (sep and not anchor):
        raise UsageError(f"scope: путь {pattern!r} — нужен файл, каталог, маска или файл#якорь")
    compile_mask(file_part)  # неверная маска — код 2
    return _clean(pattern)


def allowed(path, patterns, profile):
    """Путь целиком в границах: совпал с путём задачи (не разделом) или always_allow."""
    if profile.scope["always_allow"].match(path):
        return True
    for p in patterns:
        if "#" in p:
            continue  # раздел файла — по строкам (_sections_ok)
        if path == p or path.startswith(p.rstrip("/") + "/") or MaskSet([p]).match(path):
            return True
    return False


def _sections(text, anchors):
    """Диапазоны строк (с 1, включительно) разделов с этими якорями."""
    heads = textscan.headings(text)
    total = len(text.splitlines())
    out = []
    for i, (level, title, n) in enumerate(heads):
        ids = {m.group(1) for m in textscan.ANCHOR_TAG.finditer(title)} | {textscan.slug(title)}
        if ids & anchors:
            end = total
            for lvl, _, m in heads[i + 1:]:
                if lvl <= level:
                    end = m - 1
                    break
            out.append((n, end))
    return out


def _blocks(text, blocks):
    """Диапазоны строк (с 1, включительно, с метками) участков [scope].blocks."""
    lines = text.splitlines()
    out = []
    for b in blocks:
        start = None
        for n, line in enumerate(lines, 1):
            if start is None and b.begin in line:
                start = n
            elif start is not None and b.end in line:
                out.append((start, n))
                start = None
    return out


def _hunks(repo, since, path):
    """[(удалённые строки, добавленные строки, начало в новом файле)] от начала задачи."""
    out, cur = [], None
    diff = git(repo.root, "diff", "-U0", "--no-renames", "--no-ext-diff", "--no-textconv", since, "--", path)
    for line in diff.stdout.decode("utf-8", "replace").splitlines():
        m = HUNK.match(line)
        if m:
            cur = ([], [], int(m.group(1)))
            out.append(cur)
        elif cur is not None and line.startswith("-") and not line.startswith("---"):
            cur[0].append(line[1:])
        elif cur is not None and line.startswith("+") and not line.startswith("+++"):
            cur[1].append(line[1:])
    return out


def _sections_ok(repo, profile, since, path, anchors):
    """Файл изменён только внутри разрешённых участков: разделов задачи, блоков [scope]
    и (для flag_only) сменой флажков."""
    blocks = [b for b in profile.scope["blocks"] if b.file == path]
    flags = profile.scope["flag_only"].match(path)
    if not (anchors or blocks or flags):
        return False
    full = repo.abspath(path)
    if not os.path.isfile(full) or git(repo.root, "cat-file", "-e", f"{since}:{path}", check=False).returncode:
        return False  # новый или удалённый файл — целиком, а не участок
    with open(full, encoding="utf-8", errors="replace") as f:
        text = f.read()
    ranges = _sections(text, anchors) + _blocks(text, blocks)
    for old, new, at in _hunks(repo, since, path):
        lines = range(at, at + max(len(new), 1))
        if ranges and all(any(a <= n <= b for a, b in ranges) for n in lines):
            continue
        if flags and old and len(old) == len(new) and all(
                FLAG.sub("[]", o) == FLAG.sub("[]", n) for o, n in zip(old, new)):
            continue
        return False
    return True


def check(repo, profile, record):
    """(изменённые с начала задачи, вне границ)."""
    files = select.task_files(repo, profile, record.start)
    anchors = {}
    for p in record.paths:
        file_part, sep, anchor = p.partition("#")
        if sep:
            anchors.setdefault(file_part, set()).add(anchor)
    stray = [f for f in files if not allowed(f, record.paths, profile)
             and not _sections_ok(repo, profile, record.start, f, anchors.get(f, set()))]
    return files, stray


def _resolve(repo, record):
    if select.commit(repo, record.start) is None:
        raise UsageError(f"начало задачи {record.start[:7]} не найдено в git — записать границы заново: "
                         "dpv scope start")


def allowances(profile):
    """Что в границах всегда — для вывода."""
    out = [f"{m} (always_allow)" for m in profile.scope["always_allow"].masks]
    out += [f"{m} (флажки — flag_only)" for m in profile.scope["flag_only"].masks]
    out += [f"{b.file} между «{b.begin}» и «{b.end}» (blocks)" for b in profile.scope["blocks"]]
    return out


# ---- dpv scope ----

def main(repo, profile, args, out):
    if not args or args[0] not in ("start", "check", "show"):
        raise UsageError('scope: start [--from SHA] "<задача>" ПУТЬ… | check | show')
    cmd, rest = args[0], args[1:]
    if cmd == "start":
        return _start(repo, profile, rest, out)
    if rest:
        raise UsageError(f"scope {cmd}: лишний аргумент {rest[0]!r}")
    record = load(repo, profile)
    if record is None:
        print('границы задачи не заданы — dpv scope start "<задача>" ПУТЬ…', file=out)
        return 2 if cmd == "check" else 0
    if cmd == "show":
        print(f"задача «{record.task}» · от {record.start[:7]} · {profile.logs}/{RECORD}", file=out)
        for p in record.paths:
            print(f"  {p}", file=out)
        for a in allowances(profile):
            print(f"  {a}", file=out)
        return 0
    _resolve(repo, record)
    files, stray = check(repo, profile, record)
    head = f"границы задачи «{record.task}» от {record.start[:7]}"
    if not stray:
        print(f"✓ {head}: изменено файлов {len(files)}, все в границах", file=out)
        return 0
    print(f"✗ {head}: вне границ {len(stray)} из {len(files)}", file=out)
    for f in stray:
        print(f"    {f}", file=out)
    print("  в границах: " + ", ".join(record.paths + allowances(profile)), file=out)
    print("  каждый файл вне границ — убрать, объяснить в PR или спросить автора", file=out)
    return 1


def _start(repo, profile, args, out):
    since = "HEAD"
    if args and args[0] == "--from":
        if len(args) < 2:
            raise UsageError("scope start --from: нужен SHA")
        since, args = args[1], args[2:]
    if len(args) < 2 or not args[0].strip():
        raise UsageError('scope start: нужны название задачи и хотя бы один путь — '
                         'dpv scope start [--from SHA] "<задача>" ПУТЬ…')
    task, paths = args[0], [_validate(p) for p in args[1:]]
    start = select.commit(repo, since)
    if start is None:
        raise UsageError(f"scope start: {since!r} не найден в git" + (" — в репозитории нет коммитов"
                                                                     if since == "HEAD" else ""))
    path = record_path(repo, profile)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"task": task, "start": start, "paths": paths}, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"границы задачи «{task}»: от {start[:7]}, путей {len(paths)} — {profile.logs}/{RECORD}", file=out)
    return 0
