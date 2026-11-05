#!/usr/bin/env python3
"""Раздел «Прогресс» в README.md — из плана, решений и TODO.md.

README не источник истины (`DPmeta:docs/PRINCIPLES.md`, E-13): раздел между
метками `progress:begin` и `progress:end` строит этот скрипт, руками его не
правят. Источники:

  * таблица этапов docs/direction/ROADMAP/README.md и файлы этапов —
    состояние и критерии выхода (`- [x]` / `- [ ]`);
  * docs/direction/DECISIONS.md — принятые, заменённые, отложенные;
  * TODO.md — открытые пункты по разделам.

Заодно ловятся противоречия: разное состояние этапа в плане и в файле
этапа; этап «выполнен» при открытых критериях.

    python3 tools/verify/readme_progress.py           # переписать раздел
    python3 tools/verify/readme_progress.py --check   # только сверить

Код возврата: 0 — раздел актуален (или переписан); 1 — отстал или противоречия.
"""
import os
import re
import sys

from common import DECISIONS, ROADMAP, ROADMAP_DIR, Findings, read, root

README = "README.md"
TODO = "TODO.md"
BEGIN = "<!-- progress:begin -->"
END = "<!-- progress:end -->"


def stage_rows():
    rows = []
    for line in read(ROADMAP).splitlines():
        m = re.match(r"^\|\s*(\d+)\s*\|\s*(.+?)\s*\|\s*(.+?)\s*\|\s*$", line)
        if m:
            rows.append((int(m.group(1)), m.group(2), m.group(3)))
    files = {}
    for line in read(ROADMAP).splitlines():
        m = re.match(r"^\|\s*(\d+)\s*\|\s*`([\w.-]+\.md)`\s*\|\s*$", line)
        if m:
            files[int(m.group(1))] = ROADMAP_DIR + "/" + m.group(2)
    # Первая таблица — этапы с состоянием; вторая — файлы этапов.
    seen, stages = set(), []
    for no, summary, state in rows:
        if no in seen or summary.startswith("`"):
            continue
        seen.add(no)
        stages.append((no, summary, state))
    return stages, files


def norm_state(s):
    s = s.strip().strip("*").strip()
    return s.rstrip(".").strip("*").strip()


def criteria(rel):
    text = read(rel)
    done = len(re.findall(r"^\s*- \[x\]", text, re.M))
    open_ = len(re.findall(r"^\s*- \[ \]", text, re.M))
    m = re.search(r"^Состояние:\s*(.+?)\s*$", text, re.M)
    return done, open_, norm_state(m.group(1)) if m else None


def decisions_summary():
    text = read(DECISIONS)
    accepted = re.findall(r"^### (D-\d{2,3})\b", text, re.M)
    replaced = len(re.findall(r"^\*\*Состояние\.\*\*.*заменено", text, re.M))
    deferred = 0
    if "## Отложено" in text:
        tail = text.split("## Отложено", 1)[1]
        deferred = len([l for l in tail.splitlines()
                        if l.startswith("|") and not l.startswith("|---") and not l.startswith("| Вопрос")])
    return len(accepted), replaced, deferred


def todo_summary():
    sections, current = [], None
    for line in read(TODO).splitlines():
        if line.startswith("## "):
            current = [line[3:].strip(), 0]
            sections.append(current)
        elif current and re.match(r"^\s*- \[ \]", line):
            current[1] += 1
    return [(n, c) for n, c in sections if c]


def build(f):
    stages, files = stage_rows()
    lines = []
    done_count = 0
    working = []
    table = ["| Этап | Суть | Состояние | Критерии выхода |", "|---|---|---|---|"]
    for no, summary, state in stages:
        rel = files.get(no)
        if not rel or not os.path.isfile(os.path.join(root(), rel)):
            f.add(ROADMAP, f"нет файла этапа {no}")
            continue
        done, open_, file_state = criteria(rel)
        plan_state = norm_state(state)
        if file_state is None:
            f.add(rel, "нет строки «Состояние:»")
        elif file_state != plan_state:
            f.add(rel, f"состояние «{file_state}» расходится с планом «{plan_state}»")
        finished = plan_state.startswith("выполнен")
        if finished and open_:
            f.add(rel, f"этап выполнен, но открыто критериев: {open_}")
        if finished:
            done_count += 1
        elif plan_state == "в работе":
            working.append(str(no))
        shown = f"**{plan_state}**" if finished else plan_state
        table.append(f"| {no} | {summary} | {shown} | {done} из {done + open_} |")
    head = f"Выполнено этапов: **{done_count} из {len(stages)}**"
    if working:
        head += "; в работе — " + ("этап " if len(working) == 1 else "этапы ") + ", ".join(working)
    lines.append(head + ".")
    lines.append("")
    lines.extend(table)
    lines.append("")
    accepted, replaced, deferred = decisions_summary()
    lines.append(f"- **Решения** (`{DECISIONS}`): принято {accepted}, из них заменено {replaced}; "
                 f"отложенных вопросов — {deferred}.")
    todo = todo_summary()
    total = sum(c for _, c in todo)
    parts = ", ".join(f"{n.lower()} — {c}" for n, c in todo)
    lines.append(f"- **Задачи и долги** (`{TODO}`): открыто {total}" + (f": {parts}." if parts else "."))
    note = ("<!-- Строит tools/verify/readme_progress.py из docs/direction/ROADMAP/, "
            "docs/direction/DECISIONS.md и TODO.md —\n     руками не править: правится источник, "
            "потом запускается скрипт. -->")
    return BEGIN + "\n" + note + "\n\n" + "\n".join(lines) + "\n" + END


def check():
    check_only = "--check" in sys.argv[1:]
    f = Findings("README")
    block = build(f)
    text = read(README)
    if BEGIN not in text or END not in text:
        f.add(README, "нет меток раздела «Прогресс»")
        return f.finish("")
    current = text[text.index(BEGIN):text.index(END) + len(END)]
    if current != block:
        if check_only:
            f.add(README, "раздел «Прогресс» отстал: python3 tools/verify/readme_progress.py")
        else:
            with open(os.path.join(root(), README), "w", encoding="utf-8", newline="\n") as fh:
                fh.write(text.replace(current, block))
            print("README.md: раздел «Прогресс» переписан")
    return f.finish("раздел «Прогресс» актуален")


if __name__ == "__main__":
    sys.exit(check())
