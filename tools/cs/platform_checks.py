"""Проверки платформы публикации (PLATFORM_VISION §99; docs/ARCHITECTURE.md, §5).

canonical     версии канона неизменны после фиксации; варианты: схема, валидатор,
              одобрение по хэшу, устаревание
publications  журналы: схема событий, переходы, время, дубли, одобрение рабочих публикаций
destinations  явные id, границы учётных данных (площадка × окружение), близнецы, бренды
campaigns     объекты, варианты, адресаты, время расписания
projects      бренды, факты и ссылки страниц проектов
site          staging- и production-сборка: ошибки, фиды и JSON читаются, внутренние ссылки живы,
              доступность страниц (П2-10), значок, заголовки хостинга и страницы без нарушений CSP
              (П2-12), адрес выгрузки site.yaml = адресат site-production (Д35)
"""
from __future__ import annotations

import json
import re
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

from .common import Report
from .core.content import approval_state, load_items, stale_reasons
from .core.credentials import ref_environment
from .core.ledger import Ledger, integrity
from .core.repo import Repo
from .core.validator import validate_canon, validate_variant
from .schema import errors as schema_errors
from .common import sha256_file


def canonical_check(repo: Repo | None = None) -> Report:
    repo = repo or Repo()
    r = Report("canonical — канон и варианты площадок (Д22, Д28)")
    n_c = n_v = 0
    for item in load_items(repo):
        if item.schema_version < 2:
            continue
        canon = item.data.get("canonical")
        vs = item.versions()
        if canon:
            nums = [v.version for v in vs]
            if nums != list(range(1, len(nums) + 1)):
                r.error(f"{item.id}: версии канона {nums} — не подряд с 1")
            if canon.get("current") not in nums:
                r.error(f"{item.id}: current v{canon.get('current')} нет среди версий")
        for cv in vs:
            n_c += 1
            if not cv.path.is_file():
                r.error(f"{item.id}: нет файла {cv.record['file']}")
                continue
            if cv.doc.meta.get("version") != cv.version or cv.doc.meta.get("item") != item.id:
                r.error(f"{item.id}/{cv.record['file']}: заголовок не совпадает с записью (item, version)")
            if cv.sealed and sha256_file(cv.path) != cv.record["sha256"]:
                r.error(f"{item.id}: канон v{cv.version} изменён после фиксации — правка только новой версией (Д22)")
            for f in validate_canon(item, cv):
                (r.error if (f.level == "error" and cv.sealed) else r.warn if f.level != "note" else r.note)(f.msg)
        for vid, v in item.variants().items():
            n_v += 1
            ap = approval_state(v)
            strict = v.m("status") == "ready" or ap != "none"
            for f in validate_variant(v):
                if f.level == "error":
                    (r.error if strict else r.warn)(f.msg + ("" if strict else " (черновик)"))
                elif f.level == "warn":
                    r.warn(f.msg)
            for c in v.m("claims") or []:
                if c not in [x["fact"] for x in item.data.get("claims", [])]:
                    r.error(f"{item.id}/{vid}: факт {c} не заявлен в claims объекта")
            if ap == "invalid":
                r.warn(f"{item.id}/{vid}: изменён после одобрения — одобрение недействительно, публикация откажет (Д12)")
            stale = stale_reasons(v)
            if stale:
                r.warn(f"{item.id}/{vid}: устарел — {'; '.join(stale)} (Д28)")
            if v.m("trust") == "validated" and not v.m("deps"):
                r.error(f"{item.id}/{vid}: trust validated без записанных зависимостей — variant seal")
    r.note(f"версий канона {n_c}, вариантов {n_v}")
    return r


def publications_check(repo: Repo | None = None) -> Report:
    repo = repo or Repo()
    r = Report("publications — журнал публикаций (Д24)")
    total = 0
    for env in ("production", "staging"):
        led = Ledger(repo, env)
        for rec in led.records():
            total += 1
            for e in schema_errors({k: v for k, v in rec.items() if k != "_line"}, "publication"):
                r.error(f"{led.path.name}:{rec['_line']}: {e}")
        errs, warns = integrity(led)
        for e in errs:
            r.error(e)
        for w in warns:
            r.warn(w)
    items = {i.id for i in load_items(repo)}
    dests = repo.destinations()
    for env in ("production", "staging"):
        for p in Ledger(repo, env).publications().values():
            f = p.first
            if f.get("item") not in items:
                r.error(f"{p.id}: объекта {f.get('item')} нет")
            if f.get("destination") not in dests:
                r.error(f"{p.id}: адресата {f.get('destination')} нет в destinations.yaml")
            elif dests[f["destination"]].get("environment") != env:
                r.error(f"{p.id}: адресат {f['destination']} не из окружения {env}")
    r.note(f"событий {total}")
    return r


