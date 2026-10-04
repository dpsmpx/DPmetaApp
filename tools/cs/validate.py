"""Схемы данных и объекты контента: состояния, история, гейты (Д1, CONTENT_MODEL.md §2).

- `schemas_check` — каждый файл данных по своей JSON Schema;
- `items_check` — требования состояния, допустимость пути по истории,
  выбор и утверждение, согласованность записанных гейтов с тем, что
  находят автоматические проверки (гейт `pass` при красной проверке —
  ошибка).
"""
from __future__ import annotations

import re

import jsonschema

from . import claims, exports, provenance, visual
from .common import ROOT, Finding, Item, Report, as_date, load_items, load_json, load_yaml, rel, today
from .model import GATES, STATES, check_path, effective_state, reached
from .schema import errors as schema_errors

# Файл данных → схема. Шаблоны путей — от корня.
SCHEMA_MAP = [
    ("data/facts/facts.yaml", "facts"),
    ("data/facts/forbidden.yaml", "forbidden"),
    ("data/facts/claim_keywords.yaml", "claim_keywords"),
    ("data/provenance/assets.yaml", "assets"),
    ("data/platforms/export_specs.yaml", "export_specs"),
    ("data/identity/links.yaml", "links"),
    ("assets/brand/tokens.json", "brand_tokens"),
    ("content/items/*/item.yaml", "content_item"),
    ("content/items/*/provenance.yaml", "item_provenance"),
    ("content/items/*/spec/*.yaml", "composition"),
    ("data/experiments/*.yaml", "experiment"),
    ("data/metrics/*/*.yaml", "metrics"),
    ("data/identity/brands.yaml", "brands"),
    ("data/projects/*.yaml", "project"),
    ("data/platforms/destinations.yaml", "destinations"),
    ("data/platforms/capabilities.yaml", "capabilities"),
    ("data/policy/publication.yaml", "policy"),
    ("data/website/site.yaml", "site"),
    ("data/releases/*.yaml", "releases"),
    ("data/campaigns/*.yaml", "campaign"),
]


def schemas_check() -> Report:
    r = Report("schemas — данные по JSON Schema")
    n = 0
    for sp in sorted((ROOT / "schemas").glob("*.schema.json")):
        try:
            jsonschema.Draft202012Validator.check_schema(load_json(sp))
        except jsonschema.SchemaError as e:
            r.error(f"{rel(sp)}: схема неверна — {e.message}")
    for pattern, name in SCHEMA_MAP:
        for p in sorted(ROOT.glob(pattern)):
            n += 1
            doc = load_json(p) if p.suffix == ".json" else load_yaml(p)
            for e in schema_errors(doc, name):
                r.error(f"{rel(p)}: {e}")
    r.note(f"файлов {n}")
    return r


def _open_decisions() -> dict[str, bool]:
    """ОР-N → открыто ли (по таблице DECISIONS.md §3; закрытое зачёркнуто)."""
    text = (ROOT / "docs/direction/DECISIONS.md").read_text(encoding="utf-8")
    out = {}
    for m in re.finditer(r"^\|\s*(~~)?(ОР-\d+)(~~)?\s*\|", text, re.M):
        out[m.group(2)] = not m.group(1)
    return out


def gate_findings(item: Item, variant: str | None) -> dict[str, list[Finding]]:
    """Что находят автоматические проверки по гейтам (для выбранного варианта или всех)."""
    vis = visual.item_checks(item, variant)
    exp = exports.item_checks(item, variant)
    reg = provenance.registry_checks()
    used = set(item.data.get("inputs", []))
    return {
        "factual": claims.item_findings(item, variant),
        "visual": vis["visual"],
        "platform": vis["platform"] + exp["platform"],
        "safety": exp["safety"] + [f for f in reg["safety"] if f.msg.split(":")[0] in used],
        "legal": vis["legal"],
        "provenance": provenance.item_findings(item, variant),
        "brand": [],  # утверждает автор; шрифты и токены проверяются в legal/visual
    }


