#!/usr/bin/env python3
"""Модули и направления зависимостей — по таблице docs/ARCHITECTURE.md, §2 (D-21).

Компилятор ловит отсутствующую зависимость, но не лишнюю. Эта проверка
сверяет с таблицей:

  * каждый модуль settings.gradle.kts есть в таблице, и наоборот (кроме
    `android`: он подключается на этапе 11 и только при Android SDK);
  * каждая зависимость `project(":…")` в build.gradle.kts модуля — из столбца
    «Может зависеть от»; `testkit` — только в тестовых конфигурациях;
  * `android.*` и `androidx.*` — только в модуле `android` и в пробнике
    `probe` (D-02, D-66);
  * пакет исходника — `com.dpsmpx.dpai.<модуль>` (`tests/integration` —
    `com.dpsmpx.dpai.tests.integration`);
  * у модулей нет сторонних зависимостей времени выполнения (`libs.…` не в
    тестовой конфигурации) — только Kotlin stdlib (D-17); исключение —
    модуль из RUNTIME_DEPS_ALLOWED с причиной.

    python3 tools/verify/check_modules.py

Код возврата: 0 — чисто; 1 — нарушения.
"""
import os
import re

from common import ARCHITECTURE, Findings, files, main, module_of, read, root

ALLOWED_ABSENT = {"android", "probe"}  # docs/ARCHITECTURE.md, §2: подключаются при Android SDK
# Модули с Android API (D-02): оболочка и пробник B2 (D-66).
ANDROID_MODULES = {"android", "probe"}
TEST_ONLY = {"testkit"}
TABLE = re.compile(r"<!-- check_modules: begin -->(.*?)<!-- check_modules: end -->", re.S)
INCLUDE_ARG = re.compile(r'"(:[\w:-]+)"')
PROJECT_DEP = re.compile(r'^\s*(\w+)\(\s*project\(\s*"(:[\w:-]+)"\s*\)\s*\)', re.M)
LIB_DEP = re.compile(r'^\s*(\w+)\(\s*(?:platform\()?\s*libs\.([\w.]+)', re.M)
# Модуль → причина, по которой ему можно стороннюю библиотеку во время выполнения (D-17).
RUNTIME_DEPS_ALLOWED = {
    "android": "оболочка Android: Compose и AndroidX (D-34)",
    "jsch": "SSH-клиент на JVM для Android (D-35)",
    "probe": "пробник B2: Compose и AndroidX, как оболочка (D-66)",
}
PACKAGE = re.compile(r"^\s*package\s+([\w.]+)", re.M)
ANDROID_IMPORT = re.compile(r"^\s*import\s+(androidx?\.[\w.]+)", re.M)


def gradle_to_dir(path):
    return path.lstrip(":").replace(":", "/")


def parse_table(f):
    text = read(ARCHITECTURE)
    m = TABLE.search(text)
    if not m:
        f.add(ARCHITECTURE, "нет таблицы между метками check_modules")
        return {}
    table = {}
    for line in m.group(1).splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 3 or not cells[0].startswith("`"):
            continue
        name = cells[0].strip("`")
        deps = set(re.findall(r"`([\w/-]+)`", cells[2]))
        table[name] = deps
    return table


def parse_settings():
    text = read("settings.gradle.kts")
    text = "\n".join(l.split("//", 1)[0] for l in text.splitlines())
    mods = set()
    for call in re.finditer(r"include\((.*?)\)", text, re.S):
        mods.update(gradle_to_dir(a) for a in INCLUDE_ARG.findall(call.group(1)))
    return mods


def package_for(module):
    return "com.dpsmpx.dpai." + module.replace("/", ".").replace("-", "")


def check():
    f = Findings("модули")
    table = parse_table(f)
    if not table:
        return f.finish("")
    settings = parse_settings()
    for m in sorted(settings - set(table)):
        f.add("settings.gradle.kts", f"модуль {m} не описан в {ARCHITECTURE}, §2")
    for m in sorted(set(table) - settings - ALLOWED_ABSENT):
        f.add(ARCHITECTURE, f"модуль {m} есть в таблице, но не подключён в settings.gradle.kts")
    for m, deps in table.items():
        for d in deps - set(table):
            f.add(ARCHITECTURE, f"модуль {m} зависит от неизвестного модуля {d}")

    for m in sorted(settings & set(table)):
        build = f"{m}/build.gradle.kts"
        if not os.path.isfile(os.path.join(root(), build)):
            f.add(build, "нет файла сборки модуля")
            continue
        for cfg, lib in LIB_DEP.findall(read(build)):
            if not cfg.startswith("test") and m not in RUNTIME_DEPS_ALLOWED:
                f.add(build, f"{cfg}(libs.{lib}) — сторонняя библиотека во время выполнения (D-17)")
        for cfg, dep in PROJECT_DEP.findall(read(build)):
            d = gradle_to_dir(dep)
            is_test = cfg.startswith("test")
            if d in TEST_ONLY:
                if not is_test:
                    f.add(build, f"{d} — только в тестовых конфигурациях, а здесь {cfg}")
            elif d not in table.get(m, set()):
                f.add(build, f"{m} не может зависеть от {d} ({cfg}): нет в {ARCHITECTURE}, §2")

    for rel in files():
        if not rel.endswith(".kt"):
            continue
        mod = module_of(rel)
        text = read(rel)
        if mod not in ANDROID_MODULES:
            for imp in ANDROID_IMPORT.findall(text):
                f.add(rel, f"{imp} — Android только в модуле android (D-02)")
        if "/src/" in rel and mod in table:
            pm = PACKAGE.search(text)
            want = package_for(mod)
            if not pm:
                f.add(rel, "нет объявления package")
            elif not (pm.group(1) == want or pm.group(1).startswith(want + ".")):
                f.add(rel, f"пакет {pm.group(1)} вне пакета модуля {want}")
    return f.finish(f"модулей — {len(settings)}, в таблице — {len(table)}")


if __name__ == "__main__":
    main(check)
