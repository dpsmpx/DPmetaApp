"""Модульные тесты ядра платформы: разметка, подстановка фактов, журнал, повторы, учётные данные."""
from __future__ import annotations

import json
import unittest

from tools.cs.core import markup
from tools.cs.core.credentials import EnvCredentialStore, ref_environment
from tools.cs.core.errors import PublishError
from tools.cs.core.inject import changed, resolve
from tools.cs.core.ledger import Ledger, idempotency_key, integrity, publication_id
from tools.cs.core.obs import redact
from tools.cs.core.retry import RateLimiter, RetryPolicy
from tests.platform_fixture import make_repo


class MarkupTest(unittest.TestCase):
    TEXT = "## Заголовок\n\nАбзац с **жирным**, *курсивом*, `кодом` и [ссылкой](/news/x/) <тег> & https://t.me/x.\n\n- один\n- два"

    def test_html_escapes_and_links(self):
        h = markup.to_html(markup.parse(self.TEXT))
        self.assertIn("<h2>Заголовок</h2>", h)
        self.assertIn("&lt;тег&gt; &amp;", h)
        self.assertIn('<a href="/news/x/">ссылкой</a>', h)
        self.assertIn('<a href="https://t.me/x">https://t.me/x</a>', h)
        self.assertIn("<ul><li>один</li><li>два</li></ul>", h)

    def test_telegram_html_absolute_links_and_length(self):
        h = markup.to_telegram_html(markup.parse(self.TEXT), "https://dpsmpx.com")
        self.assertIn("<b>Заголовок</b>", h)
        self.assertIn('<a href="https://dpsmpx.com/news/x/">', h)
        self.assertIn("• один", h)
        self.assertNotIn("<h2>", h)
        self.assertEqual(markup.visible_length("<b>ab</b> &amp;"), 4)

    def test_discord_escapes_markdown(self):
        md = markup.to_discord_md(markup.parse("a_b*c"), "")
        self.assertEqual(md, "a\\_b\\*c")

    def test_plain_links(self):
        p = markup.to_plain(markup.parse("[сайт](/x/)"), "https://dpsmpx.com")
        self.assertEqual(p, "сайт — https://dpsmpx.com/x/")


class InjectTest(unittest.TestCase):
    def setUp(self):
        self.repo = make_repo(git=False)

    def test_fact_and_link(self):
        r = resolve("{{fact:GH-LOC}} · {{link:L-TG-DEVLOG}} · {{site:/news/}}", "ru", self.repo, "https://dpsmpx.com")
        self.assertEqual(r.errors, [])
        self.assertIn("150 тысяч строк", r.text)
        self.assertIn("https://t.me/gh_3D_Voxel_OpenWorld_RPG", r.text)
        self.assertIn("https://dpsmpx.com/news/", r.text)
        self.assertIn("GH-LOC", r.deps["facts"])

    def test_errors(self):
        r = resolve("{{fact:NO-SUCH}} {{fact:GH-SPEEDUP}} {{link:L-GH-DPLANG}} {{release:galdhold.latest.version}} {{oops}}",
                    "ru", self.repo)
        msgs = " ".join(r.errors)
        self.assertIn("NO-SUCH", msgs)
        self.assertIn("broken_for_public", msgs)
        self.assertIn("нет опубликованных релизов", msgs)
        self.assertIn("неизвестная подстановка", msgs)

    def test_changed_detects_fact_edit(self):
        r = resolve("{{fact:GH-LOC}}", "ru", self.repo)
        self.assertEqual(changed(r.deps, self.repo), [])
        p = self.repo.path("data/facts/facts.yaml")
        p.write_text(p.read_text(encoding="utf-8").replace("больше 150 тысяч строк C++ (октябрь 2026)",
                                                            "больше 160 тысяч строк C++ (ноябрь 2026)"), encoding="utf-8")
        self.repo.reset()
        self.assertEqual(changed(r.deps, self.repo), ["факт GH-LOC"])


