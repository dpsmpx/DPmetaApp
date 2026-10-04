"""Контрактные тесты адаптеров (PLATFORM_VISION §100–102).

TelegramAdapter проходит настоящий код через имитацию Bot API (транспорт):
  - отказывает без учётных данных;
  - отказывает без явного id канала и в чужой чат;
  - возвращает id и адрес публикации;
  - приводит ошибки площадки к структурным кодам;
  - обрыв после отправки — OUTCOME_UNKNOWN, а не «повторить».
Общий контракт — для всех имитаций площадок и реестра адаптеров.
"""
from __future__ import annotations

import unittest

from tools.cs.adapters import REGISTRY
from tools.cs.adapters.base import Payload, RemoteRef
from tools.cs.adapters.mock import MockState, mock_class
from tools.cs.adapters.telegram import TelegramAdapter, TransportError, map_error
from tools.cs.core.content import find
from tools.cs.core.credentials import EnvCredentialStore
from tools.cs.core.errors import PublishError
from tests.platform_fixture import make_repo

CHAT = "-1001234567890"


class FakeBotAPI:
    """Имитация Bot API: ответы по методам, очередь сбоев, журнал вызовов."""

    def __init__(self, chat_type="channel", rights=True):
        self.calls, self.fail = [], []
        self.chat_type, self.rights = chat_type, rights
        self.next_id = 41

    def __call__(self, method, params, files=None):
        self.calls.append((method, params, sorted((files or {}).keys())))
        if self.fail:
            f = self.fail.pop(0)
            if isinstance(f, Exception):
                raise f
            return f
        if method == "getMe":
            return {"ok": True, "result": {"id": 777, "is_bot": True, "username": "test_bot"}}
        if method == "getChat":
            return {"ok": True, "result": {"id": int(params["chat_id"]), "type": self.chat_type, "title": "Тест"}}
        if method == "getChatMember":
            return {"ok": True, "result": {"status": "administrator", "can_post_messages": self.rights}}
        if method.startswith("send"):
            self.next_id += 1
            msg = {"message_id": self.next_id, "chat": {"id": int(params["chat_id"])}}
            return {"ok": True, "result": [msg, {"message_id": self.next_id + 1}] if method == "sendMediaGroup" else msg}
        return {"ok": True, "result": True}


def dest(**kw):
    d = {"id": "tg-prod", "platform": "telegram", "environment": "production", "target": {"id": CHAT, "username": "test_devlog"},
         "account": {"id": "777"}, "credential_ref": "CS_PROD_TELEGRAM_BOT_TOKEN"}
    d.update(kw)
    return d


def payload(text="Привет", fmt="post", media=None):
    return Payload("telegram", "ru", fmt, text, len(text), media=media or [])


