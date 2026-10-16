#!/usr/bin/env python3
"""Прогресс проекта в README.md — из документов, а не по памяти.

Раздел README между метками `<!-- progress:begin -->` и
`<!-- progress:end -->` строится из того, что уже записано:

  * этапы — таблица `docs/direction/ROADMAP/README.md` (суть и состояние)
    и «Критерии выхода» в файле каждого этапа (`- [x]` выполнен,
    `- [ ]` нет);
  * решения — `docs/direction/DECISIONS.md` (принятые, заменённые,
    отложенные вопросы);
  * задачи и долги — открытые пункты `TODO.md` по разделам;
  * аудиты — `docs/audits/` с датами.

Заодно сверяется, что документы не противоречат друг другу: состояние
этапа в таблице плана и в файле этапа одно и то же; этап «выполнен» —
только когда все его критерии выхода отмечены; каждый критерий — с
отметкой. Иначе прогресс не пишется: README не должен показывать то,
чего документы не подтверждают.

    python3 tools/hostcheck/readme_progress.py          обновить README.md
    python3 tools/hostcheck/readme_progress.py --check  только проверить (шаг hostcheck)

Код возврата: 0 — README актуален (или обновлён); 1 — README устарел
(в --check) или документы противоречат друг другу; 2 — нет файла,
таблицы или меток.
"""
import difflib
import glob
import os
import re
import sys

PROJ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
README = os.path.join(PROJ, "README.md")
ROADMAP_DIR = os.path.join(PROJ, "docs", "direction", "ROADMAP")
DECISIONS = os.path.join(PROJ, "docs", "direction", "DECISIONS.md")
TODO = os.path.join(PROJ, "TODO.md")
AUDITS = os.path.join(PROJ, "docs", "audits")
BEGIN, END = "<!-- progress:begin -->", "<!-- progress:end -->"
DONE = "выполнен"


class Broken(Exception):
    """Нет нужного файла, таблицы или меток — код возврата 2."""


def read(path):
    if not os.path.isfile(path):
        raise Broken(f"нет {os.path.relpath(path, PROJ)}")
    with open(path, encoding="utf-8") as f:
        return f.read()


def section(text, title):
    """Строки раздела `## title` до следующего заголовка того же уровня."""
    out, inside = [], False
    for line in text.splitlines():
        if line.startswith("## "):
            inside = line[3:].strip() == title
            continue
        if inside:
            out.append(line)
    return out


def norm_state(s):
    """«**выполнен** (4 октября 2026).» и «выполнен (4 октября 2026)» — одно состояние."""
    return re.sub(r"\s+", " ", s.replace("*", "")).strip().rstrip(".").strip()


def stages(problems):
    plan = read(os.path.join(ROADMAP_DIR, "README.md"))
    rows = [m.groups() for m in re.finditer(r"^\|\s*(\d+)\s*\|([^|\n]+)\|([^|\n]+)\|\s*$", plan, re.M)]
    if not rows:
        raise Broken("в docs/direction/ROADMAP/README.md нет таблицы этапов «| Этап | Суть | Состояние |»")
    out = []
    for num, essence, state in rows:
        files = glob.glob(os.path.join(ROADMAP_DIR, f"{int(num):02d}-*.md"))
        if len(files) != 1:
            raise Broken(f"этап {num}: нужен ровно один файл docs/direction/ROADMAP/{int(num):02d}-*.md")
        rel = os.path.relpath(files[0], PROJ)
        text = read(files[0])
        m = re.search(r"^Состояние:\s*(.+)$", text, re.M)
        file_state = norm_state(m.group(1)) if m else None
        state = norm_state(state)
        if file_state != state:
            problems.append(f"этап {num}: состояние в плане «{state}», в {rel} — «{file_state}»")
        criteria = [line for line in section(text, "Критерии выхода") if line.startswith("- ")]
        done = sum(1 for c in criteria if c.startswith("- [x] "))
        for c in criteria:
            if not re.match(r"^- \[( |x)\] ", c):
                problems.append(f"{rel}: критерий выхода без отметки [ ] или [x]: «{c[2:50]}…»")
        if not criteria:
            problems.append(f"{rel}: нет раздела «Критерии выхода» с пунктами")
        if state.startswith(DONE) and done != len(criteria):
            problems.append(f"этап {num} «{state}», но критериев выхода выполнено {done} из {len(criteria)} ({rel})")
        if not state.startswith(DONE) and criteria and done == len(criteria):
            problems.append(f"этап {num}: все критерии выхода выполнены, а состояние — «{state}» ({rel})")
        out.append((int(num), essence.strip(), state, done, len(criteria)))
    return out


