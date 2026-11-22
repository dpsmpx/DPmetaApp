#!/usr/bin/env python3
"""Зависимости и сборка — по каталогу и PROVENANCE (D-15, D-17, T11).

  * каждая версия gradle/libs.versions.toml — строкой в таблице
    docs/PROVENANCE.md, §2 с той же версией, и каждая строка таблицы — в
    каталоге;
  * в *.gradle.kts нет координат с версией и версий плагинов мимо каталога;
  * сумма дистрибутива Gradle в wrapper и SHA-256 gradle-wrapper.jar —
    те же, что в docs/PROVENANCE.md, §3;
  * каждый двоичный файл репозитория — в docs/PROVENANCE.md, §4 с той же суммой;
  * разрешён только workflow APK корневого DPmetaApp (DPmetaApp:D-187).

    python3 tools/verify/check_dependencies.py

Код возврата: 0 — чисто; 1 — нарушения.
"""
import hashlib
import os
import re
import tomllib

from common import Findings, files, is_text, main, read, root

CATALOG = "gradle/libs.versions.toml"
PROVENANCE = "docs/PROVENANCE.md"
WRAPPER_PROPS = "gradle/wrapper/gradle-wrapper.properties"
WRAPPER_JAR = "gradle/wrapper/gradle-wrapper.jar"
TABLE = re.compile(r"<!-- provenance: begin -->(.*?)<!-- provenance: end -->", re.S)
COORD = re.compile(r'"[\w.-]+:[\w.-]+:\d[^"]*"')
PLUGIN_VERSION = re.compile(r'\bid\("[^"]+"\)\s+version\s+"|\bkotlin\("[^"]+"\)\s+version\s+"')


def sha256(rel):
    return hashlib.sha256(open(os.path.join(root(), rel), "rb").read()).hexdigest()


def provenance_rows(f):
    m = TABLE.search(read(PROVENANCE))
    if not m:
        f.add(PROVENANCE, "нет таблицы зависимостей между метками provenance")
        return {}
    rows = {}
    for line in m.group(1).splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) >= 2 and cells[0].startswith("`"):
            rows[re.match(r"`([^`]+)`", cells[0]).group(1)] = cells[1]
    return rows


def check():
    f = Findings("зависимости")
    all_files = files()
    if not os.path.isfile(os.path.join(root(), CATALOG)):
        f.add(CATALOG, "нет каталога версий")
        return f.finish("")
    with open(os.path.join(root(), CATALOG), "rb") as fh:
        catalog = tomllib.load(fh)
    versions = catalog.get("versions", {})
    rows = provenance_rows(f)
    for key, ver in versions.items():
        if key not in rows:
            f.add(CATALOG, f"версия {key} = {ver} без строки в {PROVENANCE}, §2")
        elif rows[key] != ver:
            f.add(PROVENANCE, f"{key}: в каталоге {ver}, в PROVENANCE {rows[key]}")
    for key in rows:
        if key not in versions:
            f.add(PROVENANCE, f"строка {key} без версии в каталоге")

    for rel in all_files:
        if rel.endswith(".gradle.kts"):
            text = "\n".join(l.split("//", 1)[0] for l in read(rel).splitlines())
            if COORD.search(text):
                f.add(rel, "координаты с версией мимо каталога gradle/libs.versions.toml")
            if PLUGIN_VERSION.search(text):
                f.add(rel, "версия плагина мимо каталога gradle/libs.versions.toml")
        if rel.startswith(".github/workflows/"):
            f.add(rel, "в подсистеме нельзя добавлять workflow; APK собирает DPmetaApp:D-187")

    prov = read(PROVENANCE)
    if os.path.isfile(os.path.join(root(), WRAPPER_PROPS)):
        props = read(WRAPPER_PROPS)
        m = re.search(r"^distributionSha256Sum=([0-9a-f]{64})$", props, re.M)
        if not m:
            f.add(WRAPPER_PROPS, "нет distributionSha256Sum: дистрибутив Gradle не сверяется")
        elif m.group(1) not in prov:
            f.add(WRAPPER_PROPS, f"сумма дистрибутива Gradle не записана в {PROVENANCE}, §3")
        v = re.search(r"gradle-([\d.]+)-bin\.zip", props)
        if v and f"| Версия | {v.group(1)} |" not in prov:
            f.add(WRAPPER_PROPS, f"версия Gradle {v.group(1)} не совпадает с {PROVENANCE}, §3")
    binaries = [p for p in all_files if not is_text(p)]
    for rel in binaries:
        digest = sha256(rel)
        if f"`{rel}` | `{digest}`" not in prov:
            f.add(rel, f"двоичный файл без строки с SHA-256 в {PROVENANCE}, §4")
    return f.finish(f"версий — {len(versions)}, двоичных файлов — {len(binaries)}")


if __name__ == "__main__":
    main(check)