class LedgerTest(unittest.TestCase):
    def setUp(self):
        self.repo = make_repo(git=False)
        self.led = Ledger(self.repo, "production")
        self.key = idempotency_key("CI-0101", "telegram.ru", "a" * 64, "tg-prod", "production")
        self.pid = publication_id(self.key)

    def base(self, **kw):
        return {"publication_id": self.pid, "idempotency_key": self.key, "item": "CI-0101", "variant": "telegram.ru",
                "platform": "telegram", "destination": "tg-prod", **kw}

    def test_key_is_stable_and_specific(self):
        self.assertEqual(self.key, idempotency_key("CI-0101", "telegram.ru", "a" * 64, "tg-prod", "production"))
        self.assertNotEqual(self.key, idempotency_key("CI-0101", "telegram.ru", "b" * 64, "tg-prod", "production"))
        self.assertNotEqual(self.key, idempotency_key("CI-0101", "telegram.ru", "a" * 64, "tg-prod", "staging"))

    def test_transitions(self):
        self.led.append(self.base(event="PUBLISHING"))
        with self.assertRaises(ValueError):
            self.led.append(self.base(event="SCHEDULED"))
        self.led.append(self.base(event="PUBLISHED", remote_id="1", remote_url="https://t.me/x/1",
                                  approval={"by": "author", "date": "2026-10-04", "sha256": "a" * 64}))
        errs, warns = integrity(self.led)
        self.assertEqual((errs, warns), ([], []))
        self.assertEqual(self.led.by_key(self.key).state, "PUBLISHED")

    def test_integrity_flags(self):
        self.led.append(self.base(event="PUBLISHING"))
        self.led.append(self.base(event="PUBLISHED", remote_id="1"))  # без одобрения
        k2 = "c" * 64
        self.led.append({"publication_id": publication_id(k2), "idempotency_key": k2, "item": "CI-0101", "variant": "telegram.ru",
                         "destination": "tg-prod", "event": "PUBLISHED", "remote_id": "1",
                         "approval": {"by": "author", "date": "2026-10-04", "sha256": "c" * 64}})
        k3 = "d" * 64
        self.led.append({"publication_id": publication_id(k3), "idempotency_key": k3, "item": "CI-0101", "variant": "x",
                         "destination": "tg-prod", "event": "PUBLISHING"})
        errs, warns = integrity(self.led)
        self.assertTrue(any("без одобрения" in e for e in errs))
        self.assertTrue(any("дубль" in e for e in errs))
        self.assertTrue(any("исход неизвестен" in w for w in warns))


class RetryTest(unittest.TestCase):
    def test_exponential_bounded_and_retry_after(self):
        p = RetryPolicy(base_delay_s=2, multiplier=2, max_delay_s=10, jitter=0)
        self.assertEqual([p.delay(i) for i in (1, 2, 3, 4)], [2, 4, 8, 10])
        self.assertEqual(p.delay(1, retry_after=30), 30)

    def test_rate_limiter_waits(self):
        now = [0.0]
        slept = []
        rl = RateLimiter(1.0, clock=lambda: now[0], sleep=lambda s: (slept.append(s), now.__setitem__(0, now[0] + s)))
        rl.wait()
        now[0] += 0.3
        rl.wait()
        self.assertEqual([round(s, 2) for s in slept], [0.7])


class CredentialTest(unittest.TestCase):
    def test_env_store_and_boundaries(self):
        store = EnvCredentialStore({"CS_PROD_TELEGRAM_BOT_TOKEN": "123:abc"})
        self.assertEqual(store.health("CS_PROD_TELEGRAM_BOT_TOKEN"), "есть")
        self.assertEqual(store.health("CS_STAGING_TELEGRAM_BOT_TOKEN"), "нет")
        self.assertEqual(ref_environment("CS_STAGING_X"), "staging")
        self.assertIsNone(ref_environment("TELEGRAM_TOKEN"))
        self.assertEqual(repr(store.get("CS_PROD_TELEGRAM_BOT_TOKEN")), "Secret(***)")

    def test_redact(self):
        fake = "12345678:" + "AA" + "abcdefghijklmnopqrstuvwxyz0123456"  # собрано, чтобы проверка secrets не видела токен
        rec = redact({"url": f"https://api.telegram.org/bot{fake}/sendMessage",
                      "token": "x", "note": "ok"})
        self.assertNotIn("AAabc", json.dumps(rec))
        self.assertEqual(rec["token"], "[скрыто]")
        self.assertEqual(rec["note"], "ok")

    def test_error_codes(self):
        self.assertTrue(PublishError("RATE_LIMITED").retryable)
        self.assertFalse(PublishError("AUTH_EXPIRED").retryable)
        self.assertEqual(PublishError("WHATEVER", "x").code, "UNKNOWN_PLATFORM_ERROR")


if __name__ == "__main__":
    unittest.main()
