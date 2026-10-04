"""Журнал публикаций: только дописывание, по строке JSON на событие (Д24, §47, §88).

    data/publications/production.jsonl   рабочие публикации (в Git)
    data/publications/staging.jsonl      безопасный режим: тестовые адресаты (в Git)
    build/publications/development.jsonl имитации площадок (не в Git)

Состояние публикации — последнее событие по её id. Состояния публикации
отделены от состояний контента: «одобрено» — свойство варианта,
«опубликовано» — событие здесь (§14).
"""
from __future__ import annotations

import datetime as _dt
import hashlib
import json
from dataclasses import dataclass, field
from pathlib import Path

from .repo import Repo

EVENTS = ["SCHEDULED", "PUBLISHING", "PUBLISHED", "FAILED", "RETRY", "UNKNOWN",
          "SUPERSEDED", "ARCHIVED", "DELETED", "CANCELLED"]
TERMINAL_OK = {"PUBLISHED", "SUPERSEDED", "ARCHIVED", "DELETED"}

# допустимые переходы по событиям одной публикации
NEXT: dict[str | None, set[str]] = {
    None: {"SCHEDULED", "PUBLISHING", "PUBLISHED"},          # PUBLISHED первым — ручная публикация
    "SCHEDULED": {"SCHEDULED", "PUBLISHING", "CANCELLED"},
    "PUBLISHING": {"PUBLISHED", "FAILED", "UNKNOWN"},
    "FAILED": {"RETRY", "PUBLISHING", "CANCELLED"},
    "RETRY": {"PUBLISHING", "CANCELLED"},
    "UNKNOWN": {"PUBLISHED", "FAILED", "CANCELLED"},
    "PUBLISHED": {"SUPERSEDED", "DELETED", "ARCHIVED"},
    "SUPERSEDED": {"ARCHIVED", "DELETED"},
    "DELETED": {"ARCHIVED"},
    "ARCHIVED": set(),
    "CANCELLED": set(),
}

LEDGER_FILES = {
    "production": "data/publications/production.jsonl",
    "staging": "data/publications/staging.jsonl",
    "development": "build/publications/development.jsonl",
}


def idempotency_key(item: str, variant: str, variant_sha: str, destination: str, environment: str) -> str:
    """Один и тот же вариант с тем же содержимым в тот же адресат и окружение — один ключ (§16)."""
    raw = "|".join([item, variant, variant_sha, destination, environment])
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def publication_id(key: str) -> str:
    return "PUB-" + key[:12]


def now_iso() -> str:
    return _dt.datetime.now(_dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


@dataclass
class Publication:
    id: str
    events: list[dict] = field(default_factory=list)

    @property
    def state(self) -> str:
        return self.events[-1]["event"]

    @property
    def first(self) -> dict:
        return self.events[0]

    def last(self, event: str) -> dict | None:
        return next((e for e in reversed(self.events) if e["event"] == event), None)

    @property
    def attempts(self) -> int:
        return sum(1 for e in self.events if e["event"] == "PUBLISHING")

    @property
    def remote(self) -> dict:
        e = self.last("PUBLISHED") or {}
        return {"remote_id": e.get("remote_id"), "remote_url": e.get("remote_url")}

    def get(self, key: str, default=None):
        for e in reversed(self.events):
            if e.get(key) is not None:
                return e[key]
        return default


class Ledger:
    def __init__(self, repo: Repo, environment: str, path: Path | None = None):
        self.repo, self.environment = repo, environment
        self.path = path or repo.path(LEDGER_FILES[environment])

    def records(self) -> list[dict]:
        if not self.path.is_file():
            return []
        out = []
        for n, line in enumerate(self.path.read_text(encoding="utf-8").splitlines(), 1):
            if line.strip():
                rec = json.loads(line)
                rec["_line"] = n
                out.append(rec)
        return out

    def append(self, rec: dict) -> dict:
        rec = {k: v for k, v in rec.items() if v is not None and not k.startswith("_")}
        rec.setdefault("schema", "publication/v1")
        rec.setdefault("ts", now_iso())
        rec.setdefault("environment", self.environment)
        if rec.get("event") not in EVENTS:
            raise ValueError(f"событие {rec.get('event')} не из {EVENTS}")
        pubs = self.publications()
        prev = pubs.get(rec["publication_id"])
        state = prev.state if prev else None
        if rec["event"] not in NEXT[state]:
            raise ValueError(f"{rec['publication_id']}: переход {state} → {rec['event']} недопустим")
        if prev:  # поля публикации наследуются от первого события
            for k in ("idempotency_key", "item", "variant", "platform", "destination", "campaign"):
                rec.setdefault(k, prev.first.get(k))
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with open(self.path, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False, sort_keys=False) + "\n")
        return rec

    def publications(self) -> dict[str, Publication]:
        out: dict[str, Publication] = {}
        for r in self.records():
            out.setdefault(r["publication_id"], Publication(r["publication_id"])).events.append(r)
        return out

    def by_key(self, key: str) -> Publication | None:
        return self.publications().get(publication_id(key))

    def find(self, item: str | None = None, variant: str | None = None, destination: str | None = None,
             states: set[str] | None = None) -> list[Publication]:
        out = []
        for p in self.publications().values():
            f = p.first
            if item and f.get("item") != item:
                continue
            if variant and f.get("variant") != variant:
                continue
            if destination and f.get("destination") != destination:
                continue
            if states and p.state not in states:
                continue
            out.append(p)
        return out


def integrity(ledger: Ledger) -> tuple[list[str], list[str]]:
    """(ошибки, предупреждения) журнала: переходы, время, дубли, обязательные поля."""
    errors, warnings = [], []
    state: dict[str, str | None] = {}
    published_remote: dict[tuple, str] = {}
    last_ts = ""
    name = ledger.path.name
    for r in ledger.records():
        where = f"{name}:{r['_line']}"
        pid = r.get("publication_id")
        ev = r.get("event")
        if ev not in EVENTS:
            errors.append(f"{where}: неизвестное событие {ev}")
            continue
        ts = r.get("ts", "")
        if ts < last_ts:
            errors.append(f"{where}: время идёт назад ({ts} < {last_ts})")
        last_ts = max(last_ts, ts)
        if r.get("environment") != ledger.environment:
            errors.append(f"{where}: запись окружения {r.get('environment')} в журнале {ledger.environment}")
        prev = state.get(pid)
        if ev not in NEXT[prev]:
            errors.append(f"{where}: {pid} — переход {prev} → {ev} недопустим")
        state[pid] = ev
        if ev == "PUBLISHED":
            if not (r.get("remote_id") or r.get("remote_url")):
                errors.append(f"{where}: {pid} PUBLISHED без remote_id и remote_url")
            if ledger.environment == "production" and (r.get("approval") or {}).get("by") != "author":
                errors.append(f"{where}: {pid} — рабочая публикация без одобрения автора (Д12)")
            rk = (r.get("destination"), str(r.get("remote_id") or r.get("remote_url")))
            if r.get("action") != "edit" and rk in published_remote and published_remote[rk] != pid:
                errors.append(f"{where}: {pid} — тот же пост, что у {published_remote[rk]} (дубль)")
            published_remote[rk] = pid
    for pid, ev in state.items():
        if ev in ("PUBLISHING", "UNKNOWN"):
            warnings.append(f"{name}: {pid} — исход неизвестен ({ev}): сверить на площадке, затем publish --reconcile")
    return errors, warnings
