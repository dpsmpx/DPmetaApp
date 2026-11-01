#!/usr/bin/env python3
"""Сверка ссылок между репозиториями экосистемы dpsmpx.

Читает реестр tools/ecosystem.toml и клоны репозиториев (только чтение) и
строит отчёт в Markdown:

1. связность — сколько файлов каждого репозитория называют другой;
2. ссылки на номера чужих решений и требований (`Galdhold:Р18`,
   `DPlang Р22`, `V-07 DP Verify`) — есть ли такой номер в том репозитории;
3. пути другого репозитория (`DPpass:docs/PROCESS.md`, DP-Verify V-17) —
   есть ли такой файл в клоне;
4. прежние имена (VoxelRPG, `dpsmpx/C-` …) — где ещё встречаются.

Распознаются только строгие формы: имя и номер рядом — через `:` или
пробел. «по решению VoxelRPG (Р22)» не распознаётся: номер в скобках
может быть своим (так в DPlang). Поэтому отчёт — нижняя граница, а не
полный список.

Только стандартная библиотека Python ≥ 3.11; без сети; ничего не пишет,
кроме файла из --out. Коды возврата: 0 — отчёт построен; 1 — у
репозитория из --strict есть несуществующий номер или путь; 2 — неверный
вызов или реестр.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tomllib
from dataclasses import dataclass, field
from pathlib import Path

MAX_BYTES = 2_000_000
# Место проекта в экосистеме (M-10, M-12): управление, ядро, поддерживающий слой,
# слой синхронизации (DPmeta), архив — вне текущей системы.
PLACES = {"control", "core", "support", "sync", "archive"}
SAMPLES = 5


@dataclass
class Repo:
    name: str
    slug: str
    clone: str
    aliases: list[str] = field(default_factory=list)
    names: list[str] = field(default_factory=list)
    decisions: dict | None = None
    defs: list[dict] = field(default_factory=list)
    place: str = ""
    root: Path | None = None
    head: str = ""
    ids: set[str] = field(default_factory=set)
    id_re: str = ""

    @property
    def all_names(self) -> list[str]:
        return [self.name, *self.names, *self.aliases]


def load_registry(path: Path) -> list[Repo]:
    data = tomllib.loads(path.read_text(encoding="utf-8"))
    if data.get("schema") != 1:
        raise ValueError(f"{path}: schema должна быть 1")
    known = {"name", "slug", "clone", "aliases", "names", "decisions", "defs", "place"}
    repos = []
    for entry in data.get("repo", []):
        extra = set(entry) - known
        if extra:
            raise ValueError(f"{path}: неизвестные ключи {sorted(extra)} у {entry.get('name')}")
        if "place" in entry and entry["place"] not in PLACES:
            raise ValueError(f"{path}: place {entry['place']!r} у {entry.get('name')} — "
                             f"одно из {', '.join(sorted(PLACES))} (M-10)")
        repos.append(Repo(**entry))
    names = [n for r in repos for n in r.all_names]
    if len(names) != len(set(names)):
        raise ValueError(f"{path}: одно имя у двух репозиториев")
    return repos


def id_contexts(id_re: str) -> list[re.Pattern]:
    """Где номер определяется, а не упоминается."""
    i = f"({id_re})"
    return [
        re.compile(rf"^#{{1,4}}\s+(?:<a [^>]*></a>)?{i}(?![\w-])"),  # заголовок
        re.compile(rf"^\|\s*{i}\s*\|"),                                # первая ячейка таблицы
        re.compile(rf"\*\*{i}\*\*"),                                    # выделенный номер
    ]


def collect_ids(root: Path, specs: list[dict]) -> set[str]:
    ids: set[str] = set()
    for spec in specs:
        p = root / spec["path"]
        if not p.is_file():
            continue
        pats = id_contexts(spec["id"])
        for line in p.read_text(encoding="utf-8", errors="replace").splitlines():
            for pat in pats:
                ids.update(m.group(1) for m in pat.finditer(line))
    return ids


def find_clone(root: Path, repo: Repo) -> Path:
    """Клон по пути из реестра, иначе — по имени репозитория из slug.

    Сессии кладут клоны по-разному: `galdhold`, `Andors-Love`,
    `dpsmpx/truejointtilemaker`. Ни один вариант не находится — путь из
    реестра (его и покажет отчёт как «нет клона»).
    """
    name = repo.slug.split("/")[-1]
    cands = [repo.clone, name, name.lower(), repo.slug, repo.slug.lower()]
    for c in dict.fromkeys(cands):
        if (root / c / ".git").exists():
            return root / c
    return root / repo.clone


def is_clone(root: Path) -> bool:
    """Клон — рабочее дерево git: каталог со своим `.git` или каталог внутри
    чужого рабочего дерева — подсистема, переехавшая в другой репозиторий
    (DPmeta в `DPmetaApp:dpmeta/`, M-14). Пути и номера читаются от него."""
    if (root / ".git").exists():
        return True
    return root.is_dir() and git(root, "rev-parse", "--is-inside-work-tree").strip() == "true"


def git(root: Path, *args: str) -> str:
    out = subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True)
    return out.stdout if out.returncode == 0 else ""


def text_files(root: Path):
    # Отслеживаемые и новые, но не игнорируемые: рабочее дерево, а не коммит.
    listed = git(root, "ls-files", "-z", "--cached", "--others", "--exclude-standard")
    paths = [p for p in listed.split("\0") if p] if listed else []
    for rel in sorted(paths):
        p = root / rel
        try:
            if not p.is_file() or p.stat().st_size > MAX_BYTES:
                continue
            raw = p.read_bytes()
        except OSError:
            continue
        if b"\0" in raw[:4096]:
            continue
        try:
            yield rel, raw.decode("utf-8")
        except UnicodeDecodeError:
            continue


def name_re(name: str) -> str:
    return rf"(?<![\w-]){re.escape(name)}(?![\w-])"


@dataclass
class Finding:
    src: str
    loc: str
    target: str
    what: str


def scan(repos: list[Repo], sources: list[Repo]):
    """Строки берутся из sources; целями остаются все репозитории с клоном."""
    present = [r for r in repos if r.root is not None]
    mention: dict[tuple[str, str], set[str]] = {}
    refs: list[tuple[Finding, bool]] = []
    paths: list[tuple[Finding, bool]] = []
    stale: dict[tuple[str, str, str], list[str]] = {}

    targets = []
    for t in present:
        nm = "|".join(name_re(n) for n in sorted(t.all_names, key=len, reverse=True))
        id_part = t.id_re or r"(?!x)x"
        targets.append((
            t,
            re.compile(nm),
            re.compile(rf"(?:{nm})(?::|[  ])({id_part})(?![\w-])"),
            re.compile(rf"(?<![\w-])({id_part})[  ](?:{nm})"),
            # В обратных кавычках путь берётся целиком (в имени бывают пробелы);
            # вне их — до первого пробела.
            re.compile(rf"`(?:{nm}):([^`]+)`|(?:{nm}):((?:[\w.-]+/)*[\w.-]+\.\w+|(?:[\w.-]+/)+[\w.-]*)"),
            [(a, re.compile(name_re(a))) for a in t.aliases],
        ))

    for s in [r for r in sources if r.root is not None]:
        # Файл принадлежит самому глубокому клону: подсистема в каталоге другого
        # репозитория (DPmeta в `DPmetaApp:dpmeta/`, M-14) считается только за себя.
        inner = [r.root.relative_to(s.root).as_posix() + "/" for r in present
                 if r.root != s.root and r.root.is_relative_to(s.root)]
        for rel, text in text_files(s.root):
            if any(rel.startswith(d) for d in inner):
                continue
            for no, line in enumerate(text.splitlines(), 1):
                loc = f"{rel}:{no}"
                for t, any_name, fwd, back, path_re, alias_res in targets:
                    if t is s or not any_name.search(line):
                        continue
                    mention.setdefault((s.name, t.name), set()).add(rel)
                    for a, a_re in alias_res:
                        if a_re.search(line):
                            stale.setdefault((s.name, t.name, a), []).append(loc)
                    seen = set()
                    for m in [*fwd.finditer(line), *back.finditer(line)]:
                        rid = m.group(1)
                        if rid in seen:
                            continue
                        seen.add(rid)
                        refs.append((Finding(s.name, loc, t.name, rid), rid in t.ids))
                    for m in path_re.finditer(line):
                        target = (m.group(1) or m.group(2)).rstrip(".,;:)")
                        if not re.search(r"[/.]", target) or (t.id_re and re.fullmatch(t.id_re, target)):
                            continue  # `Galdhold:Р12` — номер, а не путь
                        target = re.sub(r":\d+(?:-\d+)?$", "", target)
                        # `run.sh --quick` — команда с ключами; `docs/audits/**` — маска;
                        # `dpai/…`, `…/core/file_io.h` — сокращение пути, как маска.
                        cands = {target, target.split(" ")[0]}
                        cands = {re.sub(r"/?[*…].*$", "", c) or "." for c in cands}
                        ok = any((t.root / c).exists() for c in cands)
                        paths.append((Finding(s.name, loc, t.name, target), ok))
    return mention, refs, paths, stale


def render(repos: list[Repo], sources: list[Repo], mention, refs, paths, stale) -> str:
    present = [r for r in repos if r.root is not None]
    scanned = [r for r in sources if r.root is not None]
    out = ["# Сверка ссылок между репозиториями", ""]
    out += ["Построено `tools/xref.py`. Числа — нижняя граница: распознаются только",
            "строгие формы ссылок (описание — в начале `tools/xref.py`).", ""]
    out += ["## Клоны", "", "| репозиторий | коммит | номеров определено |", "|---|---|---|"]
    for r in repos:
        if r.root is None:
            out.append(f"| {r.name} | нет клона | — |")
        else:
            out.append(f"| {r.name} | `{r.head or '?'}` | {len(r.ids)} |")
    out += ["", "## Связность", "",
            "Сколько файлов репозитория в строке называют репозиторий в столбце",
            "(любым именем из реестра, включая прежние). Строки — только",
            "просканированные репозитории.", ""]
    short = [r.name if len(r.name) < 12 else r.name[:10] + "…" for r in present]
    out.append("| из \\ в | " + " | ".join(short) + " |")
    out.append("|---" * (len(present) + 1) + "|")
    for s in scanned:
        row = []
        for t in present:
            row.append("·" if s is t else str(len(mention.get((s.name, t.name), ()))) or "0")
        out.append(f"| {s.name} | " + " | ".join(row) + " |")

    bad_refs = [f for f, ok in refs if not ok]
    out += ["", "## Номера чужих решений и требований", "",
            f"Ссылок распознано: {len(refs)}; нет такого номера: {len(bad_refs)}.", ""]
    pairs: dict[tuple[str, str], int] = {}
    for f, _ in refs:
        pairs[(f.src, f.target)] = pairs.get((f.src, f.target), 0) + 1
    if pairs:
        out += ["| откуда | куда | ссылок |", "|---|---|---|"]
        out += [f"| {a} | {b} | {n} |" for (a, b), n in sorted(pairs.items())]
        out.append("")
    if bad_refs:
        out += ["Нет такого номера:", ""]
        out += [f"- {f.src} `{f.loc}` → {f.target} `{f.what}`" for f in sorted(bad_refs, key=lambda f: (f.src, f.loc))]
        out.append("")

    bad_paths = [f for f, ok in paths if not ok]
    out += ["## Пути другого репозитория", "",
            f"Ссылок вида `Проект:путь`: {len(paths)}; пути нет в клоне: {len(bad_paths)}.",
            "Отсутствие пути — не всегда ошибка: срез на дату и запланированное",
            "описывают то, чего нет (так у DP-Verify, V-17 и `planned`).", ""]
    if bad_paths:
        out += [f"- {f.src} `{f.loc}` → {f.target}:`{f.what}`" for f in sorted(bad_paths, key=lambda f: (f.src, f.loc))]
        out.append("")

    out += ["## Прежние имена", "",
            "Где другой репозиторий называет проект прежним именем. Запись о прошлом",
            "под прежним именем — не ошибка (правила — `docs/NAMING.md`).", ""]
    if stale:
        out += ["| откуда | о ком | имя | строк | файлов | примеры |", "|---|---|---|---|---|---|"]
        for (s, t, a), locs in sorted(stale.items()):
            files = sorted({l.rsplit(":", 1)[0] for l in locs})
            ex = ", ".join(f"`{l}`" for l in locs[:SAMPLES])
            out.append(f"| {s} | {t} | `{a}` | {len(locs)} | {len(files)} | {ex} |")
    else:
        out.append("Нет.")
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
    ap.add_argument("--only", action="append", default=[], metavar="ИМЯ",
                    help="сканировать только эти репозитории как источники (номера целей читаются у всех)")
    ap.add_argument("--strict", action="append", default=[], metavar="ИМЯ",
                    help="код 1, если у этого репозитория есть несуществующий номер или путь")
    ap.add_argument("--out", type=Path, help="записать отчёт в файл, а не на экран")
    a = ap.parse_args(argv)

    try:
        repos = load_registry(a.registry)
    except (OSError, ValueError, TypeError, tomllib.TOMLDecodeError) as e:
        print(f"xref: реестр: {e}", file=sys.stderr)
        return 2
    by_name = {r.name: r for r in repos}
    overrides = {}
    for item in a.clone:
        name, sep, path = item.partition("=")
        if not sep or name not in by_name:
            print(f"xref: --clone {item}: нужно ИМЯ=ПУТЬ с именем из реестра", file=sys.stderr)
            return 2
        overrides[name] = Path(path)
    for name in [*a.only, *a.strict]:
        if name not in by_name:
            print(f"xref: нет репозитория {name} в реестре", file=sys.stderr)
            return 2

    for r in repos:
        root = overrides.get(r.name) or find_clone(a.root, r)
        if is_clone(root):
            r.root = root.resolve()
            r.head = git(r.root, "rev-parse", "--short", "HEAD").strip()
            specs = ([r.decisions] if r.decisions else []) + r.defs
            r.ids = collect_ids(r.root, specs)
            r.id_re = "|".join(f"(?:{s['id']})" for s in specs)

    sources = [by_name[n] for n in a.only] if a.only else repos
    mention, refs, paths, stale = scan(repos, sources)
    report = render(repos, sources, mention, refs, paths, stale)
    if a.out:
        a.out.write_text(report, encoding="utf-8")
    else:
        sys.stdout.write(report)

    bad = [f for f, ok in [*refs, *paths] if not ok and f.src in a.strict]
    for f in bad:
        print(f"xref: {f.src} {f.loc}: нет {f.target}:{f.what}", file=sys.stderr)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