def destinations_check(repo: Repo | None = None) -> Report:
    repo = repo or Repo()
    r = Report("destinations — адресаты и учётные данные (Д25)")
    dests = repo.destinations()
    brands = repo.brands()
    links = repo.links()
    refs: dict[str, set] = {}
    for d in dests.values():
        did = d["id"]
        ref = d.get("credential_ref")
        if ref:
            env = ref_environment(ref)
            if env != d["environment"]:
                r.error(f"{did}: {ref} — имя для {env}, а адресат {d['environment']} (граница окружений)")
            refs.setdefault(ref, set()).add(d["environment"])
        for b in d.get("accepts_brands") or []:
            if b not in brands:
                r.error(f"{did}: бренд {b} не описан")
        if d.get("link") and d["link"] not in links:
            r.error(f"{did}: ссылки {d['link']} нет в links.yaml")
        tw = d.get("staging_twin")
        if tw:
            t = dests.get(tw)
            if t is None:
                r.error(f"{did}: staging_twin {tw} не найден")
            elif t.get("environment") != "staging" or t.get("platform") != d["platform"]:
                r.error(f"{did}: близнец {tw} должен быть staging той же площадки")
        if d.get("status") == "active":
            if not (d.get("target") or {}).get("id"):
                r.error(f"{did}: active без target.id — по имени не публикуем (§52)")
            if d["platform"] == "telegram" and not (d.get("account") or {}).get("id"):
                r.error(f"{did}: active Telegram без account.id бота")
        if d.get("status") == "blocked" and not d.get("blocked_by"):
            r.error(f"{did}: blocked без blocked_by")
    for ref, envs in refs.items():
        if len(envs) > 1:
            r.error(f"{ref}: одни учётные данные в окружениях {sorted(envs)} — смешение запрещено (§55)")
    r.note(f"адресатов {len(dests)}, активных {sum(1 for d in dests.values() if d.get('status') == 'active')}")
    return r


def campaigns_check(repo: Repo | None = None) -> Report:
    repo = repo or Repo()
    r = Report("campaigns — кампании")
    items = {i.id: i for i in load_items(repo)}
    dests = repo.destinations()
    for cid, c in repo.campaigns().items():
        if Path(c["_file"]).stem != cid:
            r.error(f"{c['_file']}: id {cid} не совпадает с именем файла")
        for iid in c.get("items", []):
            if iid not in items:
                r.error(f"{cid}: объекта {iid} нет")
            elif items[iid].data.get("campaign") not in (None, cid):
                r.warn(f"{cid}: у {iid} campaign = {items[iid].data.get('campaign')}")
        for s in c.get("schedule") or []:
            if s["item"] not in c.get("items", []):
                r.error(f"{cid}: в расписании {s['item']}, которого нет в items")
                continue
            it = items.get(s["item"])
            if it and not s.get("na") and s["variant"] not in it.variants():
                r.warn(f"{cid}: варианта {s['item']}/{s['variant']} пока нет")
            if s.get("destination") and s["destination"] not in dests:
                r.error(f"{cid}: адресата {s['destination']} нет")
            if s.get("at") and not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?(Z|[+-]\d{2}:\d{2})", str(s["at"])):
                r.error(f"{cid}: время {s['at']} — нужно ISO 8601 с поясом")
    for it in items.values():
        for t in it.data.get("targets") or []:
            if t.get("destination") and t["destination"] not in dests:
                r.error(f"{it.id}: цель {t['variant']} — адресата {t['destination']} нет")
    r.note(f"кампаний {len(repo.campaigns())}")
    return r


def projects_check(repo: Repo | None = None) -> Report:
    repo = repo or Repo()
    r = Report("projects — сущности проектов")
    facts, links, brands = repo.facts(), repo.links(), repo.brands()
    for pid, p in repo.projects().items():
        if Path(p["_file"]).stem != pid:
            r.error(f"{p['_file']}: id {pid} ≠ имени файла")
        if p.get("brand") not in brands:
            r.error(f"{pid}: бренд {p.get('brand')} не описан")
        ids = [p.get("tagline"), p.get("status")] + [f for row in p.get("facts", []) for f in row["facts"]]
        for fid in [x for x in ids if x]:
            f = facts.get(fid)
            if f is None:
                r.error(f"{pid}: факта {fid} нет в реестре")
            elif not f.get("public") or f.get("class") != "FACT":
                r.error(f"{pid}: {fid} — не публичный FACT")
            elif not (f.get("allowed_wording") or {}).get("ru"):
                r.error(f"{pid}: у {fid} нет допустимой формулировки ru")
        for ref in p.get("links", []):
            lid = ref["id"]
            if lid not in links:
                r.error(f"{pid}: ссылки {lid} нет")
    r.note(f"проектов {len(repo.projects())}")
    return r


