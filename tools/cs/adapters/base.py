"""Интерфейс адаптера площадки (Д23, PLATFORM_VISION §29).

Ядро не пишет `if telegram`: всё, что зависит от площадки, — здесь и в
подклассах: как отрисовать вариант (render), какие лимиты (validate),
от чьего имени и куда идёт запрос (identify), публикация, правка,
удаление, загрузка медиа, проверка статуса. Лимиты берутся из
data/platforms/capabilities.yaml, частота и повторы — у каждого
адаптера свои (§57).
"""
from __future__ import annotations

import mimetypes
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from ..common import Finding, image_size, sha256_file
from ..core import markup
from ..core.content import Variant, link_url, media_files, resolved_body
from ..core.credentials import EnvCredentialStore
from ..core.errors import PublishError
from ..core.repo import Repo
from ..core.retry import RateLimiter, RetryPolicy


@dataclass
class MediaFile:
    ref: str
    path: Path
    sha256: str | None
    size: int
    mime: str
    width: int | None = None
    height: int | None = None


@dataclass
class Payload:
    platform: str
    lang: str
    format: str
    text: str                      # уже в разметке площадки
    visible_len: int               # длина «после разбора разметки»
    title: str | None = None
    link: str | None = None
    media: list[MediaFile] = field(default_factory=list)
    cta: str | None = None
    blocks: list = field(default_factory=list, repr=False)
    errors: list[str] = field(default_factory=list)  # ошибки подстановки фактов
    item: str | None = None
    variant: str | None = None
    url_path: str | None = None


@dataclass
class RemoteRef:
    remote_id: str | None
    remote_url: str | None
    pending: bool = False          # площадка отложила публикацию сама (Telegram: message_id 0)
    meta: dict = field(default_factory=dict)


@dataclass
class Identity:
    account_id: str | None
    target_id: str | None
    title: str | None = None


@dataclass
class RemoteStatus:
    exists: bool | None            # None — площадка не позволяет проверить
    remote_id: str | None = None
    remote_url: str | None = None


def media_of(v: Variant) -> list[MediaFile]:
    out = []
    for ref, p in media_files(v):
        if not p.is_file():
            out.append(MediaFile(ref, p, None, 0, "application/octet-stream"))
            continue
        w = h = None
        try:
            w, h, _ = image_size(p)
        except ValueError:
            pass
        out.append(MediaFile(ref, p, sha256_file(p), p.stat().st_size,
                             mimetypes.guess_type(p.name)[0] or "application/octet-stream", w, h))
    return out


class PlatformAdapter:
    platform = "base"
    automated = True               # False — ручной путь (Д33): publish даёт MANUAL_REQUIRED
    min_interval_s = 0.0           # ограничитель частоты по умолчанию

    def __init__(self, destination: dict, repo: Repo, credentials: EnvCredentialStore | None = None, *,
                 transport: Callable | None = None, clock: Callable[[], float] = time.monotonic,
                 sleep: Callable[[float], None] = time.sleep, retry: RetryPolicy | None = None):
        self.destination, self.repo = destination, repo
        self.credentials = credentials or EnvCredentialStore()
        self.transport = transport
        self.clock, self.sleep = clock, sleep
        self.retry_policy = retry or RetryPolicy.from_policy(repo.policy().get("retry"))
        self.rate_limiter = RateLimiter(self.min_interval_s, clock, sleep)

    # ------------------------------------------------------- данные площадки
    def caps(self) -> dict:
        return self.repo.capabilities().get(self.platform) or {}

    def limits(self) -> dict:
        return self.caps().get("limits") or {}

    def link_base(self) -> str:
        """База ссылок на сайт: канонический адрес; у staging-адресатов — адрес staging."""
        env = self.destination.get("environment")
        if env in ("staging", "development"):
            return self.repo.site_base("staging") or self.repo.canonical_base()
        return self.repo.canonical_base()

    # ------------------------------------------------------- отрисовка и лимиты
    def render_text(self, blocks: list, base: str) -> tuple[str, int]:
        text = markup.to_plain(blocks, base)
        return text, len(text)

    def render(self, v: Variant) -> Payload:
        base = self.link_base()
        res = resolved_body(v, base)
        blocks = markup.parse(res.text)
        text, n = self.render_text(blocks, base)
        return Payload(self.platform, v.lang, v.m("format") or "post", text, n, v.m("title"),
                       link_url(v, base), media_of(v), v.m("cta"), blocks, list(res.errors),
                       v.item.id, v.id, v.item.url_path(v.lang))

    def validate(self, p: Payload) -> list[Finding]:
        out = [Finding("error", e) for e in p.errors]
        for m in p.media:
            if m.sha256 is None:
                out.append(Finding("error", f"медиа {m.ref}: файла нет"))
        return out

    # ------------------------------------------------------- сеть (переопределяют адаптеры)
    def credential(self):
        ref = self.destination.get("credential_ref")
        val = self.credentials.get(ref)
        if not val:
            raise PublishError("MISSING_CREDENTIALS", f"нет {ref} в окружении")
        return val

    def identify(self) -> Identity:
        raise PublishError("UNSUPPORTED", f"{self.platform}: проверка аккаунта не реализована")

    def check_identity(self) -> list[str]:
        """Сверить, от чьего имени и куда пойдёт запрос, с явными id адресата (§51–52)."""
        ident = self.identify()
        want_t = str((self.destination.get("target") or {}).get("id") or "")
        want_a = str((self.destination.get("account") or {}).get("id") or "")
        out = []
        if not want_t:
            out.append("у адресата нет target.id — адресат не подключён")
        elif str(ident.target_id) != want_t:
            out.append(f"адресат площадки {ident.target_id} ≠ записанному {want_t}")
        if want_a and str(ident.account_id) != want_a:
            out.append(f"аккаунт {ident.account_id} ≠ записанному {want_a}")
        return out

    def publish(self, p: Payload, key: str) -> RemoteRef:
        raise PublishError("MANUAL_REQUIRED", f"{self.platform}: публикация через API не подключена — ручной путь")

    def edit(self, ref: RemoteRef, p: Payload) -> RemoteRef:
        raise PublishError("UNSUPPORTED", f"{self.platform}: правка не реализована")

    def delete(self, ref: RemoteRef) -> None:
        raise PublishError("UNSUPPORTED", f"{self.platform}: удаление не реализовано")

    def upload_media(self, m: MediaFile) -> str:
        raise PublishError("UNSUPPORTED", f"{self.platform}: отдельная загрузка медиа не нужна или не реализована")

    def get_status(self, ref: RemoteRef | None = None, key: str | None = None) -> RemoteStatus:
        return RemoteStatus(None)

    def health(self) -> str:
        ref = self.destination.get("credential_ref")
        return f"учётные данные: {self.credentials.health(ref)}"


def check_text_limit(p: Payload, limit: int | None, what: str) -> list[Finding]:
    if limit and p.visible_len > limit:
        return [Finding("error", f"{what}: {p.visible_len} знаков > {limit}")]
    return []
