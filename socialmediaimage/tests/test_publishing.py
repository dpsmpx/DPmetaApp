"""Движок публикации: идемпотентность, повторы, сбои, одобрение, адресаты (PLATFORM_VISION §16–19, §51–56).

Сценарии §56: publish once, publish twice, retry after timeout, token
expiration, partial campaign failure, network failure, duplicate webhook.
Плюс: рабочая публикация без одобрения и после правки, устаревание,
чужой аккаунт, чужой бренд, безопасный режим, запрет из сессии ИИ,
пробный прогон ничего не пишет, ручной путь и свойства (случайные сбои).
"""
from __future__ import annotations

import os
import random
import unittest
from unittest import mock

from tools.cs.adapters.mock import MockState, mock_class
from tools.cs.core import textdoc
from tools.cs.core.content import find
from tools.cs.core.engine import Publisher, Target
from tools.cs.core.events import Event, draft_campaign
from tools.cs.core.ledger import Ledger
from tests.platform_fixture import git_commit, ready_repo

NO_SESSION = {"CLAUDECODE": "", "CLAUDE_CODE_REMOTE": ""}


class Factory:
    """Имитации площадок с общим состоянием на адресата — как «одна и та же площадка» между запусками."""

    def __init__(self, repo, lookup=False):
        self.repo, self.states, self.lookup = repo, {}, lookup

    def __call__(self, platform, destination):
        ad = mock_class(platform)(destination, self.repo, sleep=lambda s: None)
        st = self.states.setdefault(destination["id"], MockState(lookup=self.lookup))
        ad.state = st
        return ad

    def posts(self, dest):
        return self.states.get(dest, MockState()).published()


class Base(unittest.TestCase):
    def setUp(self):
        self.env = mock.patch.dict(os.environ, NO_SESSION)
        self.env.start()
        self.repo = ready_repo()
        self.f = Factory(self.repo)
        self.slept = []

    def tearDown(self):
        self.env.stop()

    def pub(self, mode="production", factory=None):
        return Publisher(self.repo, mode, adapter_factory=factory or self.f, sleep=self.slept.append,
                         now=lambda: "2026-10-05T10:00:00Z")

    def tg(self, **kw):
        return Target("CI-0101", "telegram.ru", "tg-prod", **kw)


class Idempotency(Base):
    def test_publish_once_and_twice(self):
        o1 = self.pub().run(self.tg())
        self.assertEqual(o1.status, "published", o1.notes)
        o2 = self.pub().run(self.tg())
        self.assertEqual(o2.status, "already_published")
        self.assertEqual(len(self.f.posts("tg-prod")), 1, "второй запуск не публикует второй пост")
        led = Ledger(self.repo, "production")
        self.assertEqual([e["event"] for e in led.by_key(led.records()[0]["idempotency_key"]).events], ["PUBLISHING", "PUBLISHED"])
        rec = led.records()[-1]
        for k in ("publication_id", "item", "platform", "variant", "variant_version", "idempotency_key", "remote_id",
                  "remote_url", "approval", "ts"):
            self.assertIn(k, rec)

    def test_retry_after_timeout_without_lookup(self):
        p = self.pub()
        self.f.states["tg-prod"] = MockState(faults=["timeout_after_send"])
        o = p.run(self.tg())
        self.assertEqual(o.status, "unknown")
        self.assertEqual(len(self.f.posts("tg-prod")), 1, "пост дошёл, но ответ потерян")
        o = self.pub().run(self.tg())
        self.assertEqual(o.status, "unknown", "без сверки не повторяем — иначе дубль")
        self.assertEqual(len(self.f.posts("tg-prod")), 1)
        pid = o.pub_id
        self.assertEqual(self.pub().reconcile(pid, True, "https://t.me/test_devlog/100").status, "published")
        self.assertEqual(self.pub().run(self.tg()).status, "already_published")
        self.assertEqual(len(self.f.posts("tg-prod")), 1)

    def test_retry_after_timeout_with_lookup(self):
        f = Factory(self.repo, lookup=True)
        f.states["tg-prod"] = MockState(faults=["timeout_after_send"], lookup=True)
        self.assertEqual(self.pub(factory=f).run(self.tg()).status, "unknown")
        o = self.pub(factory=f).run(self.tg())
        self.assertEqual(o.status, "published", "площадка нашла пост по ключу — сверено без дубля")
        self.assertEqual(len(f.posts("tg-prod")), 1)

    def test_reconcile_not_found_allows_retry(self):
        self.f.states["tg-prod"] = MockState(faults=["timeout_after_send"])
        o = self.pub().run(self.tg())
        self.f.states["tg-prod"].posts.clear()  # автор проверил: поста нет
        self.assertEqual(self.pub().reconcile(o.pub_id, False).status, "failed")
        self.assertEqual(self.pub().run(self.tg()).status, "published")
        self.assertEqual(len(self.f.posts("tg-prod")), 1)


