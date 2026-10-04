"""ContentValidator (PLATFORM_VISION §46, Д26): проверка канона и вариантов до одобрения.

Проверяет: обязательные поля и схему; подстановки фактов и ссылок;
утверждения и запреты (тот же разбор, что гейт Factual — claims.py);
ссылки в тексте — только сайт и реестр ссылок; медиа есть и записаны в
происхождении; ограничения площадки (адаптер); бренды и язык адресата.
Вывод ИИ считается непроверенным, пока здесь нет ошибок (§45).
"""
from __future__ import annotations

import re

from .. import claims
from ..common import Finding, sha256_file
from ..schema import errors as schema_errors
from . import markup
from .content import CItem, CanonVersion, Variant, resolved_body
from .inject import resolve

PLATFORM_MARKUP = re.compile(r"</?(?:b|i|u|s|a|code|pre|strong|em|br|span)\b[^>]*>|^#\w|(?<!\w)@\w{4,}", re.M)


def _brand_findings(item: CItem) -> list[Finding]:
    out = []
    b = item.data.get("brand") or {}
    mode, brands = b.get("mode"), list(b.get("brands") or [])
    known = item.repo.brands()
    for x in brands:
        if x not in known:
            out.append(Finding("error", f"бренд {x} не описан в data/identity/brands.yaml"))
    kinds = [known.get(x, {}).get("kind") for x in brands]
    if mode == "PERSONAL" and brands != ["author"]:
        out.append(Finding("error", "PERSONAL — только бренд author (Д27)"))
    if mode == "PROJECT" and (len(brands) != 1 or kinds != ["PROJECT"]):
        out.append(Finding("error", "PROJECT — ровно один бренд проекта (Д27)"))
    if mode == "MIXED" and ("author" not in brands or kinds.count("PROJECT") != 1):
        out.append(Finding("error", "MIXED — автор и один проект (Д27)"))
    return out


def _claim_findings(item: CItem, text: str, fids: list[str], where: str) -> list[Finding]:
    rules = (item.repo.yaml("data/facts/forbidden.yaml") or {}).get("rules", [])
    return claims.text_findings(item, text, fids, where, False, item.repo.facts(), rules)


def _link_findings(repo, urls: list[str], where: str) -> list[Finding]:
    out = []
    bases = [b.rstrip("/") for b in (repo.canonical_base(), repo.site_base("staging")) if b]
    known = {l["url"]: l for l in repo.links().values() if l.get("url")}
    for u in urls:
        if u.startswith("/") or any(u.startswith(b) for b in bases):
            continue
        l = known.get(u) or known.get(u.rstrip("/"))
        if l is None:
            out.append(Finding("warn", f"{where}: ссылка {u} не из реестра data/identity/links.yaml"))
        elif l.get("status") in ("broken_for_public", "stale"):
            out.append(Finding("error", f"{where}: ссылка {u} — {l['status']} ({l['id']})"))
    return out


def validate_canon(item: CItem, cv: CanonVersion) -> list[Finding]:
    where = f"{item.id}/canonical/v{cv.version}"
    out = [Finding("error", f"{where}: {e}") for e in schema_errors(cv.doc.meta, "canonical")]
    lang = cv.doc.meta.get("lang") or item.canonical_lang
    res = resolve(cv.doc.body, lang, item.repo)
    out += [Finding("error", f"{where}: {e}") for e in res.errors]
    fids = sorted(set([c["fact"] for c in item.data.get("claims", [])] + res.facts_used))
    out += _claim_findings(item, res.text, fids, where)
    if PLATFORM_MARKUP.search(cv.doc.body):
        out.append(Finding("warn", f"{where}: похоже на разметку площадки (HTML, #метка, @упоминание) — канон без неё (§9)"))
    blocks = markup.parse(res.text)
    out += _link_findings(item.repo, markup.links(blocks), where)
    if not cv.doc.body.strip():
        out.append(Finding("error", f"{where}: пустой текст"))
    return out


def validate_variant(v: Variant, adapter=None, destination: dict | None = None) -> list[Finding]:
    item, repo = v.item, v.item.repo
    where = f"{item.id}/{v.id}"
    out = [Finding("error", f"{where}: {e}") for e in schema_errors(v.doc.meta, "variant")]
    if v.platform not in repo.capabilities():
        out.append(Finding("error", f"{where}: площадка {v.platform} не описана в capabilities.yaml"))
    if v.lang not in (item.data.get("languages") or [v.lang]):
        out.append(Finding("error", f"{where}: язык {v.lang} не заявлен в languages объекта"))
    sv = v.m("source_version")
    if item.versions():
        cv = item.canon(sv) if sv else None
        if cv is None:
            out.append(Finding("error", f"{where}: source_version {sv} — нет такой версии канона"))
        elif not cv.sealed:
            out.append(Finding("error", f"{where}: канон v{sv} не зафиксирован (canonical seal)"))
    res = resolved_body(v, repo.canonical_base())
    out += [Finding("error", f"{where}: {e}") for e in res.errors]
    fids = sorted(set([c["fact"] for c in item.data.get("claims", [])] + res.facts_used))
    text = "\n".join(x for x in (v.m("title"), res.text, v.m("cta")) if x)
    out += _claim_findings(item, text, fids, where)
    out += _link_findings(repo, markup.links(markup.parse(res.text)), where)
    # медиа — только записанные в происхождении (гейт Provenance)
    known = {o.get("file"): o.get("sha256") for o in (repo.yaml(f"content/items/{item.dir.name}/provenance.yaml") or {}).get("outputs", [])}
    assets = {a.get("path"): a.get("sha256") for a in (repo.yaml("data/provenance/assets.yaml") or {}).get("assets", [])}
    from .content import media_files
    for ref, p in media_files(v):
        if not p.is_file():
            out.append(Finding("error", f"{where}: медиа {ref} — файла нет"))
            continue
        rec = known.get(ref, assets.get(ref))
        if rec is None:
            out.append(Finding("error", f"{where}: медиа {ref} не записано в provenance.yaml объекта или реестре ассетов"))
        elif rec != sha256_file(p):
            out.append(Finding("error", f"{where}: медиа {ref} — хэш не совпадает с записью происхождения"))
    out += [Finding(f.level, f"{where}: {f.msg}") for f in _brand_findings(item)]
    if adapter is not None:
        out += [Finding(f.level, f"{where}: {f.msg}") for f in adapter.validate(adapter.render(v))]
    if destination is not None:
        if destination.get("platform") != v.platform:
            out.append(Finding("error", f"{where}: адресат {destination['id']} — площадка {destination.get('platform')}"))
        if destination.get("language") and destination["language"] != v.lang:
            out.append(Finding("error", f"{where}: язык варианта {v.lang} ≠ языку адресата {destination['language']}"))
        accepts = set(destination.get("accepts_brands") or [])
        extra = set(item.brands) - accepts
        if not item.brands:
            out.append(Finding("error", f"{where}: у объекта нет brand.brands — адресат не может проверить бренд (Д27)"))
        elif extra:
            out.append(Finding("error", f"{where}: адресат {destination['id']} не принимает бренды {sorted(extra)} (Д27)"))
    return out
