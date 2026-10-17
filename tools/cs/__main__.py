"""Командная строка: python3 -m tools.cs <команда>.

  check [имя …]          проверки (по умолчанию все); код 1 — есть ошибки
  status                 объекты контента: состояние, выбор, гейты, блокеры
  render ITEM            рендер вариантов для критики (renders/)
  export ITEM --variant  экспорт варианта по форматам площадок (exports/)
  new                    новый объект в состоянии IDEA
  metrics import         импорт выгрузки CSV площадки (Д15)
  …и команды платформы публикации — tools/cs/platform_cli.py (publish, approve, preview, site…)
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from . import capabilities, claims, exports, facts, links, metrics, newitem, platform_checks, platform_cli, provenance, secrets, validate, visual
from .common import Item, dump_yaml, find_item, load_yaml, rel, sha256_file
from .model import effective_state, reached
from .progress import check as progress_check
from .site import shots as site_shots

CHECKS = {
    "schemas": validate.schemas_check,
    "items": validate.items_check,
    "facts": facts.check,
    "claims": claims.check,
    "provenance": provenance.check,
    "visual": visual.check,
    "exports": exports.check,
    "metrics": metrics.check,
    "links": links.check,
    "secrets": secrets.check,
    "canonical": platform_checks.canonical_check,
    "publications": platform_checks.publications_check,
    "destinations": platform_checks.destinations_check,
    "campaigns": platform_checks.campaigns_check,
    "projects": platform_checks.projects_check,
    "capabilities": capabilities.check,
    "progress": progress_check.check,
    "site": platform_checks.site_check,
    "siteshots": site_shots.check,
}
REVIEW_FORMAT = "telegram_post"  # формат рендеров для критики: самый частый 16:9


def cmd_check(args) -> int:
    names = args.names or list(CHECKS)
    bad = [n for n in names if n not in CHECKS]
    if bad:
        print(f"нет проверок: {', '.join(bad)}; есть: {', '.join(CHECKS)}", file=sys.stderr)
        return 2
    failed = []
    for n in names:
        rep = CHECKS[n]()
        rep.print()
        if not rep.ok:
            failed.append(n)
    if failed:
        print(f"\n✗ ошибки в проверках: {', '.join(failed)}")
        return 1
    return 0


def cmd_status(args) -> int:
    rows = validate.status_rows()
    if not rows:
        print("объектов нет (content/items/)")
    for r in rows:
        print(f"{r['id']}  {r['state']:<12} {r['title']}")
        print(f"         вариант: {r['selected']}  гейты: {r['gates']}  ждёт: {r['blocked_by']}")
    return 0


def _save_item(item: Item) -> None:
    path = item.dir / "item.yaml"
    head = "".join(l for l in path.read_text(encoding="utf-8").splitlines(True) if l.startswith("#"))
    dump_yaml(item.data, path, header=head.split("\n\n")[0] if head else "")


def _langs(item: Item, platform: str) -> list[str]:
    spec_langs = load_yaml("data/platforms/export_specs.yaml")["platform_languages"]
    item_langs = item.data.get("languages") or ["ru"]
    return [l for l in spec_langs.get(platform, item_langs) if l in item_langs]


def cmd_render(args) -> int:
    from . import render
    item = find_item(args.item)
    vids = args.variant or [v["id"] for v in item.data.get("variants", [])]
    langs = args.lang or item.data.get("languages") or ["ru"]
    fmts = args.format or [REVIEW_FORMAT]
    for vid in vids:
        v = item.variant(vid)
        if v is None:
            raise SystemExit(f"{item.id}: нет варианта {vid}")
        files = set(v.get("renders", []))
        for lang in langs:
            for f in fmts:
                out = render.produce(item, vid, lang, f, "render")
                files.add(str(out.relative_to(item.dir)))
                print(f"  {rel(out)}")
        v["renders"] = sorted(files)
    _save_item(item)
    return 0


def cmd_export(args) -> int:
    from . import render
    item = find_item(args.item)
    hs = [h["state"] for h in item.data.get("history", [])]
    state = effective_state(item.data["state"], hs)
    if args.final and not reached(state, "APPROVED"):
        raise SystemExit(f"{item.id}: финальный экспорт только после APPROVED (сейчас {state})")
    if not reached(state, "UNDER_REVIEW"):
        raise SystemExit(f"{item.id}: экспорт — после критики (сейчас {state})")
    specs = exports.specs()
    platforms = set(item.data.get("platforms", [])) | {"internal"}
    names = args.format or [n for n, f in specs.items() if f["platform"] in platforms]
    entries = {e["file"]: e for e in item.data.get("exports", [])}
    for n in names:
        for lang in _langs(item, specs[n]["platform"]):
            out = render.produce(item, args.variant, lang, n, "export")
            f = str(out.relative_to(item.dir))
            entries[f] = {"format": n, "file": f, "lang": lang, "variant": args.variant,
                          "sha256": sha256_file(out), "status": "final" if args.final else "preview"}
            print(f"  {rel(out)}")
    item.data["exports"] = sorted(entries.values(), key=lambda e: e["file"])
    _save_item(item)
    return 0


def cmd_new(args) -> int:
    iid = newitem.create(args.type, args.pillar, args.slug, args.objective, args.title)
    print(f"создан {iid}: content/items/{iid}-{args.slug}/item.yaml (IDEA)")
    return 0


def cmd_metrics(args) -> int:
    metrics.import_csv(args.item, args.platform, Path(args.file), args.period_from, args.period_to,
                       args.variant, args.url)
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python3 -m tools.cs", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("check", help="проверки")
    p.add_argument("names", nargs="*")
    p.set_defaults(fn=cmd_check)
    p = sub.add_parser("status", help="объекты и состояния")
    p.set_defaults(fn=cmd_status)
    p = sub.add_parser("render", help="рендер вариантов для критики")
    p.add_argument("item")
    p.add_argument("--variant", nargs="+")
    p.add_argument("--lang", nargs="+", choices=["ru", "en"])
    p.add_argument("--format", nargs="+")
    p.set_defaults(fn=cmd_render)
    p = sub.add_parser("export", help="экспорт варианта по форматам")
    p.add_argument("item")
    p.add_argument("--variant", required=True)
    p.add_argument("--format", nargs="+")
    p.add_argument("--final", action="store_true", help="после APPROVED")
    p.set_defaults(fn=cmd_export)
    p = sub.add_parser("new", help="новый объект (IDEA)")
    p.add_argument("--type", required=True)
    p.add_argument("--pillar", required=True)
    p.add_argument("--slug", required=True)
    p.add_argument("--objective", required=True)
    p.add_argument("--title")
    p.set_defaults(fn=cmd_new)
    p = sub.add_parser("metrics", help="метрики")
    msub = p.add_subparsers(dest="mcmd", required=True)
    m = msub.add_parser("import", help="импорт CSV")
    m.add_argument("--item", required=True)
    m.add_argument("--platform", required=True)
    m.add_argument("--file", required=True)
    m.add_argument("--from", dest="period_from", required=True)
    m.add_argument("--to", dest="period_to", required=True)
    m.add_argument("--variant")
    m.add_argument("--url")
    m.set_defaults(fn=cmd_metrics)
    platform_cli.register(sub)
    args = ap.parse_args(argv)
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
