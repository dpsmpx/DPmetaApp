#!/usr/bin/env python3
"""Признаки работы в репозиториях экосистемы — по клонам.

Шаг «Кто работает» мета-прохода (docs/PROCESS.md, раздел 2): для каждого
клона из реестра tools/ecosystem.toml —

1. последний коммит основной ветки и его возраст;
2. ветки `origin/*` с невлитой работой: сколько коммитов ветки не имеют
   в основной ветке коммита с тем же изменением (`git cherry`: работа,
   внесённая другим коммитом или после правки, не считается потерянной)
   и когда ветка обновлялась;
3. ветки `dpmeta/*` — влиты или нет: так следующий проход видит, взял ли
   репозиторий правку DPmeta (docs/PROCESS.md, раздел 4).

Итог по репозиторию — «активен», если основная ветка обновлялась за
последние --main-hours часов (по умолчанию 6 — «последние часы») или ветка
с невлитой работой — за --hours (по умолчанию 24 — «меньше суток»).
Открытые PR и сессии ИИ
по клону не видны — их проход смотрит сам; «признаков нет» — не
разрешение править (раздел 2).

Читает только то, что уже скачано в клоны (`git fetch` — шаг 1 прохода):
без сети, ничего не пишет, кроме файла из --out. В неполном клоне
(`--depth`) старая влитая ветка может выглядеть невлитой — отчёт это
помечает. В клоне с одной веткой (`--single-branch`; его же даёт
`git clone --depth` без `--no-single-branch`) других веток нет вовсе —
итог по нему не «признаков нет», а «ветки не видны». Коды возврата:
0 — отчёт построен; 2 — неверный вызов или реестр.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import tomllib
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import xref  # noqa: E402


@dataclass
class Branch:
    name: str
    sha: str
    when: datetime
    ahead: int


@dataclass
class State:
    name: str
    root: Path | None
    base: str = ""
    head: str = ""
    head_when: datetime | None = None
    shallow: bool = False
    single: bool = False  # скачана только основная ветка: других не видно
    within: str = ""  # подкаталог чужого клона (M-14): ветки — того репозитория
    branches: list[Branch] = field(default_factory=list)


def parse_now(text: str | None) -> datetime:
    if not text:
        return datetime.now(timezone.utc)
    when = datetime.fromisoformat(text)
    return when if when.tzinfo else when.replace(tzinfo=timezone.utc)


def default_branch(root: Path) -> str:
    ref = xref.git(root, "symbolic-ref", "--quiet", "refs/remotes/origin/HEAD").strip()
    if ref:
        return ref.removeprefix("refs/remotes/")
    for cand in ("origin/main", "origin/master"):
        if xref.git(root, "rev-parse", "--verify", "--quiet", cand).strip():
            return cand
    return ""


def unmerged(root: Path, base: str, ref: str) -> int:
    """Коммиты ветки, изменения которых нет в основной."""
    out = subprocess.run(["git", "-C", str(root), "cherry", base, ref],
                         capture_output=True, text=True)
    if out.returncode == 0:
        return sum(1 for line in out.stdout.splitlines() if line.startswith("+"))
    return int(xref.git(root, "rev-list", "--count", f"{base}..{ref}").strip() or 0)


def read_state(name: str, root: Path) -> State:
    st = State(name, root)
    # Подсистема в другом репозитории: его ветки и коммиты — не её признаки,
    # они видны в строке того репозитория.
    st.within = xref.git(root, "rev-parse", "--show-prefix").strip().rstrip("/")
    if st.within:
        return st
    st.base = default_branch(root)
    st.shallow = xref.git(root, "rev-parse", "--is-shallow-repository").strip() == "true"
    # `+refs/heads/*:refs/remotes/origin/*` — все ветки; refspec без маски
    # (`--single-branch`, `--depth`) скачивает только одну.
    specs = xref.git(root, "config", "--get-all", "remote.origin.fetch").split()
    st.single = not any(spec.endswith("/*") for spec in specs)
    if not st.base:
        return st
    line = xref.git(root, "log", "-1", "--format=%h %cI", st.base).strip()
    if line:
        sha, when = line.split(" ", 1)
        st.head, st.head_when = sha, datetime.fromisoformat(when)
    refs = xref.git(root, "for-each-ref", "--format=%(refname:short) %(objectname:short) "
                    "%(committerdate:iso-strict)", "refs/remotes/origin")
    for row in refs.splitlines():
        ref, sha, when = row.split(" ", 2)
        if ref in (st.base, "origin/HEAD", "origin"):
            continue
        st.branches.append(Branch(ref.removeprefix("origin/"), sha,
                                  datetime.fromisoformat(when), unmerged(root, st.base, ref)))
    st.branches.sort(key=lambda b: b.when, reverse=True)
    return st


def age(now: datetime, when: datetime | None) -> str:
    if when is None:
        return "—"
    hours = (now - when).total_seconds() / 3600
    if hours < 1:
        return "< 1 ч"
    if hours < 48:
        return f"{hours:.0f} ч"
    return f"{hours / 24:.0f} дн"


def fresh(now: datetime, when: datetime | None, hours: float) -> bool:
    return when is not None and (now - when).total_seconds() <= hours * 3600


def verdict(st: State, now: datetime, hours: float, main_hours: float) -> str:
    if st.root is None:
        return "нет клона"
    if st.within:
        return f"подкаталог `{st.within}/` другого клона — признаки в строке его репозитория"
    signs = []
    if fresh(now, st.head_when, main_hours):
        signs.append(f"коммит в `{st.base.removeprefix('origin/')}`")
    live = [b for b in st.branches if b.ahead and fresh(now, b.when, hours)
            and not b.name.startswith("dpmeta/")]
    if live:
        signs.append(f"невлитые ветки: {len(live)}")
    if signs:
        return "активен — " + ", ".join(signs)
    return "ветки не видны — клон с одной веткой" if st.single else "признаков нет"


def render(states: list[State], now: datetime, hours: float, main_hours: float) -> str:
    out = ["# Признаки работы в репозиториях", "",
           f"Построено `tools/activity.py` на {now.strftime('%Y-%m-%d %H:%M')} UTC по клонам;",
           f"свежее — основная ветка за {main_hours:g} ч, ветка с невлитой работой за {hours:g} ч.",
           "Открытые PR и сессии ИИ по клону не видны — их проход смотрит сам",
           "(`docs/PROCESS.md`, раздел 2).", ""]
    out += ["## Итог", "", "| репозиторий | основная ветка | коммит | возраст | итог |",
            "|---|---|---|---|---|"]
    for st in states:
        if st.root is None or st.within:
            out.append(f"| {st.name} | — | — | — | {verdict(st, now, hours, main_hours)} |")
            continue
        base = st.base.removeprefix("origin/") or "?"
        out.append(f"| {st.name} | `{base}` | `{st.head or '?'}` | "
                   f"{age(now, st.head_when)} | {verdict(st, now, hours, main_hours)} |")

    out += ["", "## Ветки с невлитой работой", "",
            "Изменений этих коммитов нет в основной ветке (`git cherry`); свежие —",
            "обновлены за порог веток.", ""]
    rows = [(st, b) for st in states for b in st.branches
            if b.ahead and not b.name.startswith("dpmeta/")]
    if rows:
        out += ["| репозиторий | ветка | коммит | обновлена | невлитых коммитов |",
                "|---|---|---|---|---|"]
        for st, b in rows:
            mark = " **свежая**" if fresh(now, b.when, hours) else ""
            out.append(f"| {st.name} | `{b.name}` | `{b.sha}` | {age(now, b.when)}{mark} | {b.ahead} |")
    else:
        out.append("Нет.")

    out += ["", "## Ветки DPmeta", "",
            "Правки мета-проходов (`dpmeta/<цель>`, M-04): влита ли ветка в основную.", ""]
    mine = [(st, b) for st in states for b in st.branches if b.name.startswith("dpmeta/")]
    if mine:
        out += ["| репозиторий | ветка | коммит | обновлена | состояние |", "|---|---|---|---|---|"]
        for st, b in mine:
            state = "влита" if not b.ahead else f"не влита ({b.ahead} коммит.)"
            out.append(f"| {st.name} | `{b.name}` | `{b.sha}` | {age(now, b.when)} | {state} |")
    else:
        out.append("Нет.")

    shallow = [st.name for st in states if st.shallow]
    if shallow:
        out += ["", "Неполные клоны: " + ", ".join(shallow) + ". В них старая влитая",
                "ветка может выглядеть невлитой; свежие ветки это не затрагивает."]
    single = [st.name for st in states if st.single]
    if single:
        out += ["", "Клоны с одной веткой: " + ", ".join(single) + ". Других веток в них",
                "нет — невлитая работа и ветки DPmeta не видны. Нужен клон без",
                "`--single-branch` (`git clone --depth N --no-single-branch`) или",
                "`git config remote.origin.fetch '+refs/heads/*:refs/remotes/origin/*'`",
                "и `git fetch`."]
    out.append("")
    return "\n".join(out)


def main(argv: list[str] | None = None) -> int:
    here = Path(__file__).resolve().parent
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--registry", type=Path, default=here / "ecosystem.toml")
    ap.add_argument("--root", type=Path, default=here.parent.parent,
                    help="каталог, в котором лежат клоны (по умолчанию — родитель DPmeta)")
    ap.add_argument("--clone", action="append", default=[], metavar="ИМЯ=ПУТЬ",
                    help="клон репозитория не по пути из реестра")
    ap.add_argument("--hours", type=float, default=24.0,
                    help="порог свежести веток в часах (по умолчанию 24 — «меньше суток»)")
    ap.add_argument("--main-hours", type=float, default=6.0,
                    help="порог свежести основной ветки (по умолчанию 6 — «последние часы»)")
    ap.add_argument("--now", metavar="ВРЕМЯ",
                    help="момент отсчёта в ISO 8601 (по умолчанию — сейчас); для воспроизводимости")
    ap.add_argument("--out", type=Path, help="записать отчёт в файл, а не на экран")
    a = ap.parse_args(argv)

    try:
        repos = xref.load_registry(a.registry)
        now = parse_now(a.now)
    except (OSError, ValueError, TypeError, tomllib.TOMLDecodeError) as e:
        print(f"activity: {e}", file=sys.stderr)
        return 2
    by_name = {r.name: r for r in repos}
    overrides = {}
    for item in a.clone:
        name, sep, path = item.partition("=")
        if not sep or name not in by_name:
            print(f"activity: --clone {item}: нужно ИМЯ=ПУТЬ с именем из реестра", file=sys.stderr)
            return 2
        overrides[name] = Path(path)

    states = []
    for r in repos:
        root = overrides.get(r.name) or xref.find_clone(a.root, r)
        states.append(read_state(r.name, root.resolve()) if xref.is_clone(root)
                      else State(r.name, None))
    report = render(states, now, a.hours, a.main_hours)
    if a.out:
        a.out.write_text(report, encoding="utf-8")
    else:
        sys.stdout.write(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
