"""Движок публикации (Д12, Д24, Д25; docs/content/PUBLISHING.md).

    plan       собрать: объект, вариант, адресат, адаптер, текст площадки, ключ
    preflight  проверить: аккаунт, адресат, язык, ссылки, медиа, время,
               версия = одобренная, устаревание, политика (§51)
    execute    PUBLISHING → publish → verify → PUBLISHED | FAILED | UNKNOWN
               с повторами временных ошибок; повторный запуск не дублирует

Режимы: dry-run (по умолчанию; ничего не пишет и не отправляет), safe
(только staging-адресаты), production. Окружение development —
имитации площадок (adapters/mock.py) и журнал в build/.
"""
from __future__ import annotations

import datetime as _dt
import subprocess
import time
from dataclasses import dataclass, field
from typing import Callable

from ..common import Finding
from .content import CItem, Variant, approval_state, find, stale_reasons, variant_sha
from .credentials import EnvCredentialStore
from .errors import PublishError
from .ledger import Ledger, idempotency_key, now_iso, publication_id
from .obs import actor, is_ai_session, log
from .repo import Repo
from .retry import RetryPolicy
from .validator import validate_variant

MODES = ("dry-run", "development", "safe", "production")
ENV_OF_MODE = {"dry-run": None, "development": "development", "safe": "staging", "production": "production"}


@dataclass
class Target:
    item: str
    variant: str
    destination: str
    campaign: str | None = None
    at: str | None = None  # ISO время публикации (планировщик)


@dataclass
class Plan:
    target: Target
    item: CItem
    variant: Variant
    destination: dict
    environment: str
    adapter: object
    payload: object
    sha: str
    key: str
    findings: list[Finding] = field(default_factory=list)

    @property
    def pub_id(self) -> str:
        return publication_id(self.key)

    @property
    def errors(self) -> list[str]:
        return [f.msg for f in self.findings if f.level == "error"]


@dataclass
class Outcome:
    status: str  # dry_run | blocked | scheduled | published | already_published | failed | unknown | manual_required
    pub_id: str | None = None
    remote_url: str | None = None
    error: dict | None = None
    notes: list[str] = field(default_factory=list)


def _parse_ts(s: str) -> _dt.datetime:
    return _dt.datetime.fromisoformat(s.replace("Z", "+00:00"))


