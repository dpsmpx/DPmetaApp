"""Явные миграции схемы объектов (Д31, PLATFORM_VISION §111–112).

content_item/v1 → v2:
  - schema: content_item/v2;
  - copy/<площадка>.md → variants/<площадка>.<язык>.md с заголовком variant/v1
    (язык — по platform_languages, медиа — экспорт выбранного варианта для
    площадки, формат — по наличию медиа); служебные комментарии <!-- … -->
    переносятся в заголовок (note); пустые тексты не переносятся;
  - запись в history (то же состояние) — что перенесено.

Без --apply только показывает план. Ничего не удаляет молча: copy/
удаляется только после записи всех вариантов, и это видно в плане и в Git.
"""
from __future__ import annotations

import re
import shutil

import yaml

from ..common import today
from . import textdoc
from .content import CItem, save_item

FORMAT_BY_PLATFORM = {"github": "readme", "youtube": "description", "website": "page"}


def _lang(item: CItem, platform: str) -> str:
    langs = (item.repo.yaml("data/platforms/export_specs.yaml") or {}).get("platform_languages", {})
    have = item.data.get("languages") or ["ru"]
    for lg in langs.get(platform, have):
        if lg in have:
            return lg
    return have[0]


def _media(item: CItem, platform: str, lang: str) -> list[str]:
    specs = (item.repo.yaml("data/platforms/export_specs.yaml") or {}).get("formats", {})
    sel = item.data.get("selected_variant")
    out = []
    for e in item.data.get("exports", []):
        if e.get("variant") == sel and e.get("lang") == lang and (specs.get(e["format"]) or {}).get("platform") == platform:
            out.append(e["file"])
    return out[:1]


def plan_v1_to_v2(item: CItem) -> list[dict]:
    if item.schema_version != 1:
        return []
    acts = []
    for p in sorted((item.dir / "copy").glob("*.md")) if (item.dir / "copy").is_dir() else []:
        raw = p.read_text(encoding="utf-8")
        notes = " ".join(" ".join(m.split()) for m in re.findall(r"<!--(.*?)-->", raw, re.S))
        body = re.sub(r"<!--.*?-->", "", raw, flags=re.S).strip()
        platform = p.stem
        lang = _lang(item, platform)
        media = _media(item, platform, lang)
        fmt = FORMAT_BY_PLATFORM.get(platform) or ("photo_post" if media and platform == "telegram" else "post")
        acts.append({"from": f"copy/{p.name}", "to": f"variants/{platform}.{lang}.md", "platform": platform,
                     "lang": lang, "format": fmt, "media": media, "note": notes, "body": body, "skip": not body})
    return acts


def apply_v1_to_v2(item: CItem) -> list[str]:
    acts = plan_v1_to_v2(item)
    done, skipped = [], []
    for a in acts:
        if a["skip"]:
            skipped.append(a["from"])
            continue
        meta = {"schema": "variant/v1", "item": item.id, "platform": a["platform"], "lang": a["lang"],
                "format": a["format"], "source_version": None, "version": 1, "status": "draft",
                "trust": "unverified", "media": a["media"], "link": None,
                "generation": {"provider": "session", "note": "перенесено из copy/ миграцией v1→v2"},
                "note": a["note"] or None}
        fm = re.search(r"facts:\s*([A-Z0-9, -]+)", a["note"] or "")
        if fm:
            meta["claims"] = [x.strip() for x in fm.group(1).split(",") if x.strip()]
        meta = {k: v for k, v in meta.items() if v not in (None, [])} | {"source_version": None, "link": None}
        textdoc.save(textdoc.TextDoc(meta, a["body"]), item.dir / a["to"])
        done.append(f"{a['from']} → {a['to']}")
    if (item.dir / "copy").is_dir():
        shutil.rmtree(item.dir / "copy")
    item.data["schema"] = "content_item/v2"
    state = item.data.get("state")
    note = "миграция content_item/v1 → v2 (Д31): " + "; ".join(done)
    if skipped:
        note += f"; без текста, не перенесено: {', '.join(skipped)}"
    item.data.setdefault("history", []).append({"date": today(), "state": state, "note": note})
    save_item(item)
    return done + [f"пропущено (пусто): {s}" for s in skipped]


def describe(acts: list[dict]) -> str:
    lines = []
    for a in acts:
        mark = "пропуск (пустой текст)" if a["skip"] else f"{a['format']}, медиа: {', '.join(a['media']) or '—'}"
        lines.append(f"  {a['from']} → {a['to']}  [{mark}]")
    return "\n".join(lines) or "  нечего переносить"


def dump(data) -> str:
    return yaml.safe_dump(data, allow_unicode=True, sort_keys=False)
