"""Новый объект контента в состоянии IDEA (CONTENT_MODEL.md, §5)."""
from __future__ import annotations

import re

from .common import ITEMS_DIR, dump_yaml, load_items, rel, today
from .schema import schema

HEADER = "# Объект контента (docs/content/CONTENT_MODEL.md). Схема — schemas/content_item.schema.json.\n"


def next_id(items_dir=None) -> str:
    if items_dir is None:
        ids = [i.id for i in load_items()]
    else:
        ids = [d.name[:7] for d in items_dir.iterdir() if d.is_dir()] if items_dir.is_dir() else []
    nums = [int(i.split("-")[1]) for i in ids if re.fullmatch(r"CI-\d{4}", i)]
    return f"CI-{max(nums, default=0) + 1:04d}"


def create(type_: str, pillar: str, slug: str, objective: str, title: str | None = None, items_dir=None) -> str:
    props = schema("content_item")["properties"]
    if type_ not in props["type"]["enum"]:
        raise SystemExit(f"type {type_}: допустимо {props['type']['enum']}")
    if pillar not in props["pillar"]["enum"]:
        raise SystemExit(f"pillar {pillar}: допустимо {props['pillar']['enum']}")
    if not re.fullmatch(props["slug"]["pattern"], slug):
        raise SystemExit(f"slug {slug}: только a-z, 0-9 и дефис")
    if len(objective) < 10:
        raise SystemExit("objective: одна фраза — что должен понять или сделать зритель (Д1)")
    iid = next_id(items_dir)
    d = (items_dir or ITEMS_DIR) / f"{iid}-{slug}"
    if d.exists():
        raise SystemExit(f"{rel(d)} уже существует")
    for sub in ("spec", "renders", "exports", "copy"):
        (d / sub).mkdir(parents=True)
    now = today()
    item = {
        "schema": "content_item/v1",
        "id": iid,
        "slug": slug,
        "title": title or slug.replace("-", " "),
        "type": type_,
        "pillar": pillar,
        "objective": objective,
        "created": now,
        "provenance": "provenance.yaml",
        "state": "IDEA",
        "history": [{"date": now, "state": "IDEA", "note": f"объект заведён: {objective}"}],
    }
    dump_yaml(item, d / "item.yaml", header=HEADER)
    dump_yaml({"schema": "item_provenance/v1", "item": iid, "outputs": []}, d / "provenance.yaml")
    for sub in ("spec", "renders", "exports", "copy"):
        (d / sub / ".gitkeep").touch()
    return iid
