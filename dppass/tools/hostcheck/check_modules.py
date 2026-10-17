#!/usr/bin/env python3
"""Границы модулей — как в docs/ARCHITECTURE.md, §2.

Таблица модулей в ARCHITECTURE.md (между метками `check_modules`) —
единственный источник: какие модули есть и от кого каждый может
зависеть. Проверка сверяет с ней:

  * `settings.gradle.kts` — подключены ровно модули из таблицы;
  * `project(":…")` в каждом `build.gradle.kts` — только разрешённые
    направления (компилятор ловит отсутствующую зависимость, но не
    лишнюю: «удобная» зависимость `routing → engine` скомпилируется).

Импорты, которых Gradle не выражает (`android.*` только в `app/`, модель
сети и фиктивное ядро — только там, где разрешено), проверяет DP Verify:
`code.forbidden`, правила `android-import`, `simulation-import`,
`mock-engine-import` в `tools/dpverify.toml` (D-50).

    python3 tools/hostcheck/check_modules.py

Код возврата: 0 — чисто; 1 — нарушения; 2 — таблица не найдена.
"""
import os
import re
import sys

PROJ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DOC = os.path.join(PROJ, "docs", "ARCHITECTURE.md")


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def load_table():
    """{модуль: множество разрешённых прямых зависимостей} из ARCHITECTURE.md."""
    if not os.path.isfile(DOC):
        return None
    m = re.search(r"<!-- check_modules: begin -->(.*?)<!-- check_modules: end -->", read(DOC), re.S)
    if not m:
        return None
    table = {}
    for line in m.group(1).splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 3 or not cells[0].startswith("`"):
            continue
        name = cells[0].strip("`")
        deps = set(re.findall(r"`([a-z/]+)`", cells[-1]))
        table[name] = deps
    return table


def gradle_path(module):
    return ":" + module.replace("/", ":")


def included_modules():
    text = read(os.path.join(PROJ, "settings.gradle.kts"))
    return {p.lstrip(":").replace(":", "/") for p in re.findall(r'"(:[a-z:]+)"', text)}


def project_deps(module):
    path = os.path.join(PROJ, module, "build.gradle.kts")
    if not os.path.isfile(path):
        return None
    return {p.lstrip(":").replace(":", "/") for p in re.findall(r'project\("(:[a-z:]+)"\)', read(path))}


def main():
    table = load_table()
    if not table:
        print("✗ check_modules: в docs/ARCHITECTURE.md нет таблицы модулей между метками check_modules")
        return 2
    problems = []

    included = included_modules()
    for m in sorted(included - set(table)):
        problems.append(f"settings.gradle.kts подключает {gradle_path(m)}, которого нет в таблице ARCHITECTURE.md §2")
    for m in sorted(set(table) - included):
        problems.append(f"в таблице ARCHITECTURE.md §2 есть {m}, но settings.gradle.kts его не подключает")
    for m, deps in table.items():
        for d in deps - set(table):
            problems.append(f"таблица ARCHITECTURE.md §2: {m} зависит от неизвестного модуля {d}")

    for m in sorted(table):
        deps = project_deps(m)
        if deps is None:
            problems.append(f"{m}: нет build.gradle.kts")
            continue
        for d in sorted(deps - table[m]):
            problems.append(f"{m}/build.gradle.kts: зависимость {gradle_path(d)} не разрешена (ARCHITECTURE.md §2: {m} → {', '.join(sorted(table[m])) or '—'})")

    if problems:
        print("✗ Границы модулей нарушены:")
        for p in problems:
            print("    " + p)
        print("  Направления зависимостей меняются только вместе с docs/ARCHITECTURE.md §2 (и причиной в DECISIONS.md, если меняется слой).")
        return 1
    print(f"✓ Модули: {len(table)} модулей, подключение и зависимости — по ARCHITECTURE.md §2")
    return 0


if __name__ == "__main__":
    sys.exit(main())