class TelegramContract(unittest.TestCase):
    def setUp(self):
        self.repo = make_repo(git=False)
        self.api = FakeBotAPI()
        self.creds = EnvCredentialStore({"CS_PROD_TELEGRAM_BOT_TOKEN": "not-a-real-token"})

    def adapter(self, d=None, transport="api", creds=None):
        return TelegramAdapter(d or dest(), self.repo, creds or self.creds,
                               transport=self.api if transport == "api" else transport, sleep=lambda s: None)

    def test_rejects_missing_credentials(self):
        ad = TelegramAdapter(dest(), self.repo, EnvCredentialStore({}), sleep=lambda s: None)
        with self.assertRaises(PublishError) as e:
            ad.publish(payload(), "k")
        self.assertEqual(e.exception.code, "MISSING_CREDENTIALS")

    def test_rejects_destination_without_explicit_id(self):
        with self.assertRaises(PublishError) as e:
            self.adapter(dest(target={"id": None, "username": "test_devlog"})).publish(payload(), "k")
        self.assertEqual(e.exception.code, "INVALID_DESTINATION")
        self.assertEqual(self.api.calls, [], "по имени канала запросов не делаем (§52)")

    def test_identity_check(self):
        self.assertEqual(self.adapter().check_identity(), [])
        self.assertIn("аккаунт", " ".join(self.adapter(dest(account={"id": "999"})).check_identity()))
        self.api.chat_type = "supergroup"
        with self.assertRaises(PublishError) as e:
            self.adapter().identify()
        self.assertEqual(e.exception.code, "INVALID_DESTINATION")
        self.api.chat_type, self.api.rights = "channel", False
        with self.assertRaises(PublishError) as e:
            self.adapter().identify()
        self.assertEqual(e.exception.code, "PERMISSION_DENIED")

    def test_returns_remote_id_and_url(self):
        ref = self.adapter().publish(payload(), "k")
        self.assertEqual((ref.remote_id, ref.remote_url), ("42", "https://t.me/test_devlog/42"))
        method, params, _ = self.api.calls[-1]
        self.assertEqual((method, params["chat_id"], params["parse_mode"]), ("sendMessage", CHAT, "HTML"))
        private = self.adapter(dest(target={"id": CHAT})).publish(payload(), "k2")
        self.assertEqual(private.remote_url, "https://t.me/c/1234567890/43")

    def test_photo_and_album_upload_files(self):
        item = find(self.repo, "CI-0101")
        img = self.repo.path("assets/brand/fonts/OFL-IBM-Plex.txt")  # любой файл: проверяется отправка, не формат
        from tools.cs.adapters.base import MediaFile
        m = MediaFile("x.jpg", img, "0" * 64, 10, "image/jpeg", 100, 100)
        self.adapter().publish(payload(fmt="photo_post", media=[m]), "k")
        self.assertEqual(self.api.calls[-1][0], "sendPhoto")
        self.assertEqual(self.api.calls[-1][2], ["photo"])
        ref = self.adapter().publish(payload(fmt="album", media=[m, m]), "k3")
        self.assertEqual(self.api.calls[-1][0], "sendMediaGroup")
        self.assertEqual(ref.meta["message_ids"][1], ref.meta["message_ids"][0] + 1)
        self.assertIsNotNone(item)

    def test_structured_errors(self):
        cases = [
            ({"ok": False, "error_code": 429, "description": "Too Many Requests: retry after 7", "parameters": {"retry_after": 7}}, "RATE_LIMITED"),
            ({"ok": False, "error_code": 401, "description": "Unauthorized"}, "AUTH_EXPIRED"),
            ({"ok": False, "error_code": 403, "description": "Forbidden: bot is not a member of the channel chat"}, "PERMISSION_DENIED"),
            ({"ok": False, "error_code": 400, "description": "Bad Request: message is too long"}, "INVALID_CONTENT"),
            ({"ok": False, "error_code": 400, "description": "Bad Request: PHOTO_INVALID_DIMENSIONS"}, "MEDIA_REJECTED"),
            ({"ok": False, "error_code": 400, "description": "Bad Request: chat not found"}, "INVALID_DESTINATION"),
            ({"ok": False, "error_code": 502, "description": "Bad Gateway"}, "PLATFORM_UNAVAILABLE"),
            ({"ok": False, "error_code": 418, "description": "I'm a teapot"}, "UNKNOWN_PLATFORM_ERROR"),
        ]
        for resp, code in cases:
            self.assertEqual(map_error(resp).code, code, resp)
        self.assertEqual(map_error(cases[0][0]).retry_after, 7)
        self.api.fail = [cases[0][0]]
        with self.assertRaises(PublishError) as e:
            self.adapter().publish(payload(), "k")
        self.assertTrue(e.exception.retryable)

    def test_network_failures(self):
        self.api.fail = [TransportError("connect", "ConnectionRefusedError")]
        with self.assertRaises(PublishError) as e:
            self.adapter().publish(payload(), "k")
        self.assertEqual(e.exception.code, "PLATFORM_UNAVAILABLE")
        self.api.fail = [TransportError("timeout", "ответ не получен")]
        with self.assertRaises(PublishError) as e:
            self.adapter().publish(payload(), "k")
        self.assertEqual(e.exception.code, "OUTCOME_UNKNOWN")
        self.assertFalse(e.exception.retryable)

    def test_server_postponed_message(self):
        self.api.fail = [{"ok": True, "result": {"message_id": 0}}]
        ref = self.adapter().publish(payload(), "k")
        self.assertTrue(ref.pending)

    def test_limits(self):
        ad = self.adapter()
        self.assertTrue(any("4096" in f.msg for f in ad.validate(payload("я" * 4097))))
        long_caption = payload("я" * 1025, fmt="photo_post")
        msgs = " ".join(f.msg for f in ad.validate(long_caption))
        self.assertIn("1024", msgs)
        self.assertIn("изображений 0", msgs)

    def test_edit_and_delete(self):
        ad = self.adapter()
        ad.edit(RemoteRef("42", None), payload("новый"))
        self.assertEqual(self.api.calls[-1][0], "editMessageText")
        ad.delete(RemoteRef("42", None))
        self.assertEqual(self.api.calls[-1][0], "deleteMessage")
        self.api.fail = [{"ok": False, "error_code": 400, "description": "Bad Request: message can't be deleted"}]
        with self.assertRaises(PublishError) as e:
            ad.delete(RemoteRef("42", None))
        self.assertEqual(e.exception.code, "PERMISSION_DENIED")


