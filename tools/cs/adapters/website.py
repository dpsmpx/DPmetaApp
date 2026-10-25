"""Адаптер сайта (Д21, Д32, Д35): «публикация» = сборка сайта с записью и выгрузка на хостинг.

Окружения адресата: staging — локальная сборка build/site/staging (это и
есть staging-сервер, `site serve`); production — сборка build/site/production
и выгрузка из site.yaml (`deploy`: rsync на Beget, tools/cs/site/deploy.py),
затем проверка страницы по HTTP. Нет настройки выгрузки — MANUAL_REQUIRED, а
готовая папка ждёт автора; запреты выгрузки (Д34 `live`, адрес, вид сессии)
— в check_identity, до записи в журнал.
"""
from __future__ import annotations

import subprocess
import sys
import urllib.request

from ..common import Finding
from ..core import markup
from ..core.errors import PublishError
from ..site import deploy
from .base import Identity, Payload, PlatformAdapter, RemoteRef, RemoteStatus


class WebsiteAdapter(PlatformAdapter):
    platform = "website"
    runner = staticmethod(subprocess.run)   # выгрузка (тесты подменяют)
    fetch = staticmethod(urllib.request.urlopen)   # проверка по HTTP (тесты подменяют)

    @classmethod
    def default_format(cls, has_media: bool, category: str | None = None) -> str:
        return "page" if category == "page" else "post"

    def env(self) -> str:
        return self.destination.get("environment") or "staging"

    def site_env(self) -> str:
        return "staging" if self.env() == "development" else self.env()

    def out_dir(self):
        return self.repo.path("build", "site", self.env())

    def render_text(self, blocks: list, base: str) -> tuple[str, int]:
        text = markup.to_html(blocks)
        return text, markup.visible_length(text)

    def validate(self, p: Payload) -> list[Finding]:
        out = super().validate(p)
        if not p.title:
            out.append(Finding("error", "у страницы сайта нет title"))
        if p.visible_len == 0:
            out.append(Finding("error", "пустой текст страницы"))
        return out

    def identify(self) -> Identity:
        base = self.repo.site_base(self.site_env())
        return Identity(None, str((self.destination.get("target") or {}).get("id") or ""), base)

    def check_identity(self) -> list[str]:
        want = ((self.destination.get("target") or {}).get("base_url") or "").rstrip("/")
        have = self.repo.site_base(self.site_env()).rstrip("/")
        out = [] if want == have else [f"адрес сайта адресата {want} ≠ site.yaml ({have})"]
        if self.env() == "production" and deploy.config(self.repo, "production"):
            out += deploy.guard(self.repo, "production", self.credentials)
        return out

    def _build(self, include: tuple):
        from ..site.build import build
        rep = build(self.repo, self.site_env(), self.out_dir(), include=include)
        if rep.errors:
            raise PublishError("INVALID_CONTENT", rep.errors[0])
        self._report = rep
        return rep

    def _deploy(self) -> None:
        if not deploy.config(self.repo, self.site_env()):
            if self.env() == "production":
                raise PublishError("MANUAL_REQUIRED", f"сборка готова в {self.out_dir()}; выгрузка на хостинг не настроена (Д35)")
            return  # staging — локальная сборка и есть сервер
        from ..mark.release import objects
        rep = getattr(self, "_report", None)
        res = deploy.run(self.repo, self.out_dir(), self.site_env(), store=self.credentials, runner=self.runner,
                         objects=objects(rep.entries) if rep else None)
        if res.ok:
            for msg in res.problems:  # сайт выгружен, но манифест выпуска не записан (Д41) — не молча
                print(f"  ✗ {msg}", file=sys.stderr)
        else:
            # rsync запускался и упал — сеть или сервер; rsync идемпотентен, повтор безопасен.
            # Не запускался — запрет выгрузки (обычно его ловит check_identity раньше).
            raise PublishError("PLATFORM_UNAVAILABLE" if res.command else "PERMISSION_DENIED", "; ".join(res.problems))

    def _ref(self, p: Payload) -> RemoteRef:
        page = self.out_dir() / p.url_path.lstrip("/") / "index.html"
        if not page.is_file():
            raise PublishError("UNKNOWN_PLATFORM_ERROR", f"страница {p.url_path} не собрана")
        return RemoteRef(p.url_path, self.repo.site_base(self.site_env()).rstrip("/") + p.url_path)

    def publish(self, p: Payload, key: str) -> RemoteRef:
        self._build(((p.item, p.variant),))
        ref = self._ref(p)
        self._deploy()
        return ref

    def edit(self, ref: RemoteRef, p: Payload) -> RemoteRef:
        return self.publish(p, "")

    def delete(self, ref: RemoteRef) -> None:
        self._build(())
        self._deploy()

    def get_status(self, ref: RemoteRef | None = None, key: str | None = None) -> RemoteStatus:
        if ref is None or not ref.remote_id:
            return RemoteStatus(None)
        page = self.out_dir() / str(ref.remote_id).lstrip("/") / "index.html"
        if not page.is_file():
            return RemoteStatus(False, ref.remote_id, ref.remote_url)
        if self.env() == "production" and deploy.config(self.repo, "production"):
            # страница на хостинге: наша (canonical) — True, 404 — False, не дошли — None
            seen = deploy.verify(self.repo.site_base("production"), [str(ref.remote_id)], self.fetch)
            return RemoteStatus(seen[str(ref.remote_id)], ref.remote_id, ref.remote_url)
        return RemoteStatus(True, ref.remote_id, ref.remote_url)