class Publisher:
    def __init__(self, repo: Repo, mode: str = "dry-run", *, credentials: EnvCredentialStore | None = None,
                 adapter_factory: Callable | None = None, clock: Callable[[], float] = time.time,
                 sleep: Callable[[float], None] = time.sleep, now: Callable[[], str] = now_iso):
        if mode not in MODES:
            raise ValueError(f"режим {mode}: {MODES}")
        self.repo, self.mode = repo, mode
        self.credentials = credentials or EnvCredentialStore(meta_file=repo.path("local", "credentials.yaml"))
        self.clock, self.sleep, self.now = clock, sleep, now
        self.policy = repo.policy()
        self.retry = RetryPolicy.from_policy(self.policy.get("retry"))
        self.adapter_factory = adapter_factory or self._default_factory
        self._mock_states: dict = {}

    # ------------------------------------------------------- адресат и адаптер
    def _default_factory(self, platform: str, destination: dict):
        from ..adapters import adapter_class
        from ..adapters.mock import mock_class
        if self.mode == "development":
            state = self._mock_states.setdefault(destination["id"], None)
            cls = mock_class(platform)
            ad = cls(destination, self.repo, self.credentials, clock=self.clock, sleep=self.sleep, retry=self.retry)
            if state is not None:
                ad.state = state
            self._mock_states[destination["id"]] = ad.state
            return ad
        return adapter_class(platform)(destination, self.repo, self.credentials, clock=self.clock,
                                       sleep=self.sleep, retry=self.retry)

    def destination(self, dest_id: str) -> tuple[dict, list[Finding]]:
        """Адресат для режима: safe — staging-близнец рабочего адресата (Д25)."""
        dests = self.repo.destinations()
        d = dests.get(dest_id)
        if d is None:
            raise KeyError(f"адресата {dest_id} нет в data/platforms/destinations.yaml")
        out = []
        if self.mode == "safe":
            twin = d if d.get("environment") == "staging" else dests.get(d.get("staging_twin") or "")
            if twin is None or twin.get("environment") != "staging":
                out.append(Finding("error", f"у {dest_id} нет staging-близнеца — безопасный режим некуда направить"))
                return d, out
            d = twin
        elif self.mode in ("production", "dry-run") and d.get("environment") != "production":
            out.append(Finding("warn" if self.mode == "dry-run" else "error",
                               f"{dest_id} — окружение {d.get('environment')}, а режим {self.mode}"))
        return d, out

    def ledger(self, environment: str) -> Ledger:
        return Ledger(self.repo, environment)

    # ------------------------------------------------------- план и предпроверка
    def plan(self, t: Target) -> Plan:
        item = find(self.repo, t.item)
        variants = item.variants()
        if t.variant not in variants:
            raise KeyError(f"{item.id}: нет варианта {t.variant} (variants/{t.variant}.md)")
        v = variants[t.variant]
        dest, findings = self.destination(t.destination)
        env = ENV_OF_MODE[self.mode] or dest.get("environment") or "production"
        adapter = self.adapter_factory(v.platform, dest)
        payload = adapter.render(v)
        sha = variant_sha(v)
        key = idempotency_key(item.id, v.id, sha, dest["id"], env)
        plan = Plan(t, item, v, dest, env, adapter, payload, sha, key, findings)
        plan.findings += validate_variant(v, adapter, dest)
        return plan

    def preflight(self, plan: Plan, remote: bool = True) -> list[Finding]:
        out: list[Finding] = []
        E = lambda m: out.append(Finding("error", m))  # noqa: E731
        d, env = plan.destination, plan.environment
        if self.mode == "production" and is_ai_session():
            E("рабочая публикация из сессии ИИ запрещена (Д26) — запускает автор у себя")
        if self.mode in ("safe", "production", "dry-run") and d.get("status") != "active":
            E(f"адресат {d['id']} — статус {d.get('status')}, нужен active"
              + (f" (ждёт: {', '.join(d['blocked_by'])})" if d.get("blocked_by") else ""))
        need_approval = (self.policy.get("approval") or {}).get("required", {}).get(env, env == "production")
        st = approval_state(plan.variant)
        if need_approval and st != "valid":
            E(f"{plan.variant.id}: нет действующего одобрения автора ({st}) — publish только одобренной версии (Д12)")
        stale = stale_reasons(plan.variant)
        if stale:
            (E if env in ("production", "staging") else lambda m: out.append(Finding("warn", m)))(
                f"{plan.variant.id}: устарел — {', '.join(stale)} (Д28)")
        if env == "production" and (self.policy.get("outage") or {}).get("require_canonical_in_git", True):
            cv = plan.item.canon(plan.variant.m("source_version"))
            if cv is not None and not _in_git(self.repo, cv.path):
                E(f"канон v{cv.version} не закоммичен — сначала сохранить канон в Git (§108)")
        if env == "production" and plan.variant.platform != "website" and \
                (self.policy.get("outage") or {}).get("require_website_published_before_social"):
            if not self.ledger("production").find(item=plan.item.id, states={"PUBLISHED"}):
                E("политика: сначала публикация на сайте (outage.require_website_published_before_social)")
        if plan.target.at and self.mode != "dry-run":
            try:
                _parse_ts(plan.target.at)
            except ValueError:
                E(f"время {plan.target.at} — не ISO 8601")
        if not getattr(plan.adapter, "automated", True) and self.mode != "dry-run":
            out.append(Finding("note", f"{plan.variant.platform}: ручной путь (Д33) — пакет и publish --record-manual"))
        if remote and self.mode in ("safe", "production") and getattr(plan.adapter, "automated", True):
            try:
                for msg in plan.adapter.check_identity():
                    E(f"адресат {d['id']}: {msg}")
            except PublishError as e:
                E(f"адресат {d['id']}: {e.code} — {e.message}")
        return out

    # ------------------------------------------------------- исполнение
    def _base(self, plan: Plan) -> dict:
        a = plan.variant.m("approval") or {}
        return {
            "publication_id": plan.pub_id, "idempotency_key": plan.key, "item": plan.item.id,
            "campaign": plan.target.campaign or plan.item.data.get("campaign"), "platform": plan.variant.platform,
            "destination": plan.destination["id"], "variant": plan.variant.id,
            "variant_version": plan.variant.m("version"), "variant_sha256": plan.sha,
            "canonical_version": plan.variant.m("source_version"), "mode": self.mode, "actor": actor(),
            "approval": {"by": a.get("by"), "date": str(a.get("date")), "sha256": a.get("sha256")} if a else None,
        }

    def run(self, t: Target, remote: bool = True) -> Outcome:
        plan = self.plan(t)
        problems = [f for f in plan.findings + self.preflight(plan, remote=remote)]
        errors = [f.msg for f in problems if f.level == "error"]
        notes = [f.msg for f in problems if f.level != "error"]
        if self.mode == "dry-run":
            return Outcome("dry_run", plan.pub_id, notes=notes + [f"ошибка: {e}" for e in errors])
        if errors:
            return Outcome("blocked", plan.pub_id, notes=errors + notes)
        if not getattr(plan.adapter, "automated", True):
            return Outcome("manual_required", plan.pub_id, notes=notes)
        led = self.ledger(plan.environment)
        prior = led.by_key(plan.key)
        if prior and prior.state in ("PUBLISHED", "SUPERSEDED"):
            return Outcome("already_published", plan.pub_id, prior.remote["remote_url"], notes=["тот же вариант уже опубликован — повтор не нужен (§16)"])
        if prior and prior.state in ("PUBLISHING", "UNKNOWN"):
            return self._resolve_unknown(plan, led, prior)
        if t.at and _parse_ts(t.at) > _parse_ts(self.now()):
            if not prior:
                led.append({**self._base(plan), "event": "SCHEDULED", "scheduled_for": t.at})
            return Outcome("scheduled", plan.pub_id, notes=[f"запланировано на {t.at}"])
        return self._execute(plan, led, prior)

    def _resolve_unknown(self, plan: Plan, led: Ledger, prior) -> Outcome:
        st = plan.adapter.get_status(key=plan.key)
        if st.exists is True:
            led.append({**self._base(plan), "event": "PUBLISHED", "remote_id": st.remote_id,
                        "remote_url": st.remote_url, "note": "сверено с площадкой после неизвестного исхода"})
            return Outcome("published", plan.pub_id, st.remote_url, notes=["сверено: публикация есть"])
        if st.exists is False:
            led.append({**self._base(plan), "event": "FAILED", "error": {"code": "NOT_FOUND", "message": "сверено: публикации нет", "retryable": True}})
            return self._execute(plan, led, led.by_key(plan.key))
        if prior.state == "PUBLISHING":
            led.append({**self._base(plan), "event": "UNKNOWN", "note": "площадка не позволяет сверить — нужен человек"})
        return Outcome("unknown", plan.pub_id, notes=[
            "исход прошлой попытки неизвестен; площадка не даёт сверить — проверьте адресата и запустите "
            f"publish --reconcile {plan.pub_id} --exists yes|no (Д24)"])

    def _execute(self, plan: Plan, led: Ledger, prior) -> Outcome:
        base = self._base(plan)
        done_before = prior.attempts if prior else 0
        previous = [p for p in led.find(item=plan.item.id, variant=plan.variant.id, destination=plan.destination["id"],
                                        states={"PUBLISHED"}) if p.id != plan.pub_id]
        on_update = self.policy.get("on_update", "edit")
        for attempt in range(1, self.retry.max_attempts + 1):
            total = done_before + attempt
            if total > self.retry.max_total_attempts:
                return Outcome("failed", plan.pub_id, error={"code": "RETRY_LIMIT"}, notes=[f"исчерпан потолок попыток ({self.retry.max_total_attempts})"])
            led.append({**base, "event": "PUBLISHING", "attempt": total})
            t0 = self.clock()
            try:
                if previous and on_update == "edit":
                    old = previous[-1]
                    from ..adapters.base import RemoteRef
                    ref = plan.adapter.edit(RemoteRef(old.remote["remote_id"], old.remote["remote_url"]), plan.payload)
                    action = "edit"
                elif previous and on_update == "manual":
                    raise PublishError("MANUAL_REQUIRED", "вариант уже опубликован другой версией; политика on_update: manual")
                else:
                    ref = plan.adapter.publish(plan.payload, plan.key)
                    action = "publish"
            except PublishError as e:
                ms = int((self.clock() - t0) * 1000)
                log(self.repo, "publish", {**base, "attempt": total, "error": e.as_record(), "latency_ms": ms, "meta": e.meta})
                if e.code == "OUTCOME_UNKNOWN":
                    led.append({**base, "event": "UNKNOWN", "error": e.as_record(), "attempt": total})
                    return Outcome("unknown", plan.pub_id, error=e.as_record(), notes=[
                        "запрос мог дойти — повтор без сверки может дать дубль (Д24)"])
                led.append({**base, "event": "FAILED", "error": e.as_record(), "attempt": total, "latency_ms": ms})
                if e.retryable and attempt < self.retry.max_attempts and total < self.retry.max_total_attempts:
                    delay = self.retry.delay(attempt, e.retry_after)
                    led.append({**base, "event": "RETRY", "next_attempt_in_s": delay})
                    self.sleep(delay)
                    continue
                return Outcome("failed", plan.pub_id, error=e.as_record())
            ms = int((self.clock() - t0) * 1000)
            if ref.pending or not (ref.remote_id or ref.remote_url):
                led.append({**base, "event": "UNKNOWN", "note": "площадка отложила отправку — сверить позже", "attempt": total})
                return Outcome("unknown", plan.pub_id, notes=["площадка приняла, но не вернула id"])
            st = plan.adapter.get_status(ref=ref)
            if st.exists is False:
                led.append({**base, "event": "FAILED", "attempt": total,
                            "error": {"code": "UNKNOWN_PLATFORM_ERROR", "message": "проверка после публикации: записи нет", "retryable": False}})
                return Outcome("failed", plan.pub_id, error={"code": "VERIFY_FAILED"})
            led.append({**base, "event": "PUBLISHED", "remote_id": ref.remote_id, "remote_url": ref.remote_url,
                        "verified": st.exists is True, "action": action, "latency_ms": ms, "attempt": total,
                        "supersedes": previous[-1].id if action == "edit" else None})
            if action == "edit":
                led.append({"publication_id": previous[-1].id, "event": "SUPERSEDED", "superseded_by": plan.pub_id})
            log(self.repo, "publish", {**base, "attempt": total, "result": "PUBLISHED", "latency_ms": ms})
            return Outcome("published", plan.pub_id, ref.remote_url)
        return Outcome("failed", plan.pub_id)

    # ------------------------------------------------------- планировщик и ручной путь
    def due(self) -> list[Target]:
        """Наступившие запланированные и повторяемые упавшие публикации окружения режима."""
        env = ENV_OF_MODE[self.mode]
        if env is None:
            return []
        now = _parse_ts(self.now())
        out = []
        for p in self.ledger(env).publications().values():
            f = p.first
            last = p.events[-1]
            ready = False
            if p.state == "SCHEDULED" and _parse_ts(last["scheduled_for"]) <= now:
                ready = True
            elif p.state == "FAILED" and (last.get("error") or {}).get("retryable") and p.attempts < self.retry.max_total_attempts:
                ready = True
            if ready:
                dest = f["destination"]
                if env == "staging":  # журнал staging хранит близнеца; цель — рабочий адресат
                    dest = next((d["id"] for d in self.repo.destinations().values() if d.get("staging_twin") == dest), dest)
                out.append(Target(f["item"], f["variant"], dest, f.get("campaign")))
        return out

    def record_manual(self, t: Target, url: str, remote_id: str | None = None) -> Outcome:
        """Автор опубликовал руками (Д33) — записать адрес в журнал."""
        plan = self.plan(t)
        if is_ai_session():
            return Outcome("blocked", plan.pub_id, notes=["запись ручной публикации — действие автора (Д26)"])
        if approval_state(plan.variant) != "valid":
            return Outcome("blocked", plan.pub_id, notes=["вариант без действующего одобрения автора (Д12)"])
        led = self.ledger(plan.environment)
        if led.by_key(plan.key):
            return Outcome("already_published", plan.pub_id)
        led.append({**self._base(plan), "event": "PUBLISHED", "method": "manual", "remote_id": remote_id, "remote_url": url})
        return Outcome("published", plan.pub_id, url)

    def reconcile(self, pub_id: str, exists: bool, url: str | None = None, remote_id: str | None = None) -> Outcome:
        env = ENV_OF_MODE[self.mode]
        led = self.ledger(env)
        p = led.publications().get(pub_id)
        if p is None or p.state not in ("PUBLISHING", "UNKNOWN"):
            return Outcome("blocked", pub_id, notes=[f"{pub_id}: нечего сверять (состояние {p.state if p else 'нет'})"])
        if p.state == "PUBLISHING":
            led.append({"publication_id": pub_id, "event": "UNKNOWN"})
        if exists:
            if not (url or remote_id):
                return Outcome("blocked", pub_id, notes=["нужен адрес или id найденной публикации"])
            led.append({"publication_id": pub_id, "event": "PUBLISHED", "remote_url": url, "remote_id": remote_id,
                        "approval": p.first.get("approval"), "note": "сверено автором"})
            return Outcome("published", pub_id, url)
        led.append({"publication_id": pub_id, "event": "FAILED",
                    "error": {"code": "NOT_FOUND", "message": "сверено автором: публикации нет", "retryable": True}})
        return Outcome("failed", pub_id, notes=["можно повторить: publish --due"])


    def cancel(self, pub_id: str) -> Outcome:
        """Отменить запланированную публикацию до наступления времени (GOVERNANCE.md, §4)."""
        env = ENV_OF_MODE[self.mode]
        if env is None:
            return Outcome("blocked", pub_id, notes=["режим dry-run ничего не меняет"])
        led = self.ledger(env)
        p = led.publications().get(pub_id)
        if p is None or p.state not in ("SCHEDULED", "FAILED", "RETRY"):
            return Outcome("blocked", pub_id, notes=[f"отменить можно запланированную или упавшую (сейчас {p.state if p else 'нет'})"])
        led.append({"publication_id": pub_id, "event": "CANCELLED", "actor": actor()})
        return Outcome("cancelled", pub_id)

    def delete(self, pub_id: str, manual: bool = False) -> Outcome:
        """Удалить опубликованное на площадке и записать DELETED (журнал и архив сохраняют историю)."""
        env = ENV_OF_MODE[self.mode]
        if env is None:
            return Outcome("blocked", pub_id, notes=["режим dry-run ничего не меняет"])
        if env == "production" and is_ai_session():
            return Outcome("blocked", pub_id, notes=["удаление рабочей публикации — действие автора (Д26)"])
        led = self.ledger(env)
        p = led.publications().get(pub_id)
        if p is None or p.state != "PUBLISHED":
            return Outcome("blocked", pub_id, notes=[f"удалить можно опубликованное (сейчас {p.state if p else 'нет'})"])
        f = p.first
        if not manual:
            dest = self.repo.destinations()[f["destination"]]
            adapter = self.adapter_factory(f["platform"], dest)
            from ..adapters.base import RemoteRef
            try:
                adapter.delete(RemoteRef(p.remote["remote_id"], p.remote["remote_url"]))
            except PublishError as e:
                return Outcome("failed", pub_id, error=e.as_record(),
                               notes=["удалите вручную на площадке и запишите: publish --delete … --manual"])
        led.append({"publication_id": pub_id, "event": "DELETED", "actor": actor(), "method": "manual" if manual else None})
        return Outcome("deleted", pub_id)


def _in_git(repo: Repo, path) -> bool:
    """Файл отслеживается Git и не изменён (канон «сохранён», §108)."""
    try:
        rel = str(path.relative_to(repo.root))
        tracked = subprocess.run(["git", "ls-files", "--error-unmatch", rel], cwd=repo.root,
                                 capture_output=True).returncode == 0
        clean = subprocess.run(["git", "diff", "--quiet", "HEAD", "--", rel], cwd=repo.root,
                               capture_output=True).returncode == 0
        return tracked and clean
    except (OSError, ValueError):
        return False
