#!/usr/bin/env python3
"""Ссылки в коде и документах ведут туда, где что-то есть.

Код DPpass объясняет себя ссылками: на решение (D-NN), документ, раздел,
угрозу, этап плана. Ссылка в пустоту хуже её отсутствия — она выглядит
как обоснование. Проверяются все текстовые файлы репозитория, кроме
корпуса tests/data:

  * пути `docs/…` и `tools/…` существуют (маска — совпадает хоть с одним
    файлом); имя документа без пути (`ИМЯ.md`) — есть в репозитории;
  * каждое D-NN определено заголовком `### D-NN` в docs/direction/DECISIONS.md;
  * «документ, «Раздел»» и «документ, §N» — такой заголовок в документе есть;
  * угрозы T-N (рядом со ссылкой на модель безопасности или в скобках) —
    определены в docs/SECURITY_MODEL.md;
  * «этап N» рядом с ROADMAP — этап есть в docs/direction/ROADMAP/README.md;
  * тест по имени (`ИмяTest` или `ИмяTest.метод`) — такой класс и метод есть.

    python3 tools/hostcheck/check_docs.py

Код возврата: 0 — чисто; 1 — висячие ссылки.
"""
import glob
import os
import re
import subprocess
import sys

PROJ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DECISIONS = "docs/direction/DECISIONS.md"
SECURITY = "docs/SECURITY_MODEL.md"
ROADMAP = "docs/direction/ROADMAP/README.md"
TEXT = (".md", ".kt", ".kts", ".py", ".sh", ".xml", ".toml", ".properties", ".yml", ".yaml", ".txt", ".gitignore")

PATH_REF = re.compile(r"(?<![\w/.-])((?:docs|tools)/[A-Za-z0-9_./*-]*[A-Za-z0-9_*/])")
NAME_REF = re.compile(r"`([A-Z][A-Z0-9_]*\.md)`")
DECISION_REF = re.compile(r"\bD-(\d{2})\b")
DOC = r"`?((?:[\w.-]+/)*[\w.-]+\.md)`?"
SECTION_REF = re.compile(DOC + r"\s*,\s*(?:раздел\s+)?«([^»]+)»")
PARA_REF = re.compile(DOC + r"\s*,?\s*§\s*(\d+)")
THREAT = re.compile(r"\bT(\d{1,2})\b")
THREAT_CONTEXT = re.compile(r"SECURITY_MODEL|угроз|\((?:[^()]*,\s*)?T\d{1,2}\)")
STAGE = re.compile(r"[Ээ]тап\w*\s+(\d+)")
TEST_REF = re.compile(r"`([A-Z]\w*Test)(?:\.(\w+))?`")


def files():
    out = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                         cwd=PROJ, capture_output=True, check=True).stdout.decode("utf-8").split("\0")
    return sorted(p for p in out if p and os.path.isfile(os.path.join(PROJ, p)))


def read(rel):
    with open(os.path.join(PROJ, rel), encoding="utf-8", errors="replace") as f:
        return f.read()


def headings(rel):
    path = os.path.join(PROJ, rel)
    if not os.path.isfile(path):
        return None
    return [line.lstrip("#").strip() for line in read(rel).splitlines() if re.match(r"#{1,6}\s", line)]


def test_classes(all_files):
    """{класс теста: множество его методов} по тестовым исходникам (src/test, src/xrayJni — всё, кроме src/main)."""
    out = {}
    for rel in all_files:
        if "/src/" not in rel or "/src/main/" in rel or not rel.endswith(".kt"):
            continue
        text = read(rel)
        marks = [(m.start(), m.group(1)) for m in re.finditer(r"^class\s+(\w+)", text, re.M)]
        for i, (start, name) in enumerate(marks):
            end = marks[i + 1][0] if i + 1 < len(marks) else len(text)
            out[name] = set(re.findall(r"\bfun\s+(\w+)", text[start:end]))
    return out