def _site_findings(rep, out: Path, r: Report, label: str) -> None:
    for e in rep.errors:
        r.error(f"{label}: {e}")
    for w in sorted(set(rep.warnings)):
        r.warn(f"{label}: {w}")
    for name in ("feed.xml", "rss.xml", "sitemap.xml"):
        try:
            ET.parse(out / name)
        except ET.ParseError as e:
            r.error(f"{label}: {name} — XML не читается: {e}")
    for p in list(out.glob("*.json")) + list(out.glob("api/v1/*.json")):
        try:
            json.loads(p.read_text(encoding="utf-8"))
        except ValueError as e:
            r.error(f"{label}: {p.relative_to(out)} — JSON не читается: {e}")
    for page in out.rglob("*.html"):
        text = page.read_text(encoding="utf-8")
        for href in re.findall(r'(?:href|src)="(/[^"#?]*)"', text):
            target = out / href.lstrip("/")
            if not (target.is_file() or (target / "index.html").is_file()):
                r.error(f"{label}: {page.relative_to(out)} → {href} — нет такого файла")
        rel = page.relative_to(out).as_posix()
        eco = (rep.eco_path or "").strip("/")
        allow = (f"/{eco}/dpvis.js",) if eco and rel.startswith(f"{eco}/") else ()
        for msg in page_a11y(text) + page_csp(text, allow):
            r.error(f"{label}: {page.relative_to(out)}: {msg}")
    headers = out / ".htaccess" if (out / ".htaccess").is_file() else out / "_headers"
    if not headers.is_file() or "Content-Security-Policy" not in headers.read_text(encoding="utf-8"):
        r.error(f"{label}: {headers.name} без Content-Security-Policy")
    if not (out / "favicon.svg").is_file():
        r.error(f"{label}: нет favicon.svg")


def page_a11y(text: str) -> list[str]:
    """Доступность страницы сайта (П2-10): язык, один h1, alt, «к содержимому», текущий раздел."""
    out = []
    if not re.search(r'<html lang="(ru|en)"', text):
        out.append("нет <html lang>")
    h1 = len(re.findall(r"<h1[\s>]", text))
    if h1 != 1:
        out.append(f"заголовков h1 — {h1}, нужен один")
    for img in re.findall(r"<img\b[^>]*>", text):
        alt = re.search(r'\salt="([^"]*)"', img)
        if alt is None or not alt.group(1).strip():
            out.append(f"изображение без alt: {img[:80]}")
    if 'href="#main"' not in text or 'id="main"' not in text:
        out.append("нет ссылки «к содержимому» (#main)")
    for a in re.findall(r'<a\b[^>]*class="current"[^>]*>', text):
        if 'aria-current="page"' not in a:
            out.append("текущий раздел без aria-current")
    return out


def page_csp(text: str, allow_scripts: tuple = ()) -> list[str]:
    """Страница не нарушает CSP из _headers (П2-12): нет встроенных скриптов, стилей и обработчиков.
    allow_scripts — свои внешние скрипты, разрешённые CSP каталога (раздел «Экосистема», Д43)."""
    out = []
    for m in re.finditer(r"<script(?![^>]*type=\"application/ld\+json\")[^>]*>(.*?)(?:</script>|$)", text, re.S):
        src = re.search(r'\ssrc="([^"]+)"', m.group(0))
        if not (src and src.group(1) in allow_scripts and not m.group(1).strip()):
            out.append("встроенный или внешний скрипт — CSP script-src 'none'")
    if re.search(r"<style[\s>]", text) or re.search(r'\sstyle="', text):
        out.append("встроенный стиль — CSP style-src 'self'")
    if re.search(r"\son[a-z]+=\"", text):
        out.append("обработчик on…= — CSP script-src 'none'")
    if (re.search(r'<(?:img|script|iframe|source|video|audio)\b[^>]*\ssrc="https?://', text)
            or re.search(r'<link\b[^>]*rel="(?:stylesheet|preload|icon)"[^>]*href="https?://', text)):
        out.append("внешний ресурс страницы — CSP только 'self'")
    return out


def site_check(repo: Repo | None = None) -> Report:
    repo = repo or Repo()
    r = Report("site — сборка сайта (staging и production)")
    from .site.build import build
    with tempfile.TemporaryDirectory() as tmp:
        for env in ("staging", "production"):
            out = Path(tmp) / env
            rep = build(repo, env, out)
            _site_findings(rep, out, r, env)
            r.note(f"{env}: страниц {len(rep.pages)}, записей и страниц из вариантов {len(rep.entries)}")
            robots = (out / "robots.txt").read_text()
            if env == "staging" and "Disallow: /" not in robots:
                r.error("staging открыт для индексации — нужен Disallow: /")
    dep = ((repo.site().get("environments") or {}).get("production") or {}).get("deploy") or {}
    prod = repo.destinations().get("site-production") or {}
    if dep.get("target_ref") and prod and dep["target_ref"] != prod.get("credential_ref"):
        r.error(f"site.yaml deploy.target_ref {dep['target_ref']} ≠ credential_ref site-production ({prod.get('credential_ref')})")
    return r
