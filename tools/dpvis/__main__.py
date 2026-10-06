"""Команды DPvisualisation: python3 -m tools.dpvis <команда> (ТЗ §7).

  model check            проверить model/ecosystem.toml
  model build            собрать data/model.json
  collect                собрать срез из клонов → data/state/latest.json
  validate ФАЙЛ…         проверить файлы модели и среза по схемам
  build                  собрать просмотрщик в один файл → dist/dpvisualisation.html
  serve                  живой режим: сервер и пересборка среза по расписанию

Коды возврата: 0 — готово; 1 — находки (проверка не прошла); 2 — неверный
вызов или модель; 3 — сбой.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from . import VERSION, model as modelmod, rules

REPO = Path(__file__).resolve().parents[2]
MODEL_SRC = REPO / "model" / "ecosystem.toml"
MODEL_OUT = REPO / "data" / "model.json"
STATE_OUT = REPO / "data" / "state" / "latest.json"


def _load_model(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _clone_overrides(items: list[str]) -> dict[str, Path]:
    out = {}
    for item in items:
        if "=" not in item:
            raise SystemExit(f"--clone: ожидалось УЗЕЛ=ПУТЬ, а не {item!r}")
        key, path = item.split("=", 1)
        out[key] = Path(path).expanduser().resolve()
    return out


def cmd_model(args) -> int:
    try:
        if args.action == "check":
            src, _ = modelmod.load_source(args.source)
            problems = modelmod.validate(src)
            for p in problems:
                print(f"✗ {p}")
            if problems:
                return 1
            print(f"✓ модель верна: узлов {len(src.get('node', []))}, потоков {len(src.get('flow', []))}")
            return 0
        model = modelmod.build(args.source, args.out)
        print(f"✓ {args.out.relative_to(REPO) if args.out.is_relative_to(REPO) else args.out}: "
              f"узлов {len(model['nodes'])}, потоков {len(model['flows'])}")
        return 0
    except modelmod.ModelError as exc:
        for p in exc.problems:
            print(f"✗ {p}")
        return 1


def cmd_collect(args) -> int:
    from . import collect

    try:
        src, raw = modelmod.load_source(args.model_source)
        model = modelmod.compile_model(src, raw)
    except modelmod.ModelError as exc:
        for p in exc.problems:
            print(f"✗ модель: {p}", file=sys.stderr)
        return 2
    root = args.root.expanduser().resolve() if args.root else None
    if root is not None and not root.is_dir():
        print(f"✗ нет каталога клонов {root}", file=sys.stderr)
        return 2
    now = collect.parse_now(args.now)
    state = collect.collect(model, root, now, days=args.days, online=args.online,
                            fetch=args.fetch, overrides=_clone_overrides(args.clone))
    text = modelmod.dumps(state)
    if args.out == "-":
        sys.stdout.write(text)
    else:
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        tmp = out.with_suffix(out.suffix + ".tmp")
        tmp.write_text(text, encoding="utf-8")
        tmp.replace(out)
        counts: dict[str, int] = {}
        for d in state["diagnostics"]:
            counts[d["code"]] = counts.get(d["code"], 0) + 1
        working = sorted(k for k, v in state["nodes"].items() if v["activity"]["state"] == "working")
        print(f"✓ срез на {state['as_of']} → {out}")
        print(f"  работают: {', '.join(working) or 'никто'}")
        print("  диагностика: " + (", ".join(f"{k} {v}" for k, v in sorted(counts.items())) or "нет"))
    return 0


def cmd_validate(args) -> int:
    from . import formats

    bad = 0
    for path in args.files:
        try:
            data = json.loads(Path(path).read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            print(f"✗ {path}: не читается: {exc}")
            bad += 1
            continue
        problems = formats.validate_document(data)
        for p in problems:
            print(f"✗ {path}: {p}")
        if problems:
            bad += 1
        else:
            print(f"✓ {path}: {data.get('schema')}")
    return 1 if bad else 0


def cmd_build(args) -> int:
    from . import build, public

    try:
        if args.site:
            sums = build.build_site(args.model, args.state, args.site)
            print(f"✓ комплект для сайта (публичная выжимка, D-15) → {args.site}")
            for name, sha in sums.items():
                print(f"  {name}  {sha[:12]}")
            return 0
        out = build.build_bundle(args.model, args.state, args.out, public_only=args.public)
    except build.BuildError as exc:
        print(f"✗ {exc}", file=sys.stderr)
        return 1
    except public.PublicError as exc:
        print("✗ в публичной выжимке осталось закрытое — сборка остановлена:", file=sys.stderr)
        for p in exc.problems:
            print(f"  ✗ {p}", file=sys.stderr)
        return 1
    print(f"✓ {out} ({out.stat().st_size // 1024} КБ){' — публичная выжимка' if args.public else ''}")
    return 0


def cmd_serve(args) -> int:
    from . import serve

    return serve.run(args)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python3 -m tools.dpvis",
                                     description=f"DPvisualisation {VERSION}")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("model", help="проверить или собрать модель")
    p.add_argument("action", choices=["check", "build"])
    p.add_argument("--source", type=Path, default=MODEL_SRC)
    p.add_argument("--out", type=Path, default=MODEL_OUT)
    p.set_defaults(func=cmd_model)

    def collect_args(q):
        q.add_argument("--root", type=Path, help="каталог с клонами репозиториев экосистемы")
        q.add_argument("--clone", action="append", default=[], metavar="УЗЕЛ=ПУТЬ",
                       help="клон узла по явному пути (можно несколько раз)")
        q.add_argument("--days", type=int, default=rules.WINDOW_DAYS, help="окно истории, дней")
        q.add_argument("--online", action="store_true", help="HTTP-пробы сервисов из модели")
        q.add_argument("--fetch", action="store_true", help="git fetch клонов перед сбором")
        q.add_argument("--model-source", type=Path, default=MODEL_SRC)

    p = sub.add_parser("collect", help="собрать срез из клонов")
    collect_args(p)
    p.add_argument("--now", help="время среза, ISO 8601 (по умолчанию — сейчас)")
    p.add_argument("--out", default=str(STATE_OUT), help="файл среза или - для вывода")
    p.set_defaults(func=cmd_collect)

    p = sub.add_parser("validate", help="проверить файлы модели и среза по схемам")
    p.add_argument("files", nargs="+")
    p.set_defaults(func=cmd_validate)

    p = sub.add_parser("build", help="собрать просмотрщик в один файл")
    p.add_argument("--model", type=Path, default=MODEL_OUT)
    p.add_argument("--state", type=Path, default=STATE_OUT)
    p.add_argument("--out", type=Path, default=REPO / "dist" / "dpvisualisation.html")
    p.add_argument("--public", action="store_true", help="только публичная выжимка (D-15)")
    p.add_argument("--site", type=Path, help="комплект для сайта в каталог: dpvis.js, dpvis.css, dpvis-data.json (всегда публичный)")
    p.set_defaults(func=cmd_build)

    p = sub.add_parser("serve", help="живой режим: сервер и пересборка среза")
    collect_args(p)
    p.add_argument("--every", type=float, default=15, help="пересборка раз в N минут (0 — только при старте)")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--state-out", type=Path, default=STATE_OUT)
    p.add_argument("--no-collect", action="store_true", help="не пересобирать срез, только отдавать файлы")
    p.set_defaults(func=cmd_serve)

    args = parser.parse_args(argv)
    if getattr(args, "days", 1) < 1:
        parser.error("--days должно быть не меньше 1")
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
