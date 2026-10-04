"""Ссылки в документах: пути в `обратных кавычках` и [ссылки](путь) существуют.

Что считается путём: начинается с каталога верхнего уровня репозитория
(`docs/`, `data/`, `tools/` …) или является именем файла с расширением
(`DECISIONS.md`) — тогда ищется рядом с документом, в корне, а затем по
всему репозиторию. Пропускаются шаблоны (`<id>`, `*`, `NNNN`, `…`), ссылки
на файлы закрытых проектов («Galdhold `docs/…`», «dpsmpx/DPlang …») и
внешние адреса. Фигурные скобки раскрываются: `a/{b,c}.md` → два пути.

Аудиты (docs/audits/) — срезы на дату: битая ссылка в них — предупреждение.
"""
from __future__ import annotations

import re
from pathlib import Path

from .common import ROOT, Report, rel, repo_files

EXT = r"(?:md|ya?ml|json|py|js|sh|jpg|jpeg|png|txt|csv|woff2|html)"
BACKTICK = re.compile(r"`([^`\s]+)`")
MDLINK = re.compile(r"\]\(([^)\s]+)\)")
PLACEHOLDER = re.compile(r"[<>*…]|NNNN|YYYY|<id>|\$|\{\}|^~|\.\.\.")
FOREIGN = re.compile(r"(Galdhold|DPlang|dpsmpx/[\w.-]+)\s*$")


def _expand(p: str) -> list[str]:
    m = re.search(r"\{([^{}]+)\}", p)
    if not m:
        return [p]
    return [x for alt in m.group(1).split(",") for x in _expand(p[:m.start()] + alt + p[m.end():])]


def _top_dirs() -> set[str]:
    return {p.name for p in ROOT.iterdir() if p.is_dir() and not p.name.startswith(".git")} | {".claude"}


def candidates(token: str, top: set[str]) -> bool:
    t = token.strip().rstrip(".,;:")
    if "://" in t or t.startswith("#") or t.startswith("mailto:"):
        return False
    first = t.split("/")[0]
    if first in top and "/" in t:
        return True
    return bool(re.fullmatch(rf"[\w.-]+\.{EXT}", t))


def resolve(token: str, doc: Path, names: dict[str, list[Path]]) -> bool:
    t = token.strip().rstrip(".,;:").split("#")[0]
    t = re.sub(r":\d+$", "", t)  # файл:строка
    for cand in (doc.parent / t, ROOT / t):
        if cand.exists():
            return True
    if "/" not in t:
        return t in names
    return False


def check() -> Report:
    r = Report("links — ссылки в документах")
    files = repo_files()
    names: dict[str, list[Path]] = {}
    for f in files:
        names.setdefault(f.name, []).append(f)
    # Каталоги тоже бывают целью ссылки (`docs/research/`).
    for f in files:
        for parent in f.relative_to(ROOT).parents:
            if str(parent) != ".":
                names.setdefault(parent.name, []).append(ROOT / parent)
    top = _top_dirs()
    count = 0
    for doc in files:
        if doc.suffix != ".md":
            continue
        audit = rel(doc).startswith("docs/audits/")
        for n, line in enumerate(doc.read_text(encoding="utf-8").splitlines(), 1):
            hits = [(m.start(), m.group(1)) for m in BACKTICK.finditer(line)]
            hits += [(m.start(), m.group(1)) for m in MDLINK.finditer(line)]
            for pos, raw in hits:
                if FOREIGN.search(line[:pos]):
                    continue
                for tok in _expand(raw):
                    if PLACEHOLDER.search(tok) or not candidates(tok, top):
                        continue
                    count += 1
                    if not resolve(tok, doc, names):
                        msg = f"{rel(doc)}:{n}: {tok} — нет такого файла"
                        (r.warn if audit else r.error)(msg)
    r.note(f"ссылок проверено {count}")
    return r
