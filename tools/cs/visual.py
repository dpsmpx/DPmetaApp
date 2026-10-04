"""Проверки по раскладке рендера (*.layout.json): гейты Visual, Platform, Legal.

Раскладку снимает tools/render/render.js при каждом рендере: рамки элементов
и текста, размер и насыщенность шрифта, токен цвета текста, яркость фона под
текстом (по снимку со скрытым текстом). Пороги — из assets/brand/tokens.json
(limits) и data/platforms/export_specs.yaml (размеры, safe_area).

Пиксели — пиксели формата: минимальный текст = min_text_px_at_720 × высота / 720.
"""
from __future__ import annotations

import json

from .common import ROOT, Finding, Item, Report, load_items, load_json, load_yaml

TOKENS = "assets/brand/tokens.json"
SAFE_ROLES = {"face", "name", "role"}  # обязаны быть в safe_area (QUALITY_MODEL, гейт Platform)
MIN_MARGIN = 0.05  # поля — не меньше 5 % короткой стороны (VISUAL_SYSTEM.md §4); у форматов с safe_area — зона
MAX_TEXT_SIZES = 3  # «не больше трёх размеров текста» — VISUAL_SYSTEM.md §3, tokens.json type_scale.note


def _lin(c: float) -> float:
    c /= 255
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def luminance(hex_: str) -> float:
    h = hex_.lstrip("#")
    r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
    return 0.2126 * _lin(r) + 0.7152 * _lin(g) + 0.0722 * _lin(b)


def contrast(l1: float, l2: float) -> float:
    hi, lo = max(l1, l2), min(l1, l2)
    return (hi + 0.05) / (lo + 0.05)


def _box(e: dict) -> tuple[float, float, float, float]:
    b = e.get("text_box") or e
    return b["x"], b["y"], b["w"], b["h"]


def _inside(b, x, y, w, h, tol=1.0) -> bool:
    bx, by, bw, bh = b
    return bx >= x - tol and by >= y - tol and bx + bw <= x + w + tol and by + bh <= y + h + tol


def _overlap(a, b) -> float:
    ax, ay, aw, ah = a
    bx, by, bw, bh = b
    dx = min(ax + aw, bx + bw) - max(ax, bx)
    dy = min(ay + ah, by + bh) - max(ay, by)
    return dx * dy if dx > 1 and dy > 1 else 0.0


