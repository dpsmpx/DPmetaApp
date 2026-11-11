"""Адаптер Telegram: публикация в канал через Bot API (Д12; PLATFORM_APIS.md, §1).

Бот — администратор канала с правом can_post_messages. Адресат — только
по числовому id канала из destinations.yaml (§52): перед публикацией
getMe/getChat/getChatMember сверяют бота и канал с записанными id.

Сеть — через транспорт: функция (method, params, files) → ответ JSON.
Транспорт по умолчанию — urllib; в тестах — имитация Bot API, поэтому
контрактные тесты проходят настоящий код адаптера и разбор ошибок.
Токен входит в адрес запроса — адрес не логируется и не попадает в
сообщения об ошибках.
"""
from __future__ import annotations

import html
import json
import socket
import urllib.error
import urllib.request
import uuid

from ..common import Finding
from ..core import markup
from ..core.errors import PublishError
from .base import MediaFile, Payload, PlatformAdapter, RemoteRef, Identity, RemoteStatus, check_text_limit

API = "https://api.telegram.org"


class TransportError(Exception):
    def __init__(self, kind: str, detail: str = ""):
        self.kind, self.detail = kind, detail  # kind: connect | timeout
        super().__init__(f"{kind}: {detail}")


def _multipart(params: dict, files: dict) -> tuple[bytes, str]:
    boundary = uuid.uuid4().hex
    parts = []
    for k, v in params.items():
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode())
    for k, (name, data, mime) in files.items():
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"; filename="{name}"\r\n'
                     f"Content-Type: {mime}\r\n\r\n".encode() + data + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def urllib_transport(token: str, timeout: float = 30.0):
    def call(method: str, params: dict, files: dict | None = None) -> dict:
        url = f"{API}/bot{token}/{method}"
        flat = {k: (json.dumps(v, ensure_ascii=False) if isinstance(v, (dict, list, bool)) else v) for k, v in params.items()}
        if files:
            body, ctype = _multipart(flat, files)
        else:
            body, ctype = json.dumps(params, ensure_ascii=False).encode(), "application/json"
        req = urllib.request.Request(url, data=body, headers={"Content-Type": ctype}, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:  # у Bot API тело ошибки — JSON
            try:
                return json.loads(e.read().decode("utf-8"))
            except ValueError:
                return {"ok": False, "error_code": e.code, "description": f"HTTP {e.code}"}
        except (socket.timeout, TimeoutError):
            raise TransportError("timeout", "ответ не получен") from None
        except urllib.error.URLError as e:
            reason = e.reason
            if isinstance(reason, (socket.timeout, TimeoutError)):
                raise TransportError("timeout", "ответ не получен") from None
            raise TransportError("connect", type(reason).__name__) from None
    return call


def map_error(resp: dict) -> PublishError:
    """Ответ Bot API с ok=false → структурная ошибка (§18)."""
    code = int(resp.get("error_code") or 0)
    desc = str(resp.get("description") or "")
    low = desc.lower()
    params = resp.get("parameters") or {}
    if code == 429:
        return PublishError("RATE_LIMITED", desc, retry_after=params.get("retry_after"))
    if code == 401:
        return PublishError("AUTH_EXPIRED", desc)
    if params.get("migrate_to_chat_id"):
        return PublishError("INVALID_DESTINATION", f"{desc}; новый id: {params['migrate_to_chat_id']}")
    if code == 403 or "not enough rights" in low or "can't be deleted" in low or "need administrator rights" in low:
        return PublishError("PERMISSION_DENIED", desc)
    if "chat not found" in low or "channel_private" in low:
        return PublishError("INVALID_DESTINATION", desc)
    if "message to edit not found" in low or "message to delete not found" in low:
        return PublishError("NOT_FOUND", desc)
    if any(s in low for s in ("photo", "wrong file", "image_process", "file is too big", "dimensions", "failed to get http url")):
        return PublishError("MEDIA_REJECTED", desc)
    if code == 400 and any(s in low for s in ("too long", "can't parse entities", "text must be non-empty", "message is not modified")):
        return PublishError("INVALID_CONTENT", desc)
    if code >= 500:
        return PublishError("PLATFORM_UNAVAILABLE", desc)
    return PublishError("UNKNOWN_PLATFORM_ERROR", f"{code} {desc}".strip())


class TelegramAdapter(PlatformAdapter):
    platform = "telegram"
    min_interval_s = 1.0  # не чаще ~1 сообщения в секунду в один чат (FAQ Bot API)

    @classmethod
    def default_format(cls, has_media: bool, category: str | None = None) -> str:
        return "photo_post" if has_media else "post"

    def _call(self, method: str, params: dict, files: dict | None = None):
        if self.transport is None:
            self.transport = urllib_transport(self.credential().reveal())
        self.rate_limiter.wait()
        try:
            resp = self.transport(method, params, files)
        except TransportError as e:
            if e.kind == "timeout" and method.startswith(("send", "edit", "delete")):
                raise PublishError("OUTCOME_UNKNOWN", f"{method}: {e.detail}") from None
            raise PublishError("PLATFORM_UNAVAILABLE", f"{method}: {e.detail}") from None
        if not resp.get("ok"):
            raise map_error(resp)
        return resp.get("result")

    # ------------------------------------------------------- отрисовка
    def render_text(self, blocks: list, base: str) -> tuple[str, int]:
        text = markup.to_telegram_html(blocks, base)
        return text, markup.visible_length(text)

    def render(self, v) -> Payload:
        p = super().render(v)
        if p.link and p.link not in p.text:
            p.text += "\n\n" + html.escape(p.link, quote=False)
            p.visible_len = markup.visible_length(p.text)
        return p

    def validate(self, p: Payload) -> list[Finding]:
        out = super().validate(p)
        lim = self.limits()
        imgs = [m for m in p.media if m.mime in ("image/jpeg", "image/png")]
        if p.format == "post":
            out += check_text_limit(p, lim.get("text_max_chars"), "текст сообщения")
            if p.media:
                out.append(Finding("error", "формат post — без медиа; для снимка — photo_post"))
        elif p.format in ("photo_post", "album"):
            out += check_text_limit(p, lim.get("caption_max_chars"), "подпись к медиа")
            lo, hi = (1, 1) if p.format == "photo_post" else tuple(lim.get("album_items") or (2, 10))
            if not lo <= len(imgs) <= hi:
                out.append(Finding("error", f"{p.format}: изображений {len(imgs)}, нужно {lo}–{hi}"))
        else:
            out.append(Finding("error", f"формат {p.format} не поддерживается Telegram-адаптером"))
        for m in imgs:
            if m.size > (lim.get("photo_max_bytes") or 10 << 20):
                out.append(Finding("error", f"{m.ref}: {m.size} байт > лимита фото"))
            if m.width and m.height and (m.width + m.height > 10000 or max(m.width, m.height) / min(m.width, m.height) > 20):
                out.append(Finding("error", f"{m.ref}: {m.width}×{m.height} — вне лимитов фото Bot API"))
        if p.visible_len == 0 and not p.media:
            out.append(Finding("error", "пустое сообщение"))
        return out

    # ------------------------------------------------------- адресат
    def _chat_id(self) -> str:
        cid = (self.destination.get("target") or {}).get("id")
        if not cid:
            raise PublishError("INVALID_DESTINATION", "у адресата нет числового target.id — по имени не публикуем (§52)")
        return str(cid)

    def _url(self, message_id) -> str | None:
        user = (self.destination.get("target") or {}).get("username")
        if user:
            return f"https://t.me/{user.lstrip('@')}/{message_id}"
        cid = self._chat_id()
        return f"https://t.me/c/{cid[4:]}/{message_id}" if cid.startswith("-100") else None

    def identify(self) -> Identity:
        me = self._call("getMe", {})
        chat = self._call("getChat", {"chat_id": self._chat_id()})
        if chat.get("type") != "channel":
            raise PublishError("INVALID_DESTINATION", f"чат {chat.get('id')} — {chat.get('type')}, а не канал")
        member = self._call("getChatMember", {"chat_id": self._chat_id(), "user_id": me["id"]})
        if member.get("status") not in ("administrator", "creator") or not member.get("can_post_messages", member.get("status") == "creator"):
            raise PublishError("PERMISSION_DENIED", "бот не администратор канала с правом can_post_messages")
        return Identity(str(me["id"]), str(chat["id"]), chat.get("title"))

    # ------------------------------------------------------- операции
    def _ref(self, msg) -> RemoteRef:
        msgs = msg if isinstance(msg, list) else [msg]
        mid = msgs[0].get("message_id")
        if not mid:  # сервер отложил отправку сам (Bot API: message_id = 0)
            return RemoteRef(None, None, pending=True)
        return RemoteRef(str(mid), self._url(mid), meta={"message_ids": [m.get("message_id") for m in msgs]})

    def _photo(self, m: MediaFile) -> tuple[str, bytes, str]:
        return (m.path.name, m.path.read_bytes(), m.mime)

    def publish(self, p: Payload, key: str) -> RemoteRef:
        chat = self._chat_id()
        if p.format == "post":
            res = self._call("sendMessage", {"chat_id": chat, "text": p.text, "parse_mode": "HTML"})
        elif p.format == "photo_post":
            res = self._call("sendPhoto", {"chat_id": chat, "caption": p.text, "parse_mode": "HTML"},
                             {"photo": self._photo(p.media[0])})
        elif p.format == "album":
            media, files = [], {}
            for i, m in enumerate(p.media):
                item = {"type": "photo", "media": f"attach://f{i}"}
                if i == 0:
                    item.update(caption=p.text, parse_mode="HTML")
                media.append(item)
                files[f"f{i}"] = self._photo(m)
            res = self._call("sendMediaGroup", {"chat_id": chat, "media": media}, files)
        else:
            raise PublishError("INVALID_CONTENT", f"формат {p.format}")
        return self._ref(res)

    def edit(self, ref: RemoteRef, p: Payload) -> RemoteRef:
        chat = self._chat_id()
        if p.format == "post":
            self._call("editMessageText", {"chat_id": chat, "message_id": int(ref.remote_id), "text": p.text, "parse_mode": "HTML"})
        else:
            self._call("editMessageCaption", {"chat_id": chat, "message_id": int(ref.remote_id), "caption": p.text, "parse_mode": "HTML"})
        return RemoteRef(ref.remote_id, ref.remote_url or self._url(ref.remote_id))

    def delete(self, ref: RemoteRef) -> None:
        self._call("deleteMessage", {"chat_id": self._chat_id(), "message_id": int(ref.remote_id)})

    def get_status(self, ref: RemoteRef | None = None, key: str | None = None) -> RemoteStatus:
        # Bot API не читает историю канала и не ищет по ключу — исход проверяет человек.
        return RemoteStatus(None)
