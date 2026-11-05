#!/usr/bin/env python3
"""Ссылки в коде и документах ведут туда, где что-то есть.

Код и документы объясняют себя ссылками: на решение, документ, раздел,
раздел ТЗ, угрозу, этап, тест. Ссылка в пустоту хуже её отсутствия — она
выглядит как обоснование. Проверяются все текстовые файлы, кроме самого ТЗ
(текст автора, D-19):

  * пути `docs/…`, `tools/…`, `gradle/…`, `build-logic/…`, `.claude/…`,
    `.github/…` существуют (маска — совпадает хоть с одним файлом);
    имя документа без пути (в обратных кавычках) — есть в репозитории;
  * каждое D-NN определено заголовком `### D-NN` в журнале решений;
  * «документ, «Раздел»» и «документ, §N» — такой заголовок в документе есть;
  * «ТЗ, §N» — раздел N есть в ТЗ;
  * угрозы T-N (рядом со словом «угроз», SECURITY_MODEL или в скобках) —
    определены в docs/SECURITY_MODEL.md;
  * «этап N» — этап есть в плане;
  * тест по имени (`ИмяTest` или `ИмяTest.метод`) — такой класс и метод есть;
    в карте финального аудита (`tools/audit/final_stability.py`) — и в кавычках.

Ссылка на другой репозиторий экосистемы (`DPpass:docs/…`, `DPpass:D-22`)
не сверяется: её проверяют средства DPmeta.

    python3 tools/verify/check_docs.py

Код возврата: 0 — чисто; 1 — висячие ссылки.
"""
import fnmatch
import os
import re

from common import (DECISIONS, ROADMAP, SECURITY, TZ, Findings, files, is_text, main,
                    read, root)

PREFIXES = r"(?:docs|tools|gradle|build-logic|\.claude|\.github)"
PATH_REF = re.compile(r"(?<![\w/.:-])(" + PREFIXES + r"/[A-Za-z0-9_./*-]*[A-Za-z0-9_*/])")
NAME_REF = re.compile(r"(?<![\w/.:-])`([\w-]+\.md)`")
DECISION_REF = re.compile(r"(?<![\w:])D-(\d{2,3})\b")
DOC = r"`?((?:[\w.-]+/)*[\w.-]+\.md)`?"
SECTION_REF = re.compile(r"(?<![\w/.:-])" + DOC + r"\s*,\s*(?:раздел\s+)?«([^»]+)»")
PARA_REF = re.compile(r"(?<![\w/.:-])" + DOC + r"\s*,\s*§\s*(\d+)")
TZ_REF = re.compile(r"ТЗ,\s*((?:§\s*\d+(?:\.\d+)?(?:\s*[–-]\s*§\s*\d+(?:\.\d+)?)?(?:\s*,\s*)?)+)")
TZ_NUM = re.compile(r"§\s*(\d+(?:\.\d+)?)")
THREAT = re.compile(r"(?<![\w:])T(\d{1,2})\b")
THREAT_CONTEXT = re.compile(r"SECURITY_MODEL|[Уу]гроз|\((?:[^()]*,\s*)?T\d{1,2}\)")
STAGE = re.compile(r"[Ээ]тап\w*\s+(\d+)")
TEST_REF = re.compile(r"`([A-Z]\w*Test)(?:\.(\w+))?`")
# Карта доказательств финального аудита (этап 19): тесты там — строками в кавычках, а не в `…`.
AUDIT_MAP = "tools/audit/final_stability.py"
AUDIT_REF = re.compile(r'"([A-Z]\w*Test)(?:\.(\w+))?"')
# Не сверяются: текст автора (D-19) и образцы самотестов — в них ссылки
# сломаны намеренно, чтобы проверки доказали, что ловят их.
SKIP = {TZ, "tools/verify/selftest.py"}
# Пути, которых быть не должно: ссылка на них — о запрете (D-15).
ABSENT_BY_DESIGN = {".github/workflows"}
# Не пути: имена методов протокола MCP с тем же началом, что у каталога tools/ (D-50).
NOT_PATHS = {"tools/list", "tools/call"}
HEADING = re.compile(r"^#{1,6}\s+(.*?)\s*$", re.M)


def headings(rel):
    if not os.path.isfile(os.path.join(root(), rel)):
        return None
    out = set()
    for h in HEADING.findall(read(rel)):
        h = h.strip().strip("`")
        out.add(h)
        out.add(re.sub(r"^\d+(?:\.\d+)*\.\s*", "", h))  # «2. Проверки» → «Проверки»
        m = re.match(r"^(\d+)\.", h)
        if m:
            out.add("§" + m.group(1))
    return out


def tz_sections():
    if not os.path.isfile(os.path.join(root(), TZ)):
        return set()
    out = set()
    for line in read(TZ).splitlines():
        m = re.match(r"^(\d+(?:\.\d+)?)\.\s", line)
        if m:
            out.add(m.group(1))
    return out


