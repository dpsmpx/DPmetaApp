"""Происхождение: реестр входных ассетов и записи о выходах объектов (гейты Provenance, Safety).

Правила — docs/PROVENANCE.md. Реестр — data/provenance/assets.yaml; выходы
объекта — content/items/<id>/provenance.yaml (пишет render.py).
"""
from __future__ import annotations

import re

from .common import ROOT, Finding, Item, Report, jpeg_metadata_segments, load_items, load_yaml, rel, repo_files, sha256_file

ASSETS = "data/provenance/assets.yaml"
DOC = ROOT / "docs" / "PROVENANCE.md"


def assets() -> dict[str, dict]:
    return {a["id"]: a for a in load_yaml(ASSETS)["assets"]}


def registry_checks() -> dict[str, list[Finding]]:
    out: dict[str, list[Finding]] = {"provenance": [], "safety": []}
    reg = assets()
    covered: set[str] = set()
    for aid, a in reg.items():
        p = ROOT / a["path"]
        if not p.exists():
            out["provenance"].append(Finding("error", f"{aid}: {a['path']} не найден"))
            continue
        if a.get("sha256"):
            covered.add(a["path"])
            if p.is_file() and sha256_file(p) != a["sha256"]:
                out["provenance"].append(Finding("error", f"{aid}: хэш {a['path']} не совпадает с реестром"))
        elif not a.get("files"):
            out["provenance"].append(Finding("error", f"{aid}: хэш не закреплён (sha256 или files[])"))
        for f in a.get("files", []):
            covered.add(f["path"])
            fp = ROOT / f["path"]
            if not fp.is_file():
                out["provenance"].append(Finding("error", f"{aid}: {f['path']} не найден"))
            elif sha256_file(fp) != f["sha256"]:
                out["provenance"].append(Finding("error", f"{aid}: хэш {f['path']} не совпадает с реестром"))
        if a["kind"] == "REFERENCE_IMAGE":
            if not a["path"].startswith("assets/reference/"):
                out["provenance"].append(Finding("error", f"{aid}: REFERENCE_IMAGE вне assets/reference/"))
            if p.is_file():
                meta = jpeg_metadata_segments(p)
                if meta:
                    out["safety"].append(Finding("error", f"{aid}: в фотографии метаданные {', '.join(meta)} (геометки, устройство)"))
        if a["kind"] == "generated":
            g = a.get("generation") or {}
            missing = [k for k in ("method", "tool", "model", "prompt") if not g.get(k)]
            if missing:
                out["provenance"].append(Finding("error", f"{aid}: сгенерированный ассет без {', '.join(missing)} (Д11)"))
    for f in repo_files():
        r = rel(f)
        if r.startswith("assets/") and f.name != "README.md" and r not in covered:
            out["provenance"].append(Finding("error", f"{r}: файла нет в реестре {ASSETS}"))
    if DOC.is_file():
        text = DOC.read_text(encoding="utf-8")
        for aid in reg:
            if not re.search(rf"\b{aid}\b", text):
                out["provenance"].append(Finding("error", f"{aid}: не описан в docs/PROVENANCE.md"))
    else:
        out["provenance"].append(Finding("error", "нет docs/PROVENANCE.md"))
    return out


def item_findings(item: Item, variant: str | None = None) -> list[Finding]:
    out: list[Finding] = []
    reg = assets()
    d = item.data
    declared = set(d.get("inputs", []))
    for aid in declared:
        if aid not in reg:
            out.append(Finding("error", f"{item.id}: вход {aid} нет в реестре ассетов"))
    prov_path = item.dir / "provenance.yaml"
    prov = load_yaml(prov_path) if prov_path.is_file() else {"outputs": []}
    if d.get("provenance") and not item.path(d["provenance"]).is_file():
        out.append(Finding("error", f"{item.id}: {d['provenance']} не найден"))
    recorded = set()
    for o in prov.get("outputs", []):
        recorded.add(o["file"])
        if variant and o["variant"] != variant:
            continue
        name = o["file"].split("/")[-1]
        f = ROOT / o["file"]
        if not f.is_file():
            out.append(Finding("error", f"{item.id}: {o['file']} записан, но файла нет"))
            continue
        if sha256_file(f) != o["sha256"]:
            out.append(Finding("error", f"{item.id}: {name} — хэш не совпадает с provenance.yaml"))
        sp = ROOT / o["spec"]
        if not sp.is_file():
            out.append(Finding("error", f"{item.id}: {name} — спецификации {o['spec']} нет"))
        elif sha256_file(sp) != o["spec_sha256"]:
            out.append(Finding("error", f"{item.id}: {name} — спецификация изменена после рендера, перерендерить"))
        for aid in o.get("inputs", []):
            if aid not in reg:
                out.append(Finding("error", f"{item.id}: {name} — вход {aid} нет в реестре"))
            elif aid not in declared:
                out.append(Finding("error", f"{item.id}: {name} — вход {aid} не заявлен в inputs объекта"))
        if o.get("prompt") and not (ROOT / o["prompt"]).is_file():
            out.append(Finding("error", f"{item.id}: {name} — промпт {o['prompt']} не найден"))
        if o.get("layout") and not (ROOT / o["layout"]).is_file():
            out.append(Finding("error", f"{item.id}: {name} — раскладка {o['layout']} не найдена"))
    for sub in ("renders", "exports"):
        dd = item.dir / sub
        if dd.is_dir():
            for f in sorted(dd.iterdir()):
                if f.suffix in (".png", ".jpg") and rel(f) not in recorded:
                    out.append(Finding("error", f"{item.id}: {sub}/{f.name} — нет записи в provenance.yaml"))
    return out


def check() -> Report:
    r = Report("provenance — происхождение ассетов и выходов")
    reg = registry_checks()
    r.add(reg["provenance"])
    r.add(reg["safety"])
    items = load_items()
    for item in items:
        r.add(item_findings(item))
    r.note(f"ассетов {len(assets())}, объектов {len(items)}")
    return r
