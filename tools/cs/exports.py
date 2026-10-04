"""Файлы экспорта объекта: формат, размеры, вес, хэш, метаданные (гейты Platform, Safety).

Требования — data/platforms/export_specs.yaml (Д13). Экспорт со статусом
`preview` — для утверждения автором; `final` — только после APPROVED.
"""
from __future__ import annotations

import struct

from .common import Finding, Item, Report, image_size, jpeg_metadata_segments, load_items, load_yaml, sha256_file
from .model import effective_state, reached

PNG_META = {b"tEXt", b"iTXt", b"zTXt", b"eXIf", b"tIME"}


def png_metadata_chunks(path) -> list[str]:
    data = path.read_bytes()
    found, i = [], 8
    while i + 8 <= len(data):
        length, ctype = struct.unpack(">I4s", data[i:i + 8])
        if ctype in PNG_META:
            found.append(ctype.decode())
        if ctype == b"IEND":
            break
        i += 12 + length
    return found


def specs() -> dict:
    return load_yaml("data/platforms/export_specs.yaml")["formats"]


def file_checks(path, fmt_name: str, fmts: dict) -> dict[str, list[Finding]]:
    """Platform: тип, размеры, вес. Safety: метаданные EXIF/XMP/текст."""
    out: dict[str, list[Finding]] = {"platform": [], "safety": []}
    name = path.name
    fmt = fmts.get(fmt_name)
    if fmt is None:
        out["platform"].append(Finding("error", f"{name}: формата {fmt_name} нет в export_specs.yaml"))
        return out
    try:
        w, h, kind = image_size(path)
    except ValueError as e:
        out["platform"].append(Finding("error", str(e)))
        return out
    if kind != fmt["file"]:
        out["platform"].append(Finding("error", f"{name}: тип {kind}, а формат требует {fmt['file']}"))
    if (w, h) != (fmt["width"], fmt["height"]):
        out["platform"].append(Finding("error", f"{name}: {w}×{h}, а формат требует {fmt['width']}×{fmt['height']}"))
    size = path.stat().st_size
    if fmt.get("max_bytes") and size > fmt["max_bytes"]:
        out["platform"].append(Finding("error", f"{name}: {size} байт > {fmt['max_bytes']}"))
    if not fmt.get("verified", False):
        out["platform"].append(Finding("warn", f"{name}: требования {fmt_name} не подтверждены официально ({fmt.get('source', '—')})"))
    meta = jpeg_metadata_segments(path) if kind == "jpg" else png_metadata_chunks(path)
    if meta:
        out["safety"].append(Finding("error", f"{name}: метаданные в файле — {', '.join(meta)}"))
    return out


def item_checks(item: Item, variant: str | None = None) -> dict[str, list[Finding]]:
    d = item.data
    fmts = specs()
    out: dict[str, list[Finding]] = {"platform": [], "safety": []}
    states = [h["state"] for h in d.get("history", [])]
    state = effective_state(d.get("state", "IDEA"), states)
    listed = set()
    for e in d.get("exports", []):
        if variant and e.get("variant") != variant:
            continue
        p = item.path(e["file"])
        listed.add(p.resolve())
        if not p.is_file():
            out["platform"].append(Finding("error", f"{item.id}: экспорт {e['file']} не найден"))
            continue
        if sha256_file(p) != e["sha256"]:
            out["platform"].append(Finding("error", f"{item.id}: хэш {e['file']} не совпадает с item.yaml"))
        res = file_checks(p, e["format"], fmts)
        for k in out:
            out[k] += res[k]
        if e.get("status") == "final" and not reached(state, "APPROVED"):
            out["platform"].append(Finding("error", f"{item.id}: {e['file']} — final до APPROVED"))
        sel = d.get("selected_variant")
        if sel and e.get("variant") and e["variant"] != sel:
            out["platform"].append(Finding("warn", f"{item.id}: {e['file']} — не выбранный вариант {sel}"))
    exp_dir = item.dir / "exports"
    if exp_dir.is_dir() and not variant:
        for f in sorted(exp_dir.iterdir()):
            if f.suffix in (".png", ".jpg") and f.resolve() not in listed:
                out["platform"].append(Finding("error", f"{item.id}: exports/{f.name} не записан в exports[] item.yaml"))
    return out


def check() -> Report:
    r = Report("exports — форматы, размеры, вес, метаданные")
    n = 0
    for item in load_items():
        n += len(item.data.get("exports", []))
        res = item_checks(item)
        r.add(res["platform"])
        r.add(res["safety"])
    r.note(f"файлов экспорта {n}")
    return r