class Failures(Base):
    def test_token_expiration_not_retried(self):
        self.f.states["tg-prod"] = MockState(faults=["auth_expired"])
        o = self.pub().run(self.tg())
        self.assertEqual((o.status, o.error["code"]), ("failed", "AUTH_EXPIRED"))
        self.assertEqual(self.slept, [])
        self.assertEqual(self.pub().due(), [], "AUTH_EXPIRED не повторяется по расписанию")

    def test_network_failure_retried_with_backoff(self):
        self.f.states["tg-prod"] = MockState(faults=["unavailable", "rate_limited:7"])
        o = self.pub().run(self.tg())
        self.assertEqual(o.status, "published")
        self.assertEqual(len(self.slept), 2)
        self.assertGreaterEqual(self.slept[1], 7, "retry_after площадки уважается")
        events = [r["event"] for r in Ledger(self.repo, "production").records()]
        self.assertEqual(events, ["PUBLISHING", "FAILED", "RETRY", "PUBLISHING", "FAILED", "RETRY", "PUBLISHING", "PUBLISHED"])

    def test_bounded_retries_and_due(self):
        self.f.states["tg-prod"] = MockState(faults=["unavailable"] * 3)
        o = self.pub().run(self.tg())
        self.assertEqual(o.status, "failed")
        due = self.pub().due()
        self.assertEqual([(t.item, t.variant, t.destination) for t in due], [("CI-0101", "telegram.ru", "tg-prod")])
        self.assertEqual(self.pub().run(due[0]).status, "published")
        self.f.states["tg-prod"] = MockState(faults=["unavailable"] * 10)

    def test_total_attempts_ceiling(self):
        self.f.states["tg-prod"] = MockState(faults=["unavailable"] * 20)
        self.pub().run(self.tg())
        self.pub().run(self.tg())
        o = self.pub().run(self.tg())
        self.assertEqual(o.status, "failed")
        led = Ledger(self.repo, "production")
        attempts = sum(1 for r in led.records() if r["event"] == "PUBLISHING")
        self.assertLessEqual(attempts, 5, "не бесконечные повторы (§17)")

    def test_partial_campaign_failure(self):
        self.f.states["tg-prod"] = MockState(faults=["unavailable"] * 3)
        site = self.pub().run(Target("CI-0101", "website.ru", "site-production"))
        tg = self.pub().run(self.tg())
        self.assertEqual((site.status, tg.status), ("published", "failed"))
        from tools.cs.core.campaigns import target_status
        item = find(self.repo, "CI-0101")
        st = {t["variant"]: target_status(self.repo, item, t).status for t in item.data["targets"]}
        self.assertEqual(st["website.ru"], "опубликовано")
        self.assertEqual(st["telegram.ru"], "сбой")
        self.assertEqual(self.pub().run(self.pub().due()[0]).status, "published")
        st = target_status(self.repo, item, {"variant": "telegram.ru", "destination": "tg-prod"}).status
        self.assertEqual(st, "опубликовано")

    def test_platform_postponed_is_unknown_not_success(self):
        self.f.states["tg-prod"] = MockState(faults=["pending"])
        self.assertEqual(self.pub().run(self.tg()).status, "unknown")

    def test_duplicate_webhook(self):
        ev = Event("release", "galdhold", "github:dpsmpx/test", "v0.8", "Galdhold v0.8")
        c1, _, created1 = draft_campaign(self.repo, ev, apply=True)
        c2, _, created2 = draft_campaign(self.repo, ev, apply=True)
        self.assertEqual((c1, created1, created2), (c2, True, False))
        self.assertEqual(len(self.repo.campaigns()), 1)