def layout_checks(layout: dict, fmt: dict, tok: dict, where: str, soft: bool = False) -> dict[str, list[Finding]]:
    """Находки по гейтам visual / platform / legal для одной раскладки. soft — отклонённый вариант."""
    lvl = "warn" if soft else "error"
    out: dict[str, list[Finding]] = {"visual": [], "platform": [], "legal": []}
    W, H = layout["width"], layout["height"]
    lim = tok["limits"]
    if (W, H) != (fmt["width"], fmt["height"]):
        out["platform"].append(Finding("error", f"{where}: {W}×{H}, а формат требует {fmt['width']}×{fmt['height']}"))
    for url in layout.get("external_requests", []):
        out["legal"].append(Finding("error", f"{where}: внешний запрос {url} — материалы только локальные"))
    allowed = set(tok["allowed_fonts"])
    loaded = set(layout.get("fonts_loaded", []))
    min_px = lim["min_text_px_at_720"] * H / 720
    margin = MIN_MARGIN * min(W, H)
    texts = [e for e in layout["elements"] if e.get("text")]
    for e in texts:
        name = f"{where}: {e['role']} «{e['text'][:32]}»"
        family = e["font_family"].split(",")[0].strip().strip("'\"")
        if family not in allowed:
            out["legal"].append(Finding("error", f"{name}: шрифт {family} не из разрешённых {sorted(allowed)}"))
        elif family not in loaded:
            out["visual"].append(Finding("error", f"{name}: шрифт {family} не загрузился — подставлен системный"))
        if e["font_px"] < min_px - 0.05:
            out["visual"].append(Finding(lvl, f"{name}: текст {e['font_px']:.1f} px < {min_px:.1f} px"))
        if e.get("overflow"):
            out["visual"].append(Finding(lvl, f"{name}: текст не помещается в свой блок"))
        if not _inside(_box(e), 0, 0, W, H):
            out["visual"].append(Finding(lvl, f"{name}: текст выходит за край изображения"))
        elif not fmt.get("safe_area") and not _inside(_box(e), margin, margin, W - 2 * margin, H - 2 * margin):
            out["visual"].append(Finding(lvl, f"{name}: текст ближе {margin:.0f} px к краю (поля ≥ 5 % короткой стороны)"))
        fg, bg = e.get("fg"), e.get("bg_luminance")
        if fg and bg:
            norm = e["font_px"] * 720 / H  # размер в пересчёте на высоту 720
            large = norm >= 24 or (norm >= 18.66 and e.get("font_weight", 400) >= 700)
            need = lim["min_contrast_large"] if large else lim["min_contrast_normal"]
            c = contrast(luminance(tok["color"][fg]["hex"]), bg["p95"])
            if c < need:
                out["visual"].append(Finding(lvl, f"{name}: контраст {c:.2f} < {need} (по фону под текстом)"))
        elif not fg:
            out["visual"].append(Finding("warn", f"{name}: нет data-fg — контраст не проверен"))
    sizes = sorted({round(e["font_px"] * 2) / 2 for e in texts}, reverse=True)
    if len(sizes) > MAX_TEXT_SIZES:
        out["visual"].append(Finding(lvl, f"{where}: размеров текста {len(sizes)} > {MAX_TEXT_SIZES} ({', '.join(f'{x:g}' for x in sizes)} px)"))
    groups = sorted({e["group"] for e in texts if e.get("group")})
    if len(groups) > lim["max_text_groups"]:
        out["visual"].append(Finding(lvl, f"{where}: групп текста {len(groups)} > {lim['max_text_groups']} ({', '.join(groups)})"))
    for i, a in enumerate(texts):
        for b in texts[i + 1:]:
            if _overlap(_box(a), _box(b)) > 0:
                out["visual"].append(Finding(lvl, f"{where}: текст «{a['text'][:20]}» накладывается на «{b['text'][:20]}»"))
    portrait = [e for e in layout["elements"] if e["role"] == "portrait"]
    if portrait:
        pa = max(e["w"] * e["h"] for e in portrait)
        for e in texts:
            _, _, w, h = _box(e)
            if w * h > pa:
                out["visual"].append(Finding(lvl, f"{where}: «{e['text'][:20]}» крупнее портрета — человек не первый"))
    sa = fmt.get("safe_area")
    if sa:
        for e in layout["elements"]:
            if e["role"] in SAFE_ROLES and not _inside(_box(e), sa["x"], sa["y"], sa["w"], sa["h"]):
                out["platform"].append(Finding(lvl, f"{where}: {e['role']} вне безопасной зоны {sa}"))
    return out


def item_outputs(item: Item) -> list[dict]:
    p = item.dir / "provenance.yaml"
    return (load_yaml(p) or {}).get("outputs", []) if p.is_file() else []


def item_checks(item: Item, variant: str | None = None) -> dict[str, list[Finding]]:
    """Находки по всем выходам объекта (рендеры и экспорт) из provenance.yaml."""
    tok = load_json(ROOT / TOKENS)
    specs = load_yaml("data/platforms/export_specs.yaml")["formats"]
    out: dict[str, list[Finding]] = {"visual": [], "platform": [], "legal": []}
    for o in item_outputs(item):
        if variant and o["variant"] != variant:
            continue
        where = o["file"].split("/")[-1]
        lp = ROOT / o.get("layout", "")
        if not o.get("layout") or not lp.is_file():
            out["visual"].append(Finding("error", f"{where}: нет раскладки — перерендерить"))
            continue
        fmt = specs.get(o["format"])
        if fmt is None:
            out["platform"].append(Finding("error", f"{where}: формата {o['format']} нет в export_specs.yaml"))
            continue
        v = item.variant(o["variant"]) or {}
        res = layout_checks(json.loads(lp.read_text(encoding="utf-8")), fmt, tok, where,
                            soft=v.get("status") == "rejected")
        for k in out:
            out[k] += res[k]
    return out


def check() -> Report:
    r = Report("visual — читаемость, контраст, группы, безопасные зоны, шрифты")
    n = 0
    for item in load_items():
        res = item_checks(item)
        n += len(item_outputs(item))
        for k in ("visual", "platform", "legal"):
            r.add(res[k], prefix=f"{item.id} ")
    r.note(f"раскладок {n}")
    return r