def decisions():
    text = read(DECISIONS)
    accepted = re.split(r"^### (D-\d{2})\b", "\n".join(section(text, "Принятые")), flags=re.M)
    total = (len(accepted) - 1) // 2
    replaced = sum(1 for body in accepted[2::2] if re.search(r"\*\*Состояние\.\*\*[^\n]*заменено", body))
    deferred = [line for line in section(text, "Отложено") if line.startswith("|") and not line.startswith("|---")]
    return total, replaced, max(len(deferred) - 1, 0)  # минус строка заголовка таблицы


def todo():
    text = read(TODO)
    out, current = [], None
    for line in text.splitlines():
        if line.startswith("## "):
            current = [line[3:].strip(), 0]
            out.append(current)
        elif current is not None and line.startswith("- [ ] "):
            current[1] += 1
    return [(name, n) for name, n in out]


def audits():
    out = []
    for path in sorted(glob.glob(os.path.join(AUDITS, "*.md"))):
        m = re.search(r"^Дата:\s*\*\*(.+?)\*\*", read(path), re.M)
        out.append((os.path.relpath(path, PROJ), m.group(1) if m else "без даты"))
    return out


def lower_first(s):
    return s[:1].lower() + s[1:]


def render(stage_rows, dec, todos, audit_list):
    done_stages = [s for s in stage_rows if s[2].startswith(DONE)]
    nxt = next((s for s in stage_rows if not s[2].startswith(DONE)), None)
    lines = [
        BEGIN,
        "<!-- Строит tools/hostcheck/readme_progress.py из docs/direction/ROADMAP/, docs/direction/DECISIONS.md,",
        "     TODO.md и docs/audits/ — руками не править: правится источник, потом запускается скрипт. -->",
        "",
        f"Выполнено этапов: **{len(done_stages)} из {len(stage_rows)}**"
        + (f"; следующий — этап {nxt[0]}, «{nxt[1]}»." if nxt else "."),
        "",
        "| Этап | Суть | Состояние | Критерии выхода |",
        "|---|---|---|---|",
    ]
    for num, essence, state, done, total in stage_rows:
        mark = f"**{state}**" if state.startswith(DONE) else state
        lines.append(f"| {num} | {essence} | {mark} | {done} из {total} |")
    total, replaced, deferred = dec
    open_total = sum(n for _, n in todos)
    by_section = ", ".join(f"{lower_first(name)} — {n}" for name, n in todos if n)
    lines += [
        "",
        f"- **Решения** (`docs/direction/DECISIONS.md`): принято {total}"
        + (f", из них частично или полностью заменено {replaced}" if replaced else "")
        + f"; отложенных вопросов — {deferred}.",
        f"- **Задачи и долги** (`TODO.md`): открыто {open_total}" + (f": {by_section}." if by_section else "."),
    ]
    if audit_list:
        lines.append("- **Аудиты:** " + "; ".join(f"`{p}` ({d})" for p, d in audit_list) + ".")
    lines.append(END)
    return "\n".join(lines)


def main():
    check = "--check" in sys.argv[1:]
    problems = []
    try:
        block = render(stages(problems), decisions(), todo(), audits())
        readme = read(README)
    except Broken as e:
        print(f"✗ README: {e}")
        return 2
    if readme.count(BEGIN) != 1 or readme.count(END) != 1 or readme.index(BEGIN) > readme.index(END):
        print(f"✗ README: нужны ровно одна метка {BEGIN} и одна {END} (после неё)")
        return 2
    if problems:
        print("✗ README: документы о прогрессе противоречат друг другу — прогресс не пишется:")
        for p in problems:
            print("    " + p)
        return 1
    start, end = readme.index(BEGIN), readme.index(END) + len(END)
    updated = readme[:start] + block + readme[end:]
    if updated == readme:
        print("✓ README: прогресс совпадает с документами (этапы, критерии выхода, решения, TODO, аудиты)")
        return 0
    if check:
        print("✗ README: прогресс устарел — обновить: python3 tools/hostcheck/readme_progress.py")
        diff = difflib.unified_diff(readme.splitlines(), updated.splitlines(), "README.md", "ожидается", lineterm="", n=0)
        for line in list(diff)[2:22]:
            print("    " + line)
        return 1
    with open(README, "w", encoding="utf-8") as f:
        f.write(updated)
    print("✓ README: прогресс обновлён из документов")
    return 0


if __name__ == "__main__":
    sys.exit(main())
