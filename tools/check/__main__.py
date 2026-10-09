"""Проверки репозитория: python3 -m tools.check [static|full] [--write-status] [--only ID…].

Обычно вызывается через tools/check/run.sh. Уровни (D-09):

  static   правила репозитория — секунды; после любой правки
  full     static + тесты Python и просмотрщика, сборка, дым в Chromium;
           перед вливанием, итог — в разделе «Проверки» описания PR

--write-status   переписать блок состояния README.md из ROADMAP/ROADMAP.md

Коды возврата: 0 — всё прошло; 1 — находки; 2 — неверный вызов; 3 — сбой
окружения (нет нужного инструмента — проверка не запускалась).
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

from . import checks, slow

REPO = Path(__file__).resolve().parents[2]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="tools/check/run.sh", description="Проверки DPvisualisation")
    parser.add_argument("level", nargs="?", default="static", choices=["static", "full"])
    parser.add_argument("--write-status", action="store_true", help="переписать блок состояния README.md")
    parser.add_argument("--only", nargs="+", metavar="ID", help="только эти проверки")
    parser.add_argument("--root", type=Path, default=REPO, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    root = args.root.resolve()

    if args.write_status:
        (root / checks.README).write_text(checks.render_readme(root), encoding="utf-8")
        print(f"✓ {checks.README}: блок состояния переписан")

    suite = list(checks.STATIC)
    if args.level == "full":
        suite += list(slow.FULL)
    if args.only:
        unknown = set(args.only) - {cid for cid, _ in suite}
        if unknown:
            parser.error(f"нет проверок {', '.join(sorted(unknown))} на уровне {args.level}")
        suite = [(cid, fn) for cid, fn in suite if cid in args.only]

    failed, broken = 0, 0
    started = time.monotonic()
    for cid, fn in suite:
        t0 = time.monotonic()
        try:
            result = fn(root)
        except Exception as exc:  # сбой самой проверки — не находка в коде
            print(f"! {cid}: проверка упала: {type(exc).__name__}: {exc}")
            broken += 1
            continue
        problems, note = result if isinstance(result, tuple) else (result, "")
        took = f"{time.monotonic() - t0:.1f} с"
        if problems is None:
            print(f"! {cid}: не запускалась — {note}")
            broken += 1
        elif problems:
            failed += 1
            print(f"✗ {cid} ({took})")
            for p in problems:
                print("    " + p.replace("\n", "\n    "))
        else:
            print(f"✓ {cid}{' — ' + note if note else ''} ({took})")
    total = f"{time.monotonic() - started:.1f} с"
    if broken:
        print(f"Итог {args.level}: не запускалось {broken}, с находками {failed} — {total}")
        return 3
    if failed:
        print(f"Итог {args.level}: с находками {failed} из {len(suite)} — {total}")
        return 1
    print(f"Итог {args.level}: всё прошло ({len(suite)}) — {total}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
