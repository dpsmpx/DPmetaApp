"""Временный репозиторий для тестов платформы: настоящие данные + тестовая новость и адресаты.

Новость CI-0101 (PROJECT, galdhold): канон v1 с подстановками фактов,
варианты website.ru и telegram.ru. Адресаты — тестовые: рабочие и
staging, у Telegram — явные id канала и аккаунта имитации. Репозиторий
инициализирован в Git — проверка «канон сохранён в Git» (§108) работает.
"""
from __future__ import annotations

import shutil
import subprocess
import tempfile
from pathlib import Path

import yaml

from tools.cs.common import ROOT
from tools.cs.core import textdoc
from tools.cs.core.content import approval_state, find, seal_canon, seal_variant, variant_sha
from tools.cs.core.repo import Repo

COPY = [
    "data/facts", "data/identity", "data/platforms/capabilities.yaml", "data/platforms/export_specs.yaml",
    "data/policy", "data/projects", "data/provenance/assets.yaml", "assets/brand", "website",
]

DESTINATIONS = {
    "schema": "destinations/v1",
    "destinations": [
        {"id": "site-production", "platform": "website", "environment": "production", "accepts_brands": ["author", "galdhold", "dplang"],
         "target": {"id": "dpsmpx.com", "base_url": "https://dpsmpx.com"}, "credential_ref": None,
         "staging_twin": "site-staging", "status": "active"},
        {"id": "site-staging", "platform": "website", "environment": "staging", "accepts_brands": ["author", "galdhold", "dplang"],
         "target": {"id": "staging", "base_url": "http://localhost:8000"}, "credential_ref": None, "status": "active"},
        {"id": "tg-prod", "platform": "telegram", "environment": "production", "accepts_brands": ["author", "galdhold"],
         "language": "ru", "target": {"id": "-1001234567890", "username": "test_devlog"}, "account": {"id": "mock-account"},
         "credential_ref": "CS_PROD_TELEGRAM_BOT_TOKEN", "staging_twin": "tg-test", "status": "active"},
        {"id": "tg-test", "platform": "telegram", "environment": "staging", "accepts_brands": ["author", "galdhold", "dplang"],
         "language": "ru", "target": {"id": "-1009999999999"}, "account": {"id": "mock-account"},
         "credential_ref": "CS_STAGING_TELEGRAM_BOT_TOKEN", "status": "active"},
        {"id": "tg-dplang", "platform": "telegram", "environment": "production", "accepts_brands": ["dplang"],
         "language": "ru", "target": {"id": "-1005555555555"}, "account": {"id": "mock-account"},
         "credential_ref": "CS_PROD_TELEGRAM_DPLANG_TOKEN", "status": "active"},
        {"id": "vk-prod", "platform": "vk", "environment": "production", "accepts_brands": ["author", "galdhold"],
         "language": "ru", "target": {"id": "123"}, "credential_ref": None, "status": "active"},
    ],
}

SITE = {
    "schema": "site/v1", "title": {"ru": "Тест", "en": "Test"}, "canonical_base": "https://dpsmpx.com",
    "languages": ["ru", "en"],
    "environments": {"staging": {"base_url": "http://localhost:8000", "noindex": True, "drafts": True, "deploy": None},
                     "production": {"base_url": "https://dpsmpx.com", "noindex": False, "drafts": False, "deploy": None}},
    "home": {"projects": ["galdhold", "dplang"], "social_links": [{"link": "L-TG-DEVLOG", "ru": "дневник", "en": "dev log"}],
             "contact": "L-TG-AUTHOR"},
    "og_default": None,
}

CANON = """Новая сборка {{fact:GH-NAME}}: {{fact:GH-GENRE}}.

## Что внутри

- {{fact:GH-TECH}}, {{fact:GH-ENGINE}}
- {{fact:GH-LOC}}

Дневник разработки — {{link:L-TG-DEVLOG}}.
"""

TG = """**{{fact:GH-NAME}}** — {{fact:GH-GENRE}}.

{{fact:GH-TECH}}, {{fact:GH-ENGINE}}; в проекте {{fact:GH-LOC}}.

Подробнее — на сайте."""


