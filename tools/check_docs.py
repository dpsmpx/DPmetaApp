#!/usr/bin/env python3
"""Проверка документов DPmeta: свои ссылки и свои номера.

1. Ссылка Markdown `[…](путь)` ведёт на существующий файл или каталог.
   Пути в обратных кавычках не сверяются: в очереди и картах это пути
   внутри другого репозитория (раздел о нём), и без имени проекта их не
   отличить от путей DPmeta.
2. Номер записи DPmeta (`M-NN`, `C-NN`, `RISK-NN`, `SQ-NN`, `E-NN`) без
   имени проекта определён в своём документе (заголовок, первая ячейка
   таблицы или выделение).
3. В корне — только точки входа.

Ссылки на другие репозитории (`Проект:номер`, `Проект:путь`) сверяет
tools/xref.py. Коды возврата: 0 — чисто, 1 — есть находки.
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# .claude holds project skills (e.g. the periodic documentation audit task).
ROOT_ALLOW = {"README.md", "CLAUDE.md", ".gitignore", "docs", "tools", "tests", ".git", ".claude"}
OWN = {
    "M": "docs/DECISIONS.md",
    "C": "docs/CONTRACTS.md",
    "RISK": "docs/RISKS.md",
    "SQ": "docs/SYNC_QUEUE.md",
    "E": "docs/PRINCIPLES.md",
}
ID_RE = re.compile(r"(?<![\w:-])(M|C|RISK|SQ|E)-(\d{2})(?![\w-])")
LINK_RE = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")


def files() -> list[Path]:
    out = subprocess.run(["git", "-C", str(ROOT), "ls-files", "-z", "--cached", "--others",
                          "--exclude-standard"], capture_output=True, text=True)
    rels = [p for p in out.stdout.split("\0") if p] if out.returncode == 0 else []
    return sorted(ROOT / r for r in rels if r.endswith(".md") and (ROOT / r).is_file())


def defined(path: Path, prefix: str) -> set[str]:
    if not path.is_file():
        return set()
    pats = [re.compile(rf"^#{{1,4}}\s+({prefix}-\d{{2}})\b"),
            re.compile(rf"^\|\s*({prefix}-\d{{2}})\b"),
            re.compile(rf"\*\*({prefix}-\d{{2}})\b")]
    ids = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        for p in pats:
            ids.update(m.group(1) for m in p.finditer(line))
    return ids


def main() -> int:
    bad: list[str] = []
    known = {k: defined(ROOT / v, k) for k, v in OWN.items()}
    for f in files():
        rel = f.relative_to(ROOT)
        in_reports = rel.parts[:2] == ("docs", "reports")
        for no, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            for m in LINK_RE.finditer(line):
                target = m.group(1).split("#", 1)[0]
                if not target or re.match(r"[a-z]+:", target):
                    continue
                if not (f.parent / target).exists():
                    bad.append(f"{rel}:{no}: ссылка на несуществующее {target}")
            if in_reports:
                continue  # срез на дату: номера — как были
            for m in ID_RE.finditer(line):
                rid = f"{m.group(1)}-{m.group(2)}"
                if rid not in known[m.group(1)]:
                    bad.append(f"{rel}:{no}: номер {rid} не определён в {OWN[m.group(1)]}")
    for p in sorted(ROOT.iterdir()):
        if p.name not in ROOT_ALLOW:
            bad.append(f"в корне лишнее: {p.name}")
    for b in bad:
        print(b)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