def item_findings(item: Item) -> list[Finding]:
    d = item.data
    out: list[Finding] = []
    E = lambda m: out.append(Finding("error", f"{item.id}: {m}"))  # noqa: E731
    W = lambda m: out.append(Finding("warn", f"{item.id}: {m}"))  # noqa: E731

    if item.dir.name != f"{d.get('id')}-{d.get('slug')}":
        E(f"каталог {item.dir.name} не равен <id>-<slug>")
    hist = d.get("history", [])
    hs = [h.get("state") for h in hist]
    for s in hs:
        if s not in STATES:
            E(f"история: неизвестное состояние {s}")
    for msg in check_path([s for s in hs if s in STATES]):
        E(f"история: {msg}")
    state = d.get("state")
    if hs and hs[-1] != state:
        E(f"state {state}, а последняя запись истории — {hs[-1]}")
    dates = [as_date(h.get("date")) for h in hist]
    if any(a and b and b < a for a, b in zip(dates, dates[1:])):
        E("история: даты идут назад")
    if any(x and x > today() for x in dates):
        E("история: дата в будущем")
    created = as_date(d.get("created"))
    if created and dates and dates[0] and dates[0] < created:
        E("история начинается раньше created")

    eff = effective_state(state, hs)
    variants = d.get("variants", [])
    vids = [v["id"] for v in variants]
    if len(vids) != len(set(vids)):
        E("id вариантов повторяются")
    for v in variants:
        if v.get("status") == "rejected" and not v.get("rejection_reason"):
            E(f"вариант {v['id']} отклонён без rejection_reason")

    if reached(eff, "RESEARCHED"):
        if not d.get("audiences") or not d.get("platforms"):
            E("с RESEARCHED нужны audiences и platforms")
        if not d.get("claims") and not d.get("claims_none_reason"):
            E("с RESEARCHED нужны claims или claims_none_reason")
    if reached(eff, "BRIEFED"):
        if not d.get("brief") or not item.path(d["brief"]).is_file():
            E(f"с BRIEFED нужен файл брифа ({d.get('brief')})")
    v2 = str(d.get("schema", "")).endswith("/v2")
    canon_versions = (d.get("canonical") or {}).get("versions") or []
    if reached(eff, "GENERATED") and v2 and not variants:
        if not canon_versions:
            E("с GENERATED нужна версия канона (content_item/v2) или вариант со спецификацией")
    elif reached(eff, "GENERATED"):
        if not variants:
            E("с GENERATED нужен хотя бы один вариант")
        rendered = 0
        for v in variants:
            sp = item.path(v["spec"])
            if not sp.is_file():
                E(f"вариант {v['id']}: спецификации {v['spec']} нет")
            elif (load_yaml(sp) or {}).get("variant") != v["id"]:
                E(f"вариант {v['id']}: в {v['spec']} другой variant")
            for f in v.get("renders", []):
                if item.path(f).is_file():
                    rendered += 1
                else:
                    E(f"вариант {v['id']}: рендера {f} нет")
        if variants and not rendered:
            E("с GENERATED нужен хотя бы один рендер")
    if reached(eff, "UNDER_REVIEW"):
        rv = d.get("review") or {}
        if not rv.get("file") or not item.path(rv["file"]).is_file():
            E(f"с UNDER_REVIEW нужен файл критики ({rv.get('file')})")
        scores = rv.get("scores") or {}
        for vid in vids:
            if vid not in scores:
                E(f"нет оценок варианта {vid}")
        ids = [i["id"] for sev in ("fatal", "major", "minor") for i in (rv.get("issues") or {}).get(sev, [])]
        if len(ids) != len(set(ids)):
            E("id проблем в review.issues повторяются")

    sel = d.get("selected_variant")
    selected = [v["id"] for v in variants if v.get("status") == "selected"]
    if sel:
        if sel not in vids:
            E(f"selected_variant {sel} нет среди вариантов")
        elif selected != [sel]:
            E(f"статус selected должен быть только у {sel} (сейчас: {selected or 'ни у одного'})")
    elif selected:
        E(f"статус selected у {selected}, но selected_variant не задан")

    gates = d.get("gates") or {}
    if reached(eff, "APPROVED"):
        if not sel:
            E("APPROVED без выбранного варианта")
        for g in GATES:
            st = (gates.get(g) or {}).get("status")
            if st not in ("pass", "n/a"):
                E(f"APPROVED: гейт {g} — {st or 'не записан'}")
        issues = (d.get("review") or {}).get("issues") or {}
        for sev in ("fatal", "major"):
            for i in issues.get(sev, []):
                # Блокируют проблемы утверждаемого варианта и общие; у отклонённых — история выбора.
                if i["status"] == "open" and i.get("variant") in (None, sel):
                    E(f"APPROVED: открытая проблема {sev} {i['id']}")
        if (d.get("approval") or {}).get("by") != "author":
            E("APPROVED без approval автора (Д12)")
    elif d.get("approval"):
        W("approval записан, но состояние ниже APPROVED")
    if (gates.get("brand") or {}).get("status") == "pass" and gates["brand"].get("by") != "author":
        E("гейт brand проходит только автор")

    if reached(eff, "EXPORTED"):
        fmts = exports.specs()
        finals = [e for e in d.get("exports", []) if e.get("status") == "final" and e.get("variant") == sel]
        have = {fmts.get(e["format"], {}).get("platform") for e in finals}
        approved_text = set()
        if v2:  # текст площадки — одобренный вариант (Д12); изображения — финальный экспорт
            from .core.content import approval_state, find
            from .core.repo import Repo
            ci = find(Repo(), item.id)
            approved_text = {v.platform for v in ci.variants().values() if approval_state(v) == "valid"}
        for p in d.get("platforms", []):
            if v2 and p not in approved_text:
                E(f"EXPORTED: нет одобренного варианта для {p}")
            if p not in have and (not v2 or (variants and p in {s.get("platform") for s in fmts.values()})):
                E(f"EXPORTED: нет финального экспорта для {p}")
    published = [p for p in d.get("publication", []) if p.get("state") == "published"]
    if v2:  # журнал публикаций — источник истины о публикациях (Д24)
        from .core.ledger import Ledger
        from .core.repo import Repo
        published += [{"url": p.remote.get("remote_url"), "published_at": p.last("PUBLISHED").get("ts")}
                      for p in Ledger(Repo(), "production").find(item=item.id, states={"PUBLISHED", "SUPERSEDED"})]
    if published and not reached(eff, "PUBLISHED"):
        E("есть публикация, а состояние ниже PUBLISHED — публикация не из EXPORTED")
    if reached(eff, "PUBLISHED") and not any(p.get("url") and p.get("published_at") for p in published):
        E("PUBLISHED без адреса и даты публикации")

    open_or = _open_decisions()
    for b in d.get("blocked_by", []):
        m = re.match(r"ОР-\d+", b)
        if m and m.group(0) not in open_or:
            E(f"blocked_by {b}: нет в DECISIONS.md §3")
        elif m and not open_or[m.group(0)]:
            W(f"blocked_by {b}: решение закрыто — убрать из blocked_by")
    if d.get("blocked_by"):
        todo = ROOT / "TODO.md"
        if not todo.is_file() or item.id not in todo.read_text(encoding="utf-8"):
            E("объект ждёт автора (blocked_by), но не отмечен в TODO.md")

    # Записанный pass при красной автоматической проверке — ложь в объекте.
    if any((gates.get(g) or {}).get("status") == "pass" for g in GATES):
        found = gate_findings(item, sel)
        for g in GATES:
            if (gates.get(g) or {}).get("status") == "pass":
                errs = [f.msg for f in found[g] if f.level == "error"]
                if errs:
                    E(f"гейт {g} записан pass, но проверка находит: {errs[0]}" + (f" (+{len(errs) - 1})" if len(errs) > 1 else ""))
    return out


def items_check() -> Report:
    r = Report("items — состояния, история, гейты объектов")
    items = load_items()
    ids = [i.id for i in items]
    for dup in {x for x in ids if ids.count(x) > 1}:
        r.error(f"{dup}: id объекта повторяется")
    for item in items:
        r.add(item_findings(item))
        r.note(f"{item.id} {item.data.get('state')}")
    return r


def status_rows() -> list[dict]:
    rows = []
    for item in load_items():
        d = item.data
        g = d.get("gates") or {}
        mark = {"pass": "✓", "fail": "✗", "pending": "?", "n/a": "–"}
        rows.append({
            "id": item.id,
            "state": d.get("state"),
            "title": d.get("title"),
            "selected": d.get("selected_variant") or "—",
            "gates": " ".join(f"{k[:4]}{mark.get((g.get(k) or {}).get('status'), '·')}" for k in GATES),
            "blocked_by": ", ".join(d.get("blocked_by", [])) or "—",
        })
    return rows
