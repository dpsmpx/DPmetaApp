#!/usr/bin/env python3
"""Ссылки на тесты по методу ведут на существующий тест.

Пути `docs/…` и `tools/…`, имена документов, решения D-NN, разделы и §,
угрозы T-N, этапы плана и классы тестов проверяет DP Verify — `docs.links`
и `docs.refs` (`tools/dpverify.toml`, D-39). Здесь — то, чего у DP Verify
до 1.x нет: ссылка `ИмяTest.метод` — у класса теста есть такой метод.
Проверяются все текстовые файлы репозитория, кроме корпуса tests/data и
копии DP Verify.

    python3 tools/hostcheck/check_docs.py

Код возврата: 0 — чисто; 1 — висячие ссылки.
"""
import os
import re
import subprocess
import sys

PROJ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TEXT = (".md", ".kt", ".kts", ".py", ".sh", ".xml", ".toml", ".properties", ".yml", ".yaml", ".txt", ".gitignore")
TEST_REF = re.compile(r"`([A-Z]\w*Test)\.(\w+)`")


def files():
    out = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                         cwd=PROJ, capture_output=True, check=True).stdout.decode("utf-8").split("\0")
    return sorted(p for p in out if p and os.path.isfile(os.path.join(PROJ, p)))


def read(rel):
    with open(os.path.join(PROJ, rel), encoding="utf-8", errors="replace") as f:
        return f.read()


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
    # tools/dpv/ — копия DP Verify (D-39): её сверяет dpv.integrity.
    text_files = [p for p in all_files if p.endswith(TEXT) and not p.startswith(("tests/data/", "tools/dpv/"))]
    problems, count = [], 0
    for rel in text_files:
        for n, line in enumerate(read(rel).splitlines(), 1):
            for m in TEST_REF.finditer(line):
                count += 1
                cls, method = m.group(1), m.group(2)
                # нет класса — находка docs.refs (вид test-class); здесь — только метод
                if cls in tests and method not in tests[cls]:
                    problems.append(f"{rel}:{n}: в {cls} нет теста {method}")
    if problems:
        print("✗ Висячие ссылки на тесты:")
        for p in problems:
            print("    " + p)
        return 1
    print(f"✓ Ссылки на тесты: {len(text_files)} файлов, {count} ссылок «класс.метод» ведут на существующие тесты")
    return 0


if __name__ == "__main__":
    sys.exit(main())