class Gates(Base):
    def test_production_requires_valid_approval(self):
        v = find(self.repo, "CI-0101").variants()["telegram.ru"]
        v.doc.body += "\n\nДобавлено после одобрения."
        textdoc.save(v.doc, v.path)
        o = self.pub().run(self.tg())
        self.assertEqual(o.status, "blocked")
        self.assertIn("одобрения", " ".join(o.notes))
        v.doc.meta["approval"] = None
        textdoc.save(v.doc, v.path)
        self.assertEqual(self.pub().run(self.tg()).status, "blocked")
        self.assertEqual(self.f.posts("tg-prod"), [])

    def test_stale_fact_blocks(self):
        p = self.repo.path("data/facts/facts.yaml")
        p.write_text(p.read_text(encoding="utf-8").replace("больше 150 тысяч строк C++ (октябрь 2026)",
                                                            "больше 160 тысяч строк C++ (ноябрь 2026)"), encoding="utf-8")
        self.repo.reset()
        o = self.pub().run(self.tg())
        self.assertEqual(o.status, "blocked")
        self.assertIn("GH-LOC", " ".join(o.notes))

    def test_wrong_account_blocks(self):
        self.f.states["tg-prod"] = MockState(account_id="someone-else")
        o = self.pub().run(self.tg())
        self.assertEqual(o.status, "blocked")
        self.assertIn("аккаунт", " ".join(o.notes))

    def test_wrong_brand_blocks(self):
        o = self.pub().run(Target("CI-0101", "telegram.ru", "tg-dplang"))
        self.assertEqual(o.status, "blocked")
        self.assertIn("бренд", " ".join(o.notes))

    def test_safe_mode_uses_staging_twin(self):
        o = self.pub("safe").run(self.tg())
        self.assertEqual(o.status, "published")
        self.assertEqual(len(self.f.posts("tg-test")), 1)
        self.assertEqual(self.f.posts("tg-prod"), [])
        self.assertTrue(Ledger(self.repo, "staging").records())
        self.assertFalse(Ledger(self.repo, "production").records())

    def test_dry_run_writes_nothing(self):
        o = self.pub("dry-run").run(self.tg())
        self.assertEqual(o.status, "dry_run")
        self.assertFalse(Ledger(self.repo, "production").path.exists())
        self.assertEqual(self.f.posts("tg-prod"), [])

    def test_session_cannot_publish_production(self):
        with mock.patch.dict(os.environ, {"CLAUDECODE": "1"}):
            o = self.pub().run(self.tg())
        self.assertEqual(o.status, "blocked")
        self.assertIn("сессии ИИ", " ".join(o.notes))

    def test_canonical_must_be_committed(self):
        cv = find(self.repo, "CI-0101").canon()
        cv.path.write_text(cv.path.read_text(encoding="utf-8") + "\n", encoding="utf-8")
        o = self.pub().run(self.tg())
        self.assertEqual(o.status, "blocked")
        self.assertIn("не закоммичен", " ".join(o.notes))

    def test_scheduled(self):
        o = self.pub().run(self.tg(at="2026-10-06T09:00:00Z"))
        self.assertEqual(o.status, "scheduled")
        self.assertEqual(self.pub().due(), [])
        later = Publisher(self.repo, "production", adapter_factory=self.f, sleep=self.slept.append,
                          now=lambda: "2026-10-06T09:05:00Z")
        due = later.due()
        self.assertEqual(len(due), 1)
        self.assertEqual(later.run(due[0]).status, "published")

    def test_cancel_and_delete(self):
        o = self.pub().run(self.tg(at="2026-10-06T09:00:00Z"))
        self.assertEqual(self.pub().cancel(o.pub_id).status, "cancelled")
        self.assertEqual(self.pub().cancel(o.pub_id).status, "blocked")
        site = self.pub().run(Target("CI-0101", "website.ru", "site-production"))
        self.assertEqual(self.pub().delete(site.pub_id).status, "deleted")
        self.assertEqual(Ledger(self.repo, "production").publications()[site.pub_id].state, "DELETED")

    def test_new_version_edits_published_post(self):
        self.assertEqual(self.pub().run(self.tg()).status, "published")
        v = find(self.repo, "CI-0101").variants()["telegram.ru"]
        v.doc.body = v.doc.body.replace("Подробнее — на сайте.", "Подробнее — на сайте, там же — снимки.")
        v.doc.meta["version"] = 2
        textdoc.save(v.doc, v.path)
        from tests.platform_fixture import approve
        approve(self.repo, "CI-0101", "telegram.ru")
        git_commit(self.repo, "v2")
        o = self.pub().run(self.tg())
        self.assertEqual(o.status, "published")
        posts = self.f.posts("tg-prod")
        self.assertEqual(len(posts), 1, "новая версия правит пост, а не публикует второй")
        self.assertEqual(posts[0].get("edits"), 1)
        states = [p.state for p in Ledger(self.repo, "production").publications().values()]
        self.assertEqual(sorted(states), ["PUBLISHED", "SUPERSEDED"])

    def test_manual_path(self):
        o = self.pub().run(Target("CI-0101", "telegram.ru", "tg-prod"))
        self.assertEqual(o.status, "published")
        from tools.cs.adapters import adapter_class
        real = Publisher(self.repo, "production", adapter_factory=lambda p, d: adapter_class(p)(d, self.repo))
        self.assertEqual(real.destination("vk-prod")[0]["platform"], "vk")


class Properties(Base):
    """Свойство: при любых сбоях площадки, которая умеет искать по ключу, остаётся ровно один пост."""

    def test_random_faults_never_duplicate(self):
        kinds = ["unavailable", "rate_limited:1", "timeout_after_send", "pending"]
        for seed in range(25):
            with self.subTest(seed=seed):
                repo = ready_repo()
                f = Factory(repo, lookup=True)
                rng = random.Random(seed)
                f.states["tg-prod"] = MockState(lookup=True, faults=[rng.choice(kinds) for _ in range(rng.randint(0, 6))])
                for _ in range(8):
                    p = Publisher(repo, "production", adapter_factory=f, sleep=lambda s: None, now=lambda: "2026-10-05T10:00:00Z")
                    o = p.run(Target("CI-0101", "telegram.ru", "tg-prod"))
                    if o.status in ("published", "already_published"):
                        break
                pubs = [x for x in Ledger(repo, "production").publications().values() if x.state == "PUBLISHED"]
                self.assertLessEqual(len(pubs), 1)
                self.assertLessEqual(len(f.posts("tg-prod")), 1)


if __name__ == "__main__":
    unittest.main()
