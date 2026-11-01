"""Кампании, сводка по площадкам, календарь и поиск (PLATFORM_VISION §12, §28, §76–77).

Всё здесь — производные виды над моделью и журналом, а не отдельные
данные: календарь строится из расписаний кампаний и журнала, сводка —
из целей объектов (targets), вариантов и журнала (§76).
"""
from __future__ import annotations

from dataclasses import dataclass

from .content import CItem, approval_state, find, load_items, stale_reasons
from .ledger import Ledger
from .repo import Repo


@dataclass
class Row:
    item: str
    variant: str
    platform: str
    destination: str | None
    status: str
    detail: str = ""
    at: str | None = None


def targets(item: CItem, campaign: dict | None = None) -> list[dict]:
    """Цели публикации объекта: item.targets + расписание кампании (at)."""
    out = {t["variant"]: dict(t) for t in item.data.get("targets") or []}
    for s in (campaign or {}).get("schedule") or []:
        if s.get("item") == item.id:
            out.setdefault(s["variant"], {"variant": s["variant"]}).update({k: v for k, v in s.items() if k != "item"})
    return list(out.values())


def target_status(repo: Repo, item: CItem, t: dict) -> Row:
    vid = t["variant"]
    platform = vid.split(".")[0].split("-")[0]
    dest = t.get("destination")
    if t.get("na"):
        return Row(item.id, vid, platform, dest, "не применимо", t["na"], t.get("at"))
    v = item.variants().get(vid)
    if v is None:
        return Row(item.id, vid, platform, dest, "нет варианта", f"variants/{vid}.md", t.get("at"))
    d = repo.destinations().get(dest or "")
    for env in ("production", "staging"):
        led = Ledger(repo, env)
        dest_ids = {dest} | ({(d or {}).get("staging_twin")} if env == "staging" else set())
        pubs = [p for p in led.find(item=item.id, variant=vid) if p.first.get("destination") in dest_ids]
        if pubs:
            p = pubs[-1]
            label = {"PUBLISHED": "опубликовано", "SCHEDULED": "запланировано", "FAILED": "сбой", "UNKNOWN": "исход неизвестен",
                     "PUBLISHING": "публикуется", "RETRY": "повтор", "SUPERSEDED": "заменено новой версией",
                     "DELETED": "удалено", "ARCHIVED": "в архиве", "CANCELLED": "отменено"}[p.state]
            if env == "staging":
                label += " (staging)"
            detail = p.remote.get("remote_url") or (p.events[-1].get("error") or {}).get("code") or p.get("scheduled_for") or ""
            if p.state in ("PUBLISHED", "SCHEDULED") or env == "production":
                return Row(item.id, vid, platform, dest, label, str(detail), t.get("at"))
    st = approval_state(v)
    stale = stale_reasons(v)
    if stale:
        return Row(item.id, vid, platform, dest, "устарело", "; ".join(stale), t.get("at"))
    if st == "valid":
        if d is None:
            return Row(item.id, vid, platform, dest, "одобрено — ручной путь", "адресат не задан", t.get("at"))
        if d.get("status") != "active":
            return Row(item.id, vid, platform, dest, "одобрено, ждёт адресата", f"{dest}: {d.get('status')} {', '.join(d.get('blocked_by') or [])}".strip(), t.get("at"))
        return Row(item.id, vid, platform, dest, "одобрено, к публикации", "", t.get("at"))
    if st == "invalid":
        return Row(item.id, vid, platform, dest, "изменено после одобрения", "нужно новое одобрение", t.get("at"))
    return Row(item.id, vid, platform, dest, "ждёт одобрения автора" if v.m("status") == "ready" else "черновик",
               f"доверие: {v.m('trust', 'unverified')}", t.get("at"))


def campaign_rows(repo: Repo, cid: str) -> list[Row]:
    c = repo.campaigns().get(cid)
    if c is None:
        raise KeyError(f"кампании {cid} нет в data/campaigns/")
    rows = []
    for iid in c.get("items", []):
        item = find(repo, iid)
        for t in targets(item, c):
            rows.append(target_status(repo, item, t))
    return rows


def calendar(repo: Repo) -> list[Row]:
    """Календарь (§76): даты из расписаний кампаний и журнала, а не отдельная таблица."""
    rows = []
    for cid, c in repo.campaigns().items():
        for s in c.get("schedule") or []:
            if s.get("at"):
                item = find(repo, s["item"])
                r = target_status(repo, item, {**s})
                r.detail = f"{cid} · {r.detail}".strip(" ·")
                rows.append(r)
    for env in ("production", "staging"):
        for p in Ledger(repo, env).publications().values():
            ev = p.last("PUBLISHED") or p.last("SCHEDULED")
            if ev:
                f = p.first
                rows.append(Row(f["item"], f["variant"], f.get("platform", ""), f.get("destination"),
                                p.state.lower() + ("" if env == "production" else " (staging)"),
                                p.remote.get("remote_url") or "", ev.get("scheduled_for") or ev.get("ts")))
    return sorted(rows, key=lambda r: str(r.at or ""))


def search(repo: Repo, *, project=None, tag=None, platform=None, type_=None, campaign=None, status=None,
           date_from=None, date_to=None) -> list[CItem]:
    """Поиск объектов (§77) по проекту, метке, площадке, типу, кампании, состоянию и дате."""
    out = []
    for it in load_items(repo):
        d = it.data
        if project and project not in (d.get("source_projects") or []):
            continue
        if tag and tag not in (d.get("tags") or []):
            continue
        if platform and platform not in (d.get("platforms") or []) and not any(v.platform == platform for v in it.variants().values()):
            continue
        if type_ and type_ not in (d.get("type"), d.get("category")):
            continue
        if campaign and d.get("campaign") != campaign:
            continue
        if status and status != d.get("state"):
            continue
        created = str(d.get("created") or "")
        if date_from and created < date_from:
            continue
        if date_to and created > date_to:
            continue
        out.append(it)
    return out
