"""Данные репозитория для ядра: одна точка чтения с корнем, который можно подменить в тестах.

Ядро читает всё через Repo, а не через глобальный ROOT: сквозной тест
собирает временный репозиторий из фикстур и прогоняет на нём весь путь
публикации (PLATFORM_VISION §102–103).
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

import yaml

from ..common import ROOT


def fingerprint(obj) -> str:
    """Короткий отпечаток данных (16 hex) — для зависимостей и устаревания (Д28)."""
    raw = json.dumps(obj, ensure_ascii=False, sort_keys=True, default=str)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]


class Repo:
    def __init__(self, root: Path | str = ROOT):
        self.root = Path(root)
        self._cache: dict[str, object] = {}

    def path(self, *parts: str) -> Path:
        return self.root.joinpath(*parts)

    def yaml(self, rel: str, default=None):
        p = self.path(rel)
        if not p.is_file():
            return default
        with open(p, encoding="utf-8") as f:
            return yaml.safe_load(f)

    def _memo(self, key: str, fn):
        if key not in self._cache:
            self._cache[key] = fn()
        return self._cache[key]

    def reset(self) -> None:
        self._cache.clear()

    # ----------------------------------------------------------- данные
    def facts(self) -> dict[str, dict]:
        return self._memo("facts", lambda: {f["id"]: f for f in (self.yaml("data/facts/facts.yaml") or {}).get("facts", [])})

    def links(self) -> dict[str, dict]:
        return self._memo("links", lambda: {l["id"]: l for l in (self.yaml("data/identity/links.yaml") or {}).get("links", [])})

    def brands(self) -> dict[str, dict]:
        return self._memo("brands", lambda: {b["id"]: b for b in (self.yaml("data/identity/brands.yaml") or {}).get("brands", [])})

    def projects(self) -> dict[str, dict]:
        def load():
            out = {}
            for p in sorted(self.path("data/projects").glob("*.yaml")):
                d = yaml.safe_load(p.read_text(encoding="utf-8")) or {}
                d["_file"] = str(p.relative_to(self.root))
                out[d.get("id", p.stem)] = d
            return out
        return self._memo("projects", load)

    def destinations(self) -> dict[str, dict]:
        return self._memo("dest", lambda: {d["id"]: d for d in (self.yaml("data/platforms/destinations.yaml") or {}).get("destinations", [])})

    def capabilities(self) -> dict[str, dict]:
        return self._memo("caps", lambda: {p["id"]: p for p in (self.yaml("data/platforms/capabilities.yaml") or {}).get("platforms", [])})

    def policy(self) -> dict:
        return self._memo("policy", lambda: self.yaml("data/policy/publication.yaml", {}) or {})

    def site(self) -> dict:
        return self._memo("site", lambda: self.yaml("data/website/site.yaml", {}) or {})

    def releases(self) -> dict[str, list[dict]]:
        def load():
            out = {}
            for p in sorted(self.path("data/releases").glob("*.yaml")):
                d = yaml.safe_load(p.read_text(encoding="utf-8")) or {}
                out[d.get("project", p.stem)] = d.get("releases", [])
            return out
        return self._memo("releases", load)

    def campaigns(self) -> dict[str, dict]:
        def load():
            out = {}
            for p in sorted(self.path("data/campaigns").glob("CMP-*.yaml")):
                d = yaml.safe_load(p.read_text(encoding="utf-8")) or {}
                d["_file"] = str(p.relative_to(self.root))
                out[d.get("id", p.stem)] = d
            return out
        return self._memo("campaigns", load)

    def site_base(self, environment: str) -> str:
        envs = (self.site().get("environments") or {})
        return (envs.get(environment) or {}).get("base_url") or ""

    def canonical_base(self) -> str:
        """Канонический адрес сайта (production) — для ссылок из вариантов площадок (§79)."""
        return self.site().get("canonical_base") or self.site_base("production")