class GenericContract(unittest.TestCase):
    """Каждая имитация площадки соблюдает один контракт (§102: MockTelegram, MockVK, MockDiscord, MockYouTube, MockGitHub)."""

    def setUp(self):
        self.repo = make_repo(git=False)

    def test_registry_covers_capabilities(self):
        for p in self.repo.capabilities().values():
            if p.get("adapter"):
                self.assertIn(p["adapter"], REGISTRY)

    def test_mocks(self):
        for platform in ("telegram", "vk", "discord", "youtube", "github", "website"):
            with self.subTest(platform=platform):
                cls = mock_class(platform)
                self.assertTrue(cls.__name__.startswith("Mock"))
                d = {"id": f"m-{platform}", "platform": platform, "environment": "production",
                     "target": {"id": "t"}, "account": {"id": "mock-account"}}
                ad = cls(d, self.repo, sleep=lambda s: None)
                p = Payload(platform, "ru", "post", "текст", 5)
                ref = ad.publish(p, "key-1")
                self.assertTrue(ref.remote_id and ref.remote_url)
                self.assertTrue(ad.get_status(ref=ref).exists)
                self.assertEqual(ad.check_identity(), [])
                ad.state.faults = ["auth_expired"]
                with self.assertRaises(PublishError) as e:
                    ad.publish(p, "key-2")
                self.assertEqual(e.exception.code, "AUTH_EXPIRED")
                bad = cls({**d, "account": {"id": "someone-else"}}, self.repo)
                self.assertTrue(bad.check_identity())
                ad.delete(ref)
                self.assertFalse(ad.get_status(ref=ref).exists)

    def test_manual_adapters_refuse_publish(self):
        for platform in ("vk", "youtube", "discord", "github", "tiktok"):
            ad = REGISTRY[platform]({"id": "x", "environment": "production"}, self.repo)
            self.assertFalse(ad.automated)
            with self.assertRaises(PublishError) as e:
                ad.publish(Payload(platform, "ru", "post", "т", 1), "k")
            self.assertEqual(e.exception.code, "MANUAL_REQUIRED")

    def test_mock_state_lookup(self):
        ad = mock_class("vk")({"id": "v", "environment": "production", "target": {"id": "1"}}, self.repo)
        ad.state = MockState(lookup=True)
        ad.publish(Payload("vk", "ru", "post", "т", 1), "k-lookup")
        self.assertTrue(ad.get_status(key="k-lookup").exists)
        self.assertFalse(ad.get_status(key="other").exists)


if __name__ == "__main__":
    unittest.main()
