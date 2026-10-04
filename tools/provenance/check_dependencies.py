#!/usr/bin/env python3
"""Каждая зависимость — с лицензией и происхождением (D-18, D-20).

Сверяет то, что сборка реально берёт, с тем, что записано:

  * каждая библиотека и плагин из `gradle/libs.versions.toml` — строка в
    `docs/legal/THIRD_PARTY_MATRIX.md` (координаты и **та же версия**) и
    в `docs/PROVENANCE.md`; смена версии без правки документов — ошибка;
  * зависимостей мимо каталога нет: строковые координаты
    `"группа:артефакт:версия"` и `id(…) version …` в `*.gradle.kts`
    запрещены (исключение — `kotlin("test")`: версия = версии Kotlin);
  * репозитории — только google(), mavenCentral(), gradlePluginPortal();
  * Gradle: версия дистрибутива и его SHA-256 из
    `gradle-wrapper.properties` и SHA-256 `gradle-wrapper.jar` совпадают
    с записанными в PROVENANCE.md (сверены с services.gradle.org);
  * каждый двоичный файл в репозитории записан в PROVENANCE.md с SHA-256;
  * workflow GitHub Actions нет: CI не используется и не запускается,
    сторонних действий в цепочке поставки нет (D-23).

    python3 tools/provenance/check_dependencies.py

Код возврата: 0 — чисто; 1 — расхождения.
"""
import hashlib
import os
import re
import subprocess
import sys
import tomllib

PROJ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CATALOG = os.path.join(PROJ, "gradle", "libs.versions.toml")
MATRIX = os.path.join(PROJ, "docs", "legal", "THIRD_PARTY_MATRIX.md")
PROVENANCE = os.path.join(PROJ, "docs", "PROVENANCE.md")
WRAPPER_PROPS = os.path.join(PROJ, "gradle", "wrapper", "gradle-wrapper.properties")
REPOSITORIES = {"google()", "mavenCentral()", "gradlePluginPortal()"}
# Версия задаётся не каталогом, а самим плагином Kotlin — и потому совпадает с ним.
IMPLICIT = {'kotlin("test")': ("org.jetbrains.kotlin:kotlin-test", "kotlin")}


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def sha256(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def table_rows(text):
    return [line for line in text.splitlines() if line.lstrip().startswith("|")]


def row_with(rows, coord):
    needle = f"`{coord}`"
    return [r for r in rows if needle in r]


def tracked_files():
    out = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                         cwd=PROJ, capture_output=True, check=True).stdout.decode("utf-8").split("\0")
    return sorted(p for p in out if p and os.path.isfile(os.path.join(PROJ, p)))


