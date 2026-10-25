"""Наблюдаемость (PLATFORM_VISION §106): структурные записи с вычисткой секретов.

Пишет JSON по строке в build/logs/<имя>.jsonl (не в Git): id публикации и
кампании, площадку, попытку, задержку, код ошибки, метаданные ответа.
Перед записью каждое строковое значение проходит тот же сканер, что
проверка `secrets`: найденное заменяется на «[скрыто]».
"""
from __future__ import annotations

import datetime as _dt
import json
import os
import re

from ..secrets import PATTERNS, WARN_PATTERNS
from .repo import Repo

_TOKEN_URL = re.compile(r"/bot\d+:[A-Za-z0-9_-]+")


def redact(value):
    if isinstance(value, str):
        s = _TOKEN_URL.sub("/bot[скрыто]", value)
        for _, pat in PATTERNS + WARN_PATTERNS:
            s = pat.sub("[скрыто]", s)
        return s
    if isinstance(value, dict):
        return {k: ("[скрыто]" if re.search(r"token|secret|password|cookie|authorization", k, re.I) else redact(v))
                for k, v in value.items()}
    if isinstance(value, list):
        return [redact(v) for v in value]
    return value


def is_ai_session() -> bool:
    """Запуск из сессии Claude Code (Д26): одобрение и рабочая публикация отсюда запрещены
    (исключение — выгрузка сайта из локальной сессии, Д36)."""
    return bool(os.environ.get("CLAUDECODE") or os.environ.get("CLAUDE_CODE_REMOTE"))


def is_remote_session() -> bool:
    """Облачная сессия Claude Code: рабочих ключей нет, выгрузка невозможна и запрещена (Д36)."""
    return bool(os.environ.get("CLAUDE_CODE_REMOTE"))


def actor() -> str:
    if is_ai_session():
        return "session"
    return os.environ.get("CS_ACTOR", "author")


def log(repo: Repo, name: str, record: dict) -> None:
    rec = {"ts": _dt.datetime.now(_dt.timezone.utc).isoformat(timespec="seconds"), **record}
    p = repo.path("build", "logs", f"{name}.jsonl")
    p.parent.mkdir(parents=True, exist_ok=True)
    with open(p, "a", encoding="utf-8") as f:
        f.write(json.dumps(redact(rec), ensure_ascii=False) + "\n")
