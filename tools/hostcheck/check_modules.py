#!/usr/bin/env python3
"""Границы модулей — как в docs/ARCHITECTURE.md, §2.

Таблица модулей в ARCHITECTURE.md (между метками `check_modules`) —
единственный источник: какие модули есть и от кого каждый может
зависеть. Проверка сверяет с ней:

  * `settings.gradle.kts` — подключены ровно модули из таблицы;
  * `project(":…")` в каждом `build.gradle.kts` — только разрешённые
    направления (компилятор ловит отсутствующую зависимость, но не
    лишнюю: «удобная» зависимость `routing → engine` скомпилируется);
  * импорты в основном коде (`src/main`) — правила, которых Gradle не
    выражает: `android.*` только в `app/`; модель сети
    (`network.simulation`) и фиктивное ядро (`engine.mock`) — только там,
    где это записано ниже с причиной.

    python3 tools/hostcheck/check_modules.py

Код возврата: 0 — чисто; 1 — нарушения; 2 — таблица не найдена.
"""
import os
import re
import sys

PROJ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DOC = os.path.join(PROJ, "docs", "ARCHITECTURE.md")
PKG = "com.dpsmpx.dppass"

# Импорты, которые компилятор пропустит, а архитектура — нет.
# (префикс пакета, кому можно в src/main — модули или файлы, причина)
RESTRICTED_IMPORTS = [
    ("android.", {"app"},
     "Android только в app/: ядро собирается и проверяется на JVM (D-02, D-21)"),
    (f"{PKG}.network.simulation.", {"network", "cli", "connection/src/main/kotlin/com/dpsmpx/dppass/connection/Orchestrator.kt"},
     "модель сети — инструмент тестов и cli; в connection — только SimulatedHealthChecker; в app/ — нельзя"),
    (f"{PKG}.engine.mock.", {"engine", "cli"},
     "фиктивное ядро — второй бэкенд тестов и cli; приложение работает на Xray (D-25), engine.mock в app/ — нельзя"),
]


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


def main_sources(module):
    root = os.path.join(PROJ, module, "src", "main")
    for dirpath, _, files in os.walk(root):
        for fn in files:
            if fn.endswith(".kt"):
                yield os.path.join(dirpath, fn)


def allowed(rel, module, who):
    return module in who or rel in who


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

    files = 0
    for m in sorted(table):
        for path in main_sources(m):
            files += 1
            rel = os.path.relpath(path, PROJ)
            for n, line in enumerate(read(path).splitlines(), 1):
                im = re.match(r"\s*import\s+([\w.]+)", line)
                if not im:
                    continue
                name = im.group(1) + "."
                for prefix, who, why in RESTRICTED_IMPORTS:
                    if name.startswith(prefix) and not allowed(rel, m, who):
                        problems.append(f"{rel}:{n}: импорт {im.group(1)} — {why}")

    if problems:
        print("✗ Границы модулей нарушены:")
        for p in problems:
            print("    " + p)
        print("  Направления зависимостей меняются только вместе с docs/ARCHITECTURE.md §2 (и причиной в DECISIONS.md, если меняется слой).")
        return 1
    print(f"✓ Модули: {len(table)} модулей, зависимости по ARCHITECTURE.md §2, {files} файлов src/main без запрещённых импортов")
    return 0


if __name__ == "__main__":
    sys.exit(main())