def main():
    problems = []
    with open(CATALOG, "rb") as f:
        catalog = tomllib.load(f)
    versions = catalog.get("versions", {})
    matrix = read(MATRIX) if os.path.isfile(MATRIX) else ""
    provenance = read(PROVENANCE) if os.path.isfile(PROVENANCE) else ""
    if not matrix:
        problems.append("нет docs/legal/THIRD_PARTY_MATRIX.md")
    if not provenance:
        problems.append("нет docs/PROVENANCE.md")
    mrows, prows = table_rows(matrix), table_rows(provenance)

    def version_of(entry):
        if isinstance(entry, str):
            return entry.rsplit(":", 1)[1] if entry.count(":") == 2 else None
        v = entry.get("version")
        if isinstance(v, dict):
            return versions.get(v.get("ref")) if "ref" in v else v.get("strictly") or v.get("require")
        if isinstance(v, str):
            return v
        return versions.get(entry["version.ref"]) if "version.ref" in entry else None

    boms = {}
    entries = []
    for alias, lib in catalog.get("libraries", {}).items():
        coord = lib if isinstance(lib, str) and lib.count(":") >= 1 else lib.get("module") or f"{lib['group']}:{lib['name']}"
        coord = ":".join(coord.split(":")[:2])
        ver = version_of(lib)
        if coord.endswith("-bom") and ver:
            boms[coord.rsplit(":", 1)[0]] = ver
        entries.append((f"libraries.{alias}", coord, ver))
    for alias, plugin in catalog.get("plugins", {}).items():
        pid = plugin if isinstance(plugin, str) else plugin["id"]
        entries.append((f"plugins.{alias}", pid.split(":")[0], version_of(plugin)))

    checked = 0
    for where, coord, ver in entries:
        if ver is None:
            # Версия — из BOM той же группы (junit-bom → junit-jupiter).
            group = coord.split(":")[0]
            ver = next((v for g, v in boms.items() if group == g or group.startswith(g + ".")), None)
            if ver is None:
                problems.append(f"libs.versions.toml {where}: {coord} без версии и без BOM своей группы")
                continue
        checked += 1
        for name, rows in (("THIRD_PARTY_MATRIX.md", mrows), ("PROVENANCE.md", prows)):
            found = row_with(rows, coord)
            if not found:
                problems.append(f"{name}: нет строки для `{coord}` ({where} {ver})")
            elif not any(re.search(r"(?<![\w.])" + re.escape(ver) + r"(?![\w.])", r) for r in found):
                problems.append(f"{name}: строка `{coord}` не с версией {ver} (каталог изменён без записи)")

    for call, (coord, vref) in IMPLICIT.items():
        ver = versions.get(vref)
        if not any(ver in r for r in row_with(mrows, coord)):
            problems.append(f"THIRD_PARTY_MATRIX.md: нет строки `{coord}` {ver} (подключается как {call})")

    gradle_files = [p for p in tracked_files() if p.endswith(".gradle.kts")]
    for rel in gradle_files:
        text = re.sub(r"//[^\n]*", "", read(os.path.join(PROJ, rel)))
        for m in re.finditer(r'"([\w.-]+:[\w.-]+:[\w.${}-]+)"', text):
            problems.append(f"{rel}: координаты мимо каталога: {m.group(1)} — только через gradle/libs.versions.toml")
        for m in re.finditer(r'\bid\s*\(\s*"[^"]+"\s*\)\s*version\b', text):
            problems.append(f"{rel}: версия плагина мимо каталога: {m.group(0)}")
        for m in re.finditer(r"\b(maven|ivy|jcenter|mavenLocal|flatDir)\s*[({]", text):
            problems.append(f"{rel}: репозиторий {m.group(1)} — разрешены только {', '.join(sorted(REPOSITORIES))}")

    props = dict(line.split("=", 1) for line in read(WRAPPER_PROPS).splitlines() if "=" in line and not line.startswith("#"))
    m = re.search(r"gradle-([\d.]+)-(bin|all)\.zip", props.get("distributionUrl", ""))
    gradle_version = m.group(1) if m else None
    dist_sha = props.get("distributionSha256Sum", "").strip()
    if not dist_sha:
        problems.append("gradle-wrapper.properties: нет distributionSha256Sum — дистрибутив Gradle не проверяется при загрузке")
    elif dist_sha not in provenance:
        problems.append(f"PROVENANCE.md: не записан SHA-256 дистрибутива Gradle {gradle_version} ({dist_sha[:16]}…)")
    if gradle_version and not any(gradle_version in r for r in row_with(mrows, "gradle")):
        problems.append(f"THIRD_PARTY_MATRIX.md: нет строки `gradle` с версией {gradle_version}")

    for rel in tracked_files():
        if rel.startswith(".github/workflows/"):
            problems.append(f"{rel}: workflow GitHub Actions — CI не используется и не запускается (D-23); "
                            "готовность — tools/hostcheck/run.sh --full локально")

    binaries = 0
    for rel in tracked_files():
        path = os.path.join(PROJ, rel)
        with open(path, "rb") as f:
            head = f.read(8192)
        if b"\0" not in head:
            continue
        binaries += 1
        digest = sha256(path)
        rows = row_with(prows, rel)
        if not rows:
            problems.append(f"PROVENANCE.md: двоичный файл {rel} не записан (откуда он и его SHA-256)")
        elif not any(digest in r for r in rows):
            problems.append(f"PROVENANCE.md: SHA-256 {rel} не совпадает с записанным (сейчас {digest})")

    if problems:
        print("✗ Зависимости и происхождение:")
        for p in problems:
            print("    " + p)
        print("  Новая зависимость или версия — по чек-листу D-18 вместе со строками THIRD_PARTY_MATRIX.md и PROVENANCE.md (docs/PROCESS.md).")
        return 1
    print(f"✓ Зависимости: {checked} записей каталога сверены с матрицей лицензий и PROVENANCE.md; "
          f"Gradle {gradle_version} и {binaries} двоичный файл — с записанными SHA-256; workflow CI нет")
    return 0


if __name__ == "__main__":
    sys.exit(main())
