"""Имитации площадок (PLATFORM_VISION §102): весь путь публикации без выхода наружу.

mock_class("telegram") → MockTelegram: отрисовка и лимиты — от настоящего
адаптера площадки, сеть — в памяти. Отказы задаются очередью
`state.faults` на следующие вызовы publish/edit:

    "unavailable"        PLATFORM_UNAVAILABLE (повторяемая)
    "rate_limited:3"     RATE_LIMITED с retry_after=3
    "auth_expired"       AUTH_EXPIRED
    "permission_denied"  PERMISSION_DENIED
    "media_rejected"     MEDIA_REJECTED
    "invalid_content"    INVALID_CONTENT
    "timeout_after_send" пост создан, но ответ потерян → OUTCOME_UNKNOWN
    "pending"            площадка отложила отправку (id не вернулся)

`state.lookup` — умеет ли «площадка» найти пост по ключу (как VK guid).
"""
from __future__ import annotations

from dataclasses import dataclass, field

from ..core.errors import PublishError
from . import REGISTRY
from .base import Identity, Payload, PlatformAdapter, RemoteRef, RemoteStatus

FAULTS = {
    "unavailable": ("PLATFORM_UNAVAILABLE", "имитация: площадка недоступна"),
    "rate_limited": ("RATE_LIMITED", "имитация: лимит"),
    "auth_expired": ("AUTH_EXPIRED", "имитация: токен истёк"),
    "permission_denied": ("PERMISSION_DENIED", "имитация: нет прав"),
    "media_rejected": ("MEDIA_REJECTED", "имитация: медиа отклонено"),
    "invalid_content": ("INVALID_CONTENT", "имитация: текст отклонён"),
}


@dataclass
class MockState:
    posts: dict = field(default_factory=dict)      # remote_id → {text, key, media, deleted}
    faults: list = field(default_factory=list)
    calls: list = field(default_factory=list)
    lookup: bool = False
    account_id: str = "mock-account"
    next_id: int = 100

    def published(self) -> list[dict]:
        return [p for p in self.posts.values() if not p.get("deleted")]


def mock_class(platform: str) -> type[PlatformAdapter]:
    base = REGISTRY[platform]

    class Mock(base):  # type: ignore[misc, valid-type]
        automated = True
        min_interval_s = 0.0

        def __init__(self, *a, **kw):
            super().__init__(*a, **kw)
            self.state = MockState()

        def credential(self):
            return "mock"

        def _fault(self, created: RemoteRef | None = None):
            if not self.state.faults:
                return
            spec = self.state.faults.pop(0)
            name, _, arg = spec.partition(":")
            if name == "timeout_after_send":
                raise PublishError("OUTCOME_UNKNOWN", "имитация: ответ потерян после отправки")
            if name == "pending":
                return "pending"
            code, msg = FAULTS[name]
            raise PublishError(code, msg, retry_after=float(arg) if arg else None)

        def identify(self) -> Identity:
            self.state.calls.append(("identify",))
            return Identity(self.state.account_id, str((self.destination.get("target") or {}).get("id") or ""), "mock")

        def check_identity(self) -> list[str]:
            want = str((self.destination.get("account") or {}).get("id") or "")
            if want and want != self.state.account_id:
                return [f"аккаунт {self.state.account_id} ≠ записанному {want}"]
            return []

        def publish(self, p: Payload, key: str) -> RemoteRef:
            self.state.calls.append(("publish", key))
            fault = None
            if self.state.faults and self.state.faults[0] == "timeout_after_send":
                self.state.faults.pop(0)
                self._store(p, key)
                raise PublishError("OUTCOME_UNKNOWN", "имитация: ответ потерян после отправки")
            fault = self._fault()
            rid = self._store(p, key)
            if fault == "pending":
                return RemoteRef(None, None, pending=True)
            return RemoteRef(rid, f"https://mock.{self.platform}/{self.destination['id']}/{rid}")

        def _store(self, p: Payload, key: str) -> str:
            rid = str(self.state.next_id)
            self.state.next_id += 1
            self.state.posts[rid] = {"text": p.text, "key": key, "media": [m.ref for m in p.media],
                                     "destination": self.destination["id"]}
            return rid

        def edit(self, ref: RemoteRef, p: Payload) -> RemoteRef:
            self.state.calls.append(("edit", ref.remote_id))
            self._fault()
            post = self.state.posts.get(str(ref.remote_id))
            if post is None or post.get("deleted"):
                raise PublishError("NOT_FOUND", "имитация: нет поста")
            post["text"] = p.text
            post.setdefault("edits", 0)
            post["edits"] += 1
            return ref

        def delete(self, ref: RemoteRef) -> None:
            self.state.calls.append(("delete", ref.remote_id))
            post = self.state.posts.get(str(ref.remote_id))
            if post is None:
                raise PublishError("NOT_FOUND", "имитация: нет поста")
            post["deleted"] = True

        def get_status(self, ref: RemoteRef | None = None, key: str | None = None) -> RemoteStatus:
            if ref is not None and ref.remote_id:
                post = self.state.posts.get(str(ref.remote_id))
                return RemoteStatus(bool(post and not post.get("deleted")), ref.remote_id, ref.remote_url)
            if key and self.state.lookup:
                for rid, post in self.state.posts.items():
                    if post["key"] == key and not post.get("deleted"):
                        return RemoteStatus(True, rid, f"https://mock.{self.platform}/{self.destination['id']}/{rid}")
                return RemoteStatus(False)
            return RemoteStatus(None)

    Mock.__name__ = Mock.__qualname__ = "Mock" + {"vk": "VK", "github": "GitHub", "youtube": "YouTube",
                                                    "tiktok": "TikTok"}.get(platform, platform.capitalize())
    return Mock
