#!/usr/bin/env python3
"""Какие модули затронуты изменением — для уровня `affected` (docs/PROCESS.md, «Проверки»).

Изменённые файлы — относительно merge-base с origin/main, плюс
незакоммиченные и новые. Модуль затронут, если в нём изменён файл или он
зависит (по таблице docs/ARCHITECTURE.md, §2, транзитивно) от изменённого.
Изменение сборки — все модули; только документы и проверки — ни одного
(достаточно static).

    python3 tools/verify/affected.py          # задачи Gradle через пробел (пусто — сборка не нужна)
    python3 tools/verify/affected.py --plan   # что выбрано и почему

Код возврата: 0; 2 — неверный вызов.
"""
import subprocess
import sys

from check_modules import parse_settings, parse_table
from common import Findings, module_of, root

BUILD_FILES = ("build-logic/", "gradle/", "settings.gradle.kts", "build.gradle.kts", "gradle.properties",
               "gradlew", "gradlew.bat")


def git_lines(*args):
    try:
        out = subprocess.run(["git", *args], cwd=root(), capture_output=True, check=True).stdout
        return [l for l in out.decode("utf-8").splitlines() if l]
    except (subprocess.CalledProcessError, FileNotFoundError):
        return None


def changed_files():
    base = git_lines("merge-base", "HEAD", "origin/main")
    if base is None:
        return None, "нет origin/main — выбираются все модули"
    # --relative: dpai — каталог DPmetaApp (D-55); пути — от корня подсистемы, как в таблице модулей.
    committed = git_lines("diff", "--name-only", "--relative", base[0], "HEAD") or []
    worktree = git_lines("diff", "--name-only", "--relative", "HEAD") or []
    untracked = git_lines("ls-files", "--others", "--exclude-standard") or []
    return sorted(set(committed + worktree + untracked)), f"изменения от {base[0][:12]} (merge-base с origin/main)"


def select():
    table = parse_table(Findings("модули"))
    modules = parse_settings() & set(table)
    reasons = {}
    files, why = changed_files()
    if files is None:
        return {m: [why] for m in modules}, why
    build = [rel for rel in files
             if rel in BUILD_FILES or any(rel.startswith(p) for p in BUILD_FILES if p.endswith("/"))]
    if build:
        more = f" и ещё {len(build) - 1}" if len(build) > 1 else ""
        for m in modules:
            reasons[m] = [f"изменена сборка: {build[0]}{more}"]
    for rel in files:
        mod = module_of(rel)
        if mod in modules and rel not in build:
            reasons.setdefault(mod, []).append(f"изменён {rel}")
    # Транзитивно вверх по графу: кто зависит от затронутого.
    changed = True
    while changed:
        changed = False
        for m in sorted(modules):
            if m in reasons:
                continue
            for dep in sorted(table.get(m, set())):
                if dep in reasons:
                    reasons[m] = [f"зависит от {dep}"]
                    changed = True
                    break
    return reasons, why


def main():
    args = sys.argv[1:]
    if args not in ([], ["--plan"]):
        print("использование: affected.py [--plan]")
        return 2
    reasons, why = select()
    if args == ["--plan"]:
        print(f"affected: {why}")
        if not reasons:
            print("  модулей нет — сборка не нужна, достаточно static")
        for m in sorted(reasons):
            print(f"  :{m.replace('/', ':')}:check — {'; '.join(reasons[m][:3])}"
                  + (f" (и ещё {len(reasons[m]) - 3})" if len(reasons[m]) > 3 else ""))
        return 0
    print(" ".join(f":{m.replace('/', ':')}:check" for m in sorted(reasons)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