def make_repo(git: bool = True) -> Repo:
    tmp = Path(tempfile.mkdtemp(prefix="cs-platform-"))
    for rel in COPY:
        src = ROOT / rel
        dst = tmp / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        (shutil.copytree if src.is_dir() else shutil.copyfile)(src, dst)
    (tmp / "data/platforms/destinations.yaml").write_text(yaml.safe_dump(DESTINATIONS, allow_unicode=True), encoding="utf-8")
    (tmp / "data/website").mkdir(parents=True, exist_ok=True)
    (tmp / "data/website/site.yaml").write_text(yaml.safe_dump(SITE, allow_unicode=True), encoding="utf-8")
    item = tmp / "content/items/CI-0101-test-news"
    item.mkdir(parents=True)
    data = {
        "schema": "content_item/v2", "id": "CI-0101", "slug": "test-news", "title": "Тестовая новость",
        "type": "news", "pillar": "RESULT", "category": "news", "tags": ["galdhold"],
        "brand": {"mode": "PROJECT", "brands": ["galdhold"]},
        "objective": "Проверить путь публикации от канона до площадок",
        "audiences": ["players"], "platforms": ["website", "telegram"], "languages": ["ru"],
        "claims": [{"fact": f} for f in ["GH-NAME", "GH-GENRE", "GH-TECH", "GH-ENGINE", "GH-LOC"]],
        "source_projects": ["galdhold"], "created": "2026-10-04",
        "canonical": {"lang": "ru", "current": 1, "versions": [{"version": 1, "file": "canonical/v1.md", "date": "2026-10-04"}]},
        "targets": [{"variant": "website.ru", "destination": "site-production"}, {"variant": "telegram.ru", "destination": "tg-prod"}],
        "state": "IDEA", "history": [{"date": "2026-10-04", "state": "IDEA", "note": "тестовый объект"}],
    }
    (item / "item.yaml").write_text(yaml.safe_dump(data, allow_unicode=True, sort_keys=False), encoding="utf-8")
    textdoc.save(textdoc.TextDoc({"schema": "canonical/v1", "item": "CI-0101", "version": 1, "lang": "ru",
                                  "date": "2026-10-04", "title": "Новая сборка Galdhold"}, CANON), item / "canonical/v1.md")
    textdoc.save(textdoc.TextDoc({"schema": "variant/v1", "item": "CI-0101", "platform": "website", "lang": "ru", "format": "post",
                                  "source_version": 1, "version": 1, "status": "draft", "title": "Новая сборка Galdhold",
                                  "summary": "Что вошло в сборку"}, CANON), item / "variants/website.ru.md")
    textdoc.save(textdoc.TextDoc({"schema": "variant/v1", "item": "CI-0101", "platform": "telegram", "lang": "ru", "format": "post",
                                  "source_version": 1, "version": 1, "status": "draft", "link": "canonical"}, TG),
                 item / "variants/telegram.ru.md")
    repo = Repo(tmp)
    if git:
        git_commit(repo, "fixture")
    return repo


def git_commit(repo: Repo, msg: str) -> None:
    run = lambda *a: subprocess.run(["git", *a], cwd=repo.root, capture_output=True, check=True)  # noqa: E731
    if not (repo.root / ".git").exists():
        run("init", "-q")
        run("config", "user.email", "test@example.com")
        run("config", "user.name", "test")
    run("add", "-A")
    run("commit", "-q", "-m", msg, "--allow-empty")


def seal_all(repo: Repo, item_id: str = "CI-0101") -> None:
    item = find(repo, item_id)
    cv = item.canon()
    seal_canon(item, cv)
    from tools.cs.core.content import save_item
    save_item(item)
    item = find(repo, item_id)
    for v in item.variants().values():
        seal_variant(v)
        v.doc.meta.update(status="ready", trust="validated")
        textdoc.save(v.doc, v.path)


def approve(repo: Repo, item_id: str, variant: str) -> None:
    """Одобрение автора — так его пишет `approve` (тест имитирует действие автора)."""
    v = find(repo, item_id).variants()[variant]
    v.doc.meta["approval"] = {"by": "author", "date": "2026-10-04", "sha256": variant_sha(v)}
    textdoc.save(v.doc, v.path)
    assert approval_state(find(repo, item_id).variants()[variant]) == "valid"


def ready_repo() -> Repo:
    repo = make_repo(git=True)
    seal_all(repo)
    approve(repo, "CI-0101", "website.ru")
    approve(repo, "CI-0101", "telegram.ru")
    git_commit(repo, "sealed and approved")
    repo.reset()
    return repo
