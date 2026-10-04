"""Адаптер сайта (Д21, Д32): «публикация» = сборка сайта с записью и выгрузка на хостинг.

Окружения адресата: staging — локальная сборка build/site/staging (это и
есть staging-сервер, `site serve`); production — сборка build/site/production
и команда выгрузки из site.yaml (`deploy`). Пока хостинг не выбран
(ОР-11), выгрузки нет — рабочая публикация на сайт даёт MANUAL_REQUIRED, а
готовая папка ждёт автора.
"""
from __future__ import annotations

import subprocess

from ..common import Finding
from ..core import markup
from ..core.errors import PublishError
from .base import Identity, Payload, PlatformAdapter, RemoteRef, RemoteStatus


class WebsiteAdapter(PlatformAdapter):
    platform = "website"

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
        return [] if want == have else [f"адрес сайта адресата {want} ≠ site.yaml ({have})"]

    def _build(self, include: tuple):
        from ..site.build import build
        rep = build(self.repo, self.site_env(), self.out_dir(), include=include)
        if rep.errors:
            raise PublishError("INVALID_CONTENT", rep.errors[0])
        return rep

    def _deploy(self) -> None:
        cfg = ((self.repo.site().get("environments") or {}).get(self.site_env()) or {}).get("deploy")
        if not cfg:
            if self.env() == "production":
                raise PublishError("MANUAL_REQUIRED", f"сборка готова в {self.out_dir()}; выгрузка на хостинг не настроена (ОР-11)")
            return  # staging — локальная сборка и есть сервер
        r = subprocess.run(cfg["command"], cwd=self.repo.root, capture_output=True, text=True)
        if r.returncode != 0:
            raise PublishError("PLATFORM_UNAVAILABLE", f"выгрузка: код {r.returncode}")

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
        return RemoteStatus(page.is_file(), ref.remote_id, ref.remote_url)