def main():
    all_files = files()
    tests = test_classes(all_files)
    by_name = {}
    for rel in all_files:
        by_name.setdefault(os.path.basename(rel), []).append(rel)
    # tools/dpv/ — копия DP Verify (D-39): её сверяет dpv.integrity, а пути в её коде — о DP-Verify.
    text_files = [p for p in all_files if p.endswith(TEXT) and not p.startswith(("tests/data/", "tools/dpv/"))]

    dec_heads = headings(DECISIONS) or []
    decisions = {m.group(1) for h in dec_heads for m in [re.match(r"D-(\d{2})\b", h)] if m}
    sec_text = read(SECURITY) if os.path.isfile(os.path.join(PROJ, SECURITY)) else ""
    threats = set(re.findall(r"^(?:#{2,4}\s+|\|\s*\*{0,2})T(\d{1,2})\b", sec_text, re.M))
    stage_heads = headings(ROADMAP) or []
    stages = {m.group(1) for h in stage_heads for m in [re.match(r"Этап\s+(\d+)", h)] if m}

    def resolve(doc, here):
        """Имя документа из ссылки → путь в репозитории (или None)."""
        if "/" in doc:
            for base in (PROJ, os.path.join(PROJ, os.path.dirname(here))):
                cand = os.path.normpath(os.path.join(base, doc))
                if os.path.isfile(cand):
                    return os.path.relpath(cand, PROJ)
            return None
        same_dir = os.path.join(os.path.dirname(here), doc)
        if same_dir in all_files:
            return same_dir
        found = by_name.get(doc, [])
        return found[0] if len(found) == 1 else None

    problems = []
    counts = {"paths": 0, "decisions": 0, "sections": 0, "threats": 0, "stages": 0, "tests": 0}
    for rel in text_files:
        for n, line in enumerate(read(rel).splitlines(), 1):
            where = f"{rel}:{n}"
            for m in PATH_REF.finditer(line):
                ref = m.group(1).rstrip("/.")
                counts["paths"] += 1
                if "*" in ref:
                    if not glob.glob(os.path.join(PROJ, ref)):
                        problems.append(f"{where}: маска {ref} ни с чем не совпадает")
                elif not os.path.exists(os.path.join(PROJ, ref)):
                    problems.append(f"{where}: нет {ref}")
            for m in NAME_REF.finditer(line):
                if m.group(1) not in by_name:
                    problems.append(f"{where}: нет документа {m.group(1)}")
            for m in DECISION_REF.finditer(line):
                counts["decisions"] += 1
                if m.group(1) not in decisions:
                    problems.append(f"{where}: решение D-{m.group(1)} не определено в {DECISIONS}")
            for m in SECTION_REF.finditer(line):
                target = resolve(m.group(1), rel)
                if target is None:
                    continue  # чужой документ (например, в репозитории Happ) — не наш раздел
                counts["sections"] += 1
                want = m.group(2).strip().lower()
                if not any(want in h.lower() for h in headings(target)):
                    problems.append(f"{where}: в {target} нет раздела «{m.group(2)}»")
            for m in PARA_REF.finditer(line):
                target = resolve(m.group(1), rel)
                if target is None:
                    continue
                counts["sections"] += 1
                if not any(re.match(r"(§\s*)?" + m.group(2) + r"\b[.\s]", h) for h in headings(target)):
                    problems.append(f"{where}: в {target} нет раздела §{m.group(2)}")
            if THREAT_CONTEXT.search(line) and rel != SECURITY:
                for m in THREAT.finditer(line):
                    counts["threats"] += 1
                    if m.group(1) not in threats:
                        problems.append(f"{where}: угроза T{m.group(1)} не определена в {SECURITY}")
            for m in TEST_REF.finditer(line):
                counts["tests"] += 1
                cls, method = m.group(1), m.group(2)
                if cls not in tests:
                    problems.append(f"{where}: нет класса теста {cls}")
                elif method and method not in tests[cls]:
                    problems.append(f"{where}: в {cls} нет теста {method}")
            if "ROADMAP" in line:
                for m in STAGE.finditer(line):
                    counts["stages"] += 1
                    if m.group(1) not in stages:
                        problems.append(f"{where}: этапа {m.group(1)} нет в {ROADMAP}")

    if problems:
        print("✗ Висячие ссылки:")
        for p in problems:
            print("    " + p)
        return 1
    print(f"✓ Ссылки: {len(text_files)} файлов — пути ({counts['paths']}), решения ({counts['decisions']}), "
          f"разделы ({counts['sections']}), угрозы ({counts['threats']}), этапы ({counts['stages']}), "
          f"тесты ({counts['tests']}) ведут на существующее")
    return 0


if __name__ == "__main__":
    sys.exit(main())