def stages():
    if not os.path.isfile(os.path.join(root(), ROADMAP)):
        return set()
    return set(re.findall(r"^\|\s*(\d+)\s*\|", read(ROADMAP), re.M))


def test_index(all_files):
    """Классы тестов и их методы: имя класса → множество методов."""
    idx = {}
    for rel in all_files:
        if not (rel.endswith("Test.kt") and "/src/test/" in rel):
            continue
        text = read(rel)
        for m in re.finditer(r"\bclass\s+(\w+Test)\b", text):
            idx.setdefault(m.group(1), set())
        cls = os.path.basename(rel)[:-3]
        methods = set(re.findall(r"fun\s+`?([\w ]+?)`?\s*\(", text))
        idx.setdefault(cls, set()).update(methods)
    return idx


def resolve_doc(name, by_name):
    if os.path.exists(os.path.join(root(), name)):
        return name
    cands = by_name.get(os.path.basename(name), [])
    return cands[0] if len(cands) == 1 else None


def check():
    f = Findings("ссылки")
    all_files = files()
    by_name = {}
    for p in all_files:
        by_name.setdefault(os.path.basename(p), []).append(p)
    # Номера решений — двух- и трёхзначные (D-100 и дальше).
    decisions = set(re.findall(r"^### D-(\d{2,3})\b", read(DECISIONS), re.M)) if os.path.isfile(
        os.path.join(root(), DECISIONS)) else set()
    threats = set(re.findall(r"^### T(\d{1,2})\b", read(SECURITY), re.M)) if os.path.isfile(
        os.path.join(root(), SECURITY)) else set()
    tz = tz_sections()
    known_stages = stages()
    tests = test_index(all_files)
    heading_cache = {}

    def doc_headings(rel):
        if rel not in heading_cache:
            heading_cache[rel] = headings(rel)
        return heading_cache[rel]

    checked = 0
    for rel in all_files:
        if not is_text(rel) or rel in SKIP:
            continue
        checked += 1
        for no, line in enumerate(read(rel).splitlines(), 1):
            where = f"{rel}:{no}"
            for m in PATH_REF.finditer(line):
                ref = m.group(1).rstrip("/.")
                if any(ch in ref for ch in "*?["):
                    if not any(fnmatch.fnmatch(p, ref) or fnmatch.fnmatch(p, ref + "/*") for p in all_files):
                        f.add(where, f"маска {ref} ни с чем не совпадает")
                elif ref not in ABSENT_BY_DESIGN and ref not in NOT_PATHS and not os.path.exists(os.path.join(root(), ref)):
                    f.add(where, f"нет такого пути: {ref}")
            for m in NAME_REF.finditer(line):
                if m.group(1) not in by_name and not os.path.exists(os.path.join(root(), m.group(1))):
                    f.add(where, f"нет документа {m.group(1)}")
            for m in DECISION_REF.finditer(line):
                if m.group(1) not in decisions:
                    f.add(where, f"решение D-{m.group(1)} не определено в {DECISIONS}")
            for m in SECTION_REF.finditer(line):
                doc = resolve_doc(m.group(1), by_name)
                hs = doc_headings(doc) if doc else None
                if hs is None:
                    f.add(where, f"нет документа {m.group(1)}")
                elif m.group(2) not in hs:
                    f.add(where, f"в {doc} нет раздела «{m.group(2)}»")
            for m in PARA_REF.finditer(line):
                doc = resolve_doc(m.group(1), by_name)
                hs = doc_headings(doc) if doc else None
                if hs is None:
                    f.add(where, f"нет документа {m.group(1)}")
                elif "§" + m.group(2) not in hs:
                    f.add(where, f"в {doc} нет раздела §{m.group(2)}")
            for m in TZ_REF.finditer(line):
                for num in TZ_NUM.findall(m.group(1)):
                    if num not in tz:
                        f.add(where, f"в ТЗ нет раздела §{num}")
            if THREAT_CONTEXT.search(line):
                for m in THREAT.finditer(line):
                    if m.group(1) not in threats:
                        f.add(where, f"угроза T{m.group(1)} не определена в {SECURITY}")
            for m in STAGE.finditer(line):
                if m.group(1) not in known_stages:
                    f.add(where, f"этапа {m.group(1)} нет в плане {ROADMAP}")
            refs = list(TEST_REF.finditer(line)) + (list(AUDIT_REF.finditer(line)) if rel == AUDIT_MAP else [])
            for m in refs:
                cls, method = m.group(1), m.group(2)
                if cls not in tests:
                    f.add(where, f"нет теста {cls}")
                elif method and method not in tests[cls]:
                    f.add(where, f"нет метода {cls}.{method}")
    return f.finish(f"файлов — {checked}, решений — {len(decisions)}, разделов ТЗ — {len(tz)}")


if __name__ == "__main__":
    main(check)
