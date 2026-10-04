"""Команды платформы публикации (docs/content/PUBLISHING.md, docs/ARCHITECTURE.md, §4).

    migrate ITEM [--apply]                       content_item v1 → v2 (Д31)
    canonical new|seal ITEM                      новая версия канона / фиксация (Д22)
    adapt ITEM --platform P --lang L             заготовка варианта из канона
    variant seal ITEM VARIANT                    проверить и зафиксировать вариант
    validate ITEM [--variant V] [--to DEST]      ContentValidator
    approve ITEM VARIANT                         одобрение автора (не из сессии ИИ, Д26)
    diff ITEM vA vB | ITEM VARIANT               разница версий канона / вариант против канона
    preview ITEM|CMP-… [--serve [--port N]]      предпросмотр всех вариантов
    publish ITEM VARIANT --to DEST [--dev|--safe|--production] [--at ISO]
    publish --due | --record-manual | --reconcile PUB-…
    campaign status CMP-… | campaign list ; calendar ; search ; stale
    site build|serve|deploy [--dry-run] ; health ; bundle ; capabilities [--write] ; events release …
"""
from __future__ import annotations

import datetime as _dt
import difflib
import os
import sys

from .common import Finding, today
from .core import textdoc
from .core.content import (approval_state, canon_stale, find, save_item, seal_canon, seal_variant,
                           stale_reasons, variant_sha, load_items)
from .core.engine import Publisher, Target
from .core.obs import is_ai_session, is_remote_session
from .core.repo import Repo
from .core.validator import validate_canon, validate_variant


def _print_findings(fs: list[Finding]) -> int:
    errs = 0
    for f in fs:
        mark = {"error": "✗", "warn": "!", "note": "·"}[f.level]
        print(f"  {mark} {f.msg}")
        errs += f.level == "error"
    if not fs:
        print("  ✓ замечаний нет")
    return errs


# ----------------------------------------------------------------- модель
def cmd_migrate(a) -> int:
    from .core import migrate
    repo = Repo()
    item = find(repo, a.item)
    acts = migrate.plan_v1_to_v2(item)
    if not acts and item.schema_version >= 2:
        print(f"{item.id}: уже content_item/v{item.schema_version}")
        return 0
    print(f"{item.id}: content_item/v1 → v2\n" + migrate.describe(acts))
    if not a.apply:
        print("пробный прогон; применить — --apply")
        return 0
    for line in migrate.apply_v1_to_v2(item):
        print("  " + line)
    return 0


def cmd_canonical(a) -> int:
    repo = Repo()
    item = find(repo, a.item)
    canon = item.data.get("canonical")
    if a.action == "new":
        if not canon:
            raise SystemExit(f"{item.id}: канона нет — завести canonical/v1.md и запись canonical в item.yaml")
        cur = item.canon()
        n = max(v.version for v in item.versions()) + 1
        doc = textdoc.TextDoc(dict(cur.doc.meta), cur.doc.body)
        doc.meta.update(version=n, date=today(), trust="unverified", change_note="ОПИСАТЬ, что изменено и почему")
        rel = f"canonical/v{n}.md"
        textdoc.save(doc, item.dir / rel)
        canon["versions"].append({"version": n, "file": rel, "date": today()})
        canon["current"] = n
        save_item(item)
        print(f"{item.id}: черновик {rel} (копия v{cur.version}); после правки — canonical seal")
        return 0
    cv = item.canon()
    if cv is None:
        raise SystemExit(f"{item.id}: канона нет")
    if cv.sealed:
        print(f"{item.id}: v{cv.version} уже зафиксирована")
        return 0
    errs = _print_findings(validate_canon(item, cv))
    if errs:
        print("не зафиксировано: исправить ошибки")
        return 1
    cv.doc.meta["trust"] = "validated"
    textdoc.save(cv.doc, cv.path)
    seal_canon(item, cv)
    save_item(item)
    print(f"{item.id}: v{cv.version} зафиксирована (sha256 и зависимости записаны)")
    return 0


def cmd_adapt(a) -> int:
    from .core.generation import GenerationRequest, TemplateProvider, generate
    repo = Repo()
    item = find(repo, a.item)
    cv = item.canon()
    if cv is None or not cv.sealed:
        raise SystemExit(f"{item.id}: нужна зафиксированная версия канона (canonical seal)")
    vid = f"{a.platform}{'-' + a.suffix if a.suffix else ''}.{a.lang}"
    path = item.dir / "variants" / f"{vid}.md"
    if path.exists() and not a.force:
        raise SystemExit(f"{path.name} уже есть (--force — перезаписать)")
    req = GenerationRequest("template:adapt@1", {"text": cv.doc.body, "platform": a.platform, "lang": a.lang},
                            cv.record.get("sha256") or "", "none")
    res = generate(repo, TemplateProvider(), req)
    from .adapters import adapter_class
    fmt = a.format or adapter_class(a.platform).default_format(False, item.category)
    meta = {"schema": "variant/v1", "item": item.id, "platform": a.platform, "lang": a.lang, "format": fmt,
            "source_version": cv.version, "version": 1, "status": "draft", "trust": "unverified",
            "title": cv.doc.meta.get("title"), "link": "canonical" if a.platform != "website" else None,
            "generation": {"provider": res.provider, "cache_key": res.key,
                           "note": "заготовка из канона: сократить и переставить под площадку (docs/platforms/)"}}
    textdoc.save(textdoc.TextDoc(meta, res.text), path)
    print(f"{item.id}: {path.relative_to(repo.root)} — черновик (trust: unverified); дальше — правка, variant seal")
    return 0


def cmd_variant(a) -> int:
    repo = Repo()
    item = find(repo, a.item)
    v = item.variants().get(a.variant)
    if v is None:
        raise SystemExit(f"{item.id}: нет варианта {a.variant}")
    from .adapters import adapter_class
    ad = adapter_class(v.platform)({"id": "seal", "environment": "staging"}, repo)
    errs = _print_findings(validate_variant(v, ad))
    if errs:
        print("не зафиксировано: исправить ошибки")
        return 1
    if v.m("approval") and approval_state(v) == "valid" and not a.force:
        print("вариант одобрен и не менялся — фиксировать заново не нужно")
        return 0
    seal_variant(v)
    v.doc.meta["trust"] = "validated"
    v.doc.meta["status"] = "ready"
    if approval_state(v) == "invalid":
        v.doc.meta["approval"] = None
        v.doc.meta["version"] = int(v.m("version") or 1) + 1
        print("  · содержимое изменилось после одобрения — одобрение снято, версия варианта увеличена")
    textdoc.save(v.doc, v.path)
    print(f"{item.id}/{v.id}: ready, зависимости записаны; одобряет автор: python3 -m tools.cs approve {item.id} {v.id}")
    return 0


def cmd_validate(a) -> int:
    repo = Repo()
    item = find(repo, a.item)
    from .adapters import adapter_class
    errs = 0
    for cv in item.versions():
        print(f"{item.id} канон v{cv.version}{' (зафиксирован)' if cv.sealed else ''}:")
        errs += _print_findings(validate_canon(item, cv) + [Finding("warn", f"устарел: {s}") for s in canon_stale(item, cv)])
    for vid, v in item.variants().items():
        if a.variant and vid != a.variant:
            continue
        dest = repo.destinations().get(a.to) if a.to else None
        ad = adapter_class(v.platform)(dest or {"id": "validate", "environment": "staging"}, repo)
        p = ad.render(v)
        print(f"{item.id}/{vid} ({p.format}, {p.visible_len} знаков, одобрение: {approval_state(v)}):")
        errs += _print_findings(validate_variant(v, ad, dest) + [Finding("warn", f"устарел: {s}") for s in stale_reasons(v)])
    return 1 if errs else 0


def cmd_approve(a) -> int:
    """Одобрение — действие автора (Д12, Д26): не из сессии ИИ, только в терминале автора."""
    if is_ai_session():
        print("одобрение из сессии ИИ запрещено (Д26): запустите эту команду у себя в терминале")
        return 2
    if not sys.stdin.isatty():
        print("одобрение — только интерактивно (терминал автора)")
        return 2
    repo = Repo()
    item = find(repo, a.item)
    v = item.variants().get(a.variant)
    if v is None:
        raise SystemExit(f"нет варианта {a.variant}")
    from .adapters import adapter_class
    ad = adapter_class(v.platform)({"id": "approve", "environment": "production"}, repo)
    fs = validate_variant(v, ad)
    if _print_findings(fs) or stale_reasons(v) or v.m("status") != "ready":
        print("одобрить нельзя: есть ошибки, вариант устарел или не зафиксирован (variant seal)")
        return 1
    p = ad.render(v)
    print("\n——— текст, который уйдёт на площадку ———\n" + p.text + "\n———")
    print("медиа: " + (", ".join(m.ref for m in p.media) or "—"))
    if input("Одобряю эту версию для публикации? Введите «одобряю»: ").strip().lower() != "одобряю":
        print("не одобрено")
        return 1
    v.doc.meta["approval"] = {"by": "author", "date": today(), "sha256": variant_sha(v)}
    if a.note:
        v.doc.meta["approval"]["note"] = a.note
    textdoc.save(v.doc, v.path)
    print(f"{item.id}/{v.id}: одобрено (sha256 {v.doc.meta['approval']['sha256'][:12]}…)")
    return 0


def cmd_diff(a) -> int:
    repo = Repo()
    item = find(repo, a.item)
    if a.b:
        va, vb = (item.canon(int(x.lstrip("v"))) for x in (a.a, a.b))
        if va is None or vb is None:
            raise SystemExit("нет такой версии канона")
        left, right, la, lb = va.doc.body, vb.doc.body, f"v{va.version}", f"v{vb.version}"
    else:
        v = item.variants().get(a.a)
        if v is None:
            raise SystemExit(f"нет варианта {a.a}")
        cv = item.canon(v.m("source_version")) if v.m("source_version") else None
        left, right, la, lb = (cv.doc.body if cv else ""), v.doc.body, f"канон v{cv.version if cv else '—'}", v.id
    out = list(difflib.unified_diff(left.splitlines(), right.splitlines(), la, lb, lineterm=""))
    print("\n".join(out) or "без различий")
    return 0


# ----------------------------------------------------------------- публикация
def _mode(a) -> str:
    return "production" if a.production else "safe" if a.safe else "development" if a.dev else "dry-run"


def cmd_publish(a) -> int:
    repo = Repo()
    mode = _mode(a)
    allowed = repo.policy().get("session_modes") or ["dry-run", "development"]
    if mode == "production" and repo.policy().get("local_session_production") and not is_remote_session():
        allowed = [*allowed, "production"]  # Д36: движок пропустит только площадки из политики
    if is_ai_session() and mode not in allowed:
        print(f"режим {mode} из сессии ИИ запрещён (Д26; data/policy/publication.yaml, session_modes)")
        return 2
    if is_ai_session() and mode == "production" and (a.reconcile or a.cancel or a.delete or a.record_manual):
        print("сверка, отмена, удаление и ручная запись в production — действия автора (Д24, Д26); "
              "сессии разрешена только публикация на сайт (Д36)")
        return 2
    pub = Publisher(repo, mode)
    if a.reconcile:
        o = pub.reconcile(a.reconcile, a.exists == "yes", a.url, a.remote_id)
        print(f"{o.status}: {' '.join(o.notes)}")
        return 0 if o.status != "blocked" else 1
    if a.cancel:
        o = pub.cancel(a.cancel)
        print(f"{o.status}: {' '.join(o.notes)}")
        return 0 if o.status == "cancelled" else 1
    if a.delete:
        o = pub.delete(a.delete, manual=a.manual)
        print(f"{o.status}: {' '.join(o.notes)} {o.error or ''}")
        return 0 if o.status == "deleted" else 1
    if a.due:
        ts = pub.due()
        if not ts:
            print("наступивших публикаций нет")
        rc = 0
        for t in ts:
            o = pub.run(t)
            print(f"{t.item}/{t.variant} → {t.destination}: {o.status} {o.remote_url or ''} {' '.join(o.notes)}")
            rc |= o.status in ("failed", "blocked", "unknown")
        return rc
    if not (a.item and a.variant and a.to):
        raise SystemExit("нужно: ITEM VARIANT --to DEST (или --due / --reconcile)")
    t = Target(a.item, a.variant, a.to, a.campaign, a.at)
    if a.record_manual:
        if not a.url:
            raise SystemExit("--record-manual требует --url")
        o = pub.record_manual(t, a.url, a.remote_id)
        print(f"{o.status}: {o.pub_id} {o.remote_url or ''} {' '.join(o.notes)}")
        return 0 if o.status in ("published", "already_published") else 1
    plan = pub.plan(t)
    print(f"{plan.item.id}/{plan.variant.id} → {plan.destination['id']} ({plan.destination.get('platform')}, "
          f"окружение {plan.environment}, режим {mode})")
    print(f"  публикация {plan.pub_id}; ключ {plan.key[:16]}…; одобрение: {approval_state(plan.variant)}")
    tgt = plan.destination.get("target") or {}
    print(f"  куда: target.id={tgt.get('id')} {tgt.get('username') or tgt.get('base_url') or ''}; "
          f"от кого: account.id={(plan.destination.get('account') or {}).get('id')}; учётные данные: {plan.destination.get('credential_ref') or '—'}")
    print(f"  язык {plan.variant.lang}; формат {plan.payload.format}; длина {plan.payload.visible_len}; "
          f"медиа: {', '.join(m.ref for m in plan.payload.media) or '—'}; ссылка: {plan.payload.link or '—'}")
    if mode == "dry-run":
        print("——— текст площадки ———\n" + plan.payload.text + "\n———")
    o = pub.run(t, remote=not a.no_remote)
    for n in o.notes:
        print(f"  · {n}")
    print(f"итог: {o.status}{' ' + o.remote_url if o.remote_url else ''}{' ' + str(o.error) if o.error else ''}")
    return 0 if o.status in ("dry_run", "published", "already_published", "scheduled", "manual_required") else 1


# ----------------------------------------------------------------- виды
def cmd_campaign(a) -> int:
    from .core.campaigns import campaign_rows
    repo = Repo()
    if a.action == "list" or not a.id:
        for cid, c in repo.campaigns().items():
            print(f"{cid}  {c.get('status'):<9} {c.get('title')}  объекты: {', '.join(c.get('items', []))}")
        return 0
    c = repo.campaigns()[a.id]
    print(f"{a.id} — {c['title']} ({c.get('status')})\nцель: {c['objective']}")
    for r in campaign_rows(repo, a.id):
        when = f" · {r.at}" if r.at else ""
        print(f"  {r.platform:<9} {r.variant:<14} {r.status:<32} {r.destination or '—':<16} {r.detail}{when}")
    return 0


def cmd_calendar(a) -> int:
    from .core.campaigns import calendar
    rows = calendar(Repo())
    if not rows:
        print("в календаре пусто: нет расписаний кампаний и публикаций")
    for r in rows:
        print(f"{str(r.at)[:16]:<17} {r.platform:<9} {r.item}/{r.variant:<14} {r.status:<24} {r.detail}")
    return 0


def cmd_search(a) -> int:
    from .core.campaigns import search
    items = search(Repo(), project=a.project, tag=a.tag, platform=a.platform, type_=a.type, campaign=a.campaign,
                   status=a.status, date_from=a.date_from, date_to=a.date_to)
    for it in items:
        print(f"{it.id}  {it.data.get('state'):<12} {it.data.get('category', it.data.get('type')):<12} {it.data.get('title')}")
    print(f"найдено: {len(items)}")
    return 0


def cmd_stale(a) -> int:
    from .core.ledger import Ledger
    repo = Repo()
    n = 0
    published = {(p.first["item"], p.first["variant"]) for p in Ledger(repo, "production").publications().values()
                 if p.state == "PUBLISHED"}
    for item in load_items(repo):
        for cv in item.versions():
            for s in canon_stale(item, cv):
                n += 1
                print(f"{item.id} канон v{cv.version}: {s}")
        for vid, v in item.variants().items():
            for s in stale_reasons(v):
                n += 1
                mark = " [ОПУБЛИКОВАНО — решить: новая версия или оставить как историю]" if (item.id, vid) in published else ""
                print(f"{item.id}/{vid}: {s}{mark}")
    print(f"устаревших зависимостей: {n}")
    return 0


def _site_deploy(repo: Repo, dry_run: bool) -> int:
    """Выгрузка production-сборки на хостинг (Д35): тема и код сайта без новой записи.
    Записи публикуются через publish; выгружается та же сборка из журнала production."""
    from .common import Report
    from .core.obs import actor, log
    from .platform_checks import _site_findings
    from .site import deploy
    from .site.build import build
    problems = deploy.guard(repo, "production", dry_run=dry_run)
    dest = repo.destinations().get("site-production") or {}
    if not dry_run and dest.get("status") != "active":
        problems.append(f"адресат site-production — статус {dest.get('status')}"
                        + (f" (ждёт: {', '.join(dest.get('blocked_by') or [])})" if dest.get("blocked_by") else ""))
    if problems:
        print("выгрузка не запускается:" + "".join(f"\n  ✗ {p}" for p in problems))
        return 2
    out = repo.path("build", "site", "production")
    rep = build(repo, "production", out)
    r = Report("site deploy")
    _site_findings(rep, out, r, "production")
    if r.errors:
        print("сборка не прошла проверку — не выгружаю:" + "".join(f"\n  ✗ {e}" for e in r.errors))
        return 1
    res = deploy.run(repo, out, "production", dry_run=dry_run)
    print(("пробный прогон" if dry_run else "выгрузка") + f": {' '.join(res.command)}")
    for line in res.changes[:200]:
        print(f"  {line}")
    print(f"  изменений: {len(res.changes)}" + (" (показаны первые 200)" if len(res.changes) > 200 else ""))
    for p in res.problems:
        print(f"  ✗ {p}")
    if res.output and not res.ok:
        print(res.output)
    if dry_run or not res.ok:
        return 0 if res.ok else 1
    seen = deploy.verify(repo.site_base("production"), ["/", "/en/", "/projects/"])
    for path, ok in seen.items():
        print(f"  {'✓' if ok else '✗' if ok is False else '?'} {repo.site_base('production')}{path}")
    log(repo, "deploy", {"env": "production", "actor": actor(), "changes": len(res.changes),
                         "verified": {k: v for k, v in seen.items()}})
    return 0 if all(v is not False for v in seen.values()) else 1


def cmd_site(a) -> int:
    repo = Repo()
    if a.action == "deploy":
        return _site_deploy(repo, a.dry_run)
    if a.action == "serve":
        from .site.serve import serve
        root = repo.path("build", "site", a.env)
        if not root.is_dir() or a.rebuild:
            from .site.build import build
            build(repo, a.env)
        serve(root, a.port, a.env)
        return 0
    from .site.build import build
    out = repo.path(a.out) if a.out else None
    rep = build(repo, a.env, out, drafts=False if a.no_drafts else None)
    print(f"сайт {a.env}: {rep.out} — страниц {len(rep.pages)}, файлов {len(rep.files)}")
    for e in rep.errors:
        print(f"  ✗ {e}")
    for w in sorted(set(rep.warnings)):
        print(f"  ! {w}")
    if a.env == "production":
        print("  · выгрузка: python3 -m tools.cs site deploy --dry-run (показать изменения), затем без --dry-run (Д35, Д36)")
    return 1 if rep.errors else 0


def cmd_preview(a) -> int:
    from .site.preview import PAGE_DIR, preview
    path = preview(Repo(), a.id)
    print(f"предпросмотр: {path}")
    if a.serve:
        from .site.serve import serve
        serve(path.parent.parent, a.port, "предпросмотр", f"/{PAGE_DIR}/")
    else:
        print(f"  открыть со стилями сайта: python3 -m tools.cs preview {a.id} --serve")
    return 0


def cmd_health(a) -> int:
    from .core.health import report
    for sec, what, st in report(Repo(), remote=a.remote):
        print(f"{sec:<15} {what:<32} {st}")
    return 0


def cmd_bundle(a) -> int:
    from .core.bundle import bundle, verify
    repo = Repo()
    res = bundle(repo, repo.path(a.out))
    errs = verify(repo.root / res["path"]) if not os.path.isabs(res["path"]) else verify(__import__("pathlib").Path(res["path"]))
    print(f"выгрузка: {res['path']} — файлов {res['files']}; части: {res['parts']}; сверка манифеста: {'ок' if not errs else errs[:3]}")
    return 0 if not errs else 1


def cmd_capabilities(a) -> int:
    from . import capabilities
    if a.write:
        capabilities.write()
        print("таблица матрицы обновлена: docs/platforms/PUBLISHING_CAPABILITY_MATRIX.md")
    else:
        print(capabilities.table())
    return 0


def cmd_events(a) -> int:
    from .core.events import Event, draft_campaign
    repo = Repo()
    ev = Event(a.kind, a.project, a.source, a.source_id, a.title)
    cid, draft, created = draft_campaign(repo, ev, apply=a.apply)
    if not created and draft.get("source_event", {}).get("key") == ev.key and cid in repo.campaigns():
        print(f"событие {ev.key} уже обработано: {cid} — второй черновик не создаётся")
        return 0
    print(f"{'создан' if created else 'будет создан (пробный прогон; --apply)'} черновик {cid}: {draft['title']}")
    return 0


AFFECTED = [  # префикс пути → проверки (tools/check.sh --affected)
    ("data/facts/", ["facts", "claims", "canonical", "projects", "site"]),
    ("content/items/", ["items", "claims", "provenance", "visual", "exports", "canonical", "campaigns", "site"]),
    ("data/publications/", ["publications", "site"]),
    ("data/platforms/destinations", ["destinations", "campaigns", "capabilities"]),
    ("data/platforms/capabilities", ["capabilities", "destinations"]),
    ("data/platforms/export_specs", ["exports", "visual"]),
    ("data/campaigns/", ["campaigns"]),
    ("data/projects/", ["projects", "site"]),
    ("data/identity/", ["destinations", "projects", "canonical", "site"]),
    ("data/website/", ["site"]),
    ("data/provenance/", ["provenance"]),
    ("data/metrics/", ["metrics"]),
    ("data/experiments/", ["metrics"]),
    ("website/", ["site"]),
    ("assets/", ["provenance", "visual", "site"]),
    ("schemas/", ["schemas"]),
    ("docs/", ["links", "facts"]),
]


def affected_checks(files: list[str]) -> tuple[list[str], bool]:
    """(проверки, нужны ли тесты) по изменённым файлам."""
    from .__main__ import CHECKS
    names = {"schemas", "secrets"}
    tests = False
    for f in files:
        if f.startswith(("tools/", "tests/")):
            return list(CHECKS), True
        for prefix, checks in AFFECTED:
            if f.startswith(prefix):
                names.update(checks)
        if f.endswith(".md"):
            names.update(["links", "facts"])
    return [n for n in CHECKS if n in names], tests


def cmd_affected(a) -> int:
    import subprocess
    repo = Repo()
    base = "origin/main" if subprocess.run(["git", "rev-parse", "-q", "--verify", "origin/main"], cwd=repo.root,
                                            capture_output=True).returncode == 0 else "main"
    out = subprocess.run(["git", "diff", "--name-only", f"{base}...HEAD"], cwd=repo.root, capture_output=True, text=True).stdout
    out += subprocess.run(["git", "status", "--porcelain"], cwd=repo.root, capture_output=True, text=True).stdout
    files = sorted({l[3:] if l[:2].strip() and len(l) > 3 and l[2] == " " else l.strip() for l in out.splitlines() if l.strip()})
    checks, tests = affected_checks(files)
    print(" ".join(checks) + (" +tests" if tests else ""))
    return 0


def register(sub) -> None:
    p = sub.add_parser("affected", help="проверки, затронутые изменениями (для tools/check.sh --affected)")
    p.set_defaults(fn=cmd_affected)
    p = sub.add_parser("migrate", help="миграция объекта v1 → v2")
    p.add_argument("item")
    p.add_argument("--apply", action="store_true")
    p.set_defaults(fn=cmd_migrate)
    p = sub.add_parser("canonical", help="версии канона")
    p.add_argument("action", choices=["new", "seal"])
    p.add_argument("item")
    p.set_defaults(fn=cmd_canonical)
    p = sub.add_parser("adapt", help="заготовка варианта площадки из канона")
    p.add_argument("item")
    p.add_argument("--platform", required=True)
    p.add_argument("--lang", required=True, choices=["ru", "en"])
    p.add_argument("--format")
    p.add_argument("--suffix")
    p.add_argument("--force", action="store_true")
    p.set_defaults(fn=cmd_adapt)
    p = sub.add_parser("variant", help="фиксация варианта")
    p.add_argument("action", choices=["seal"])
    p.add_argument("item")
    p.add_argument("variant")
    p.add_argument("--force", action="store_true")
    p.set_defaults(fn=cmd_variant)
    p = sub.add_parser("validate", help="ContentValidator")
    p.add_argument("item")
    p.add_argument("--variant")
    p.add_argument("--to")
    p.set_defaults(fn=cmd_validate)
    p = sub.add_parser("approve", help="одобрение автора (только терминал автора)")
    p.add_argument("item")
    p.add_argument("variant")
    p.add_argument("--note")
    p.set_defaults(fn=cmd_approve)
    p = sub.add_parser("diff", help="разница версий канона или варианта и канона")
    p.add_argument("item")
    p.add_argument("a")
    p.add_argument("b", nargs="?")
    p.set_defaults(fn=cmd_diff)
    p = sub.add_parser("publish", help="публикация (по умолчанию — пробный прогон)")
    p.add_argument("item", nargs="?")
    p.add_argument("variant", nargs="?")
    p.add_argument("--to")
    p.add_argument("--campaign")
    p.add_argument("--at")
    g = p.add_mutually_exclusive_group()
    g.add_argument("--dev", action="store_true", help="имитации площадок (development)")
    g.add_argument("--safe", action="store_true", help="только staging-адресаты")
    g.add_argument("--production", action="store_true", help="рабочая публикация")
    p.add_argument("--due", action="store_true", help="наступившие и повторяемые")
    p.add_argument("--record-manual", action="store_true", help="записать ручную публикацию (Д33)")
    p.add_argument("--reconcile", metavar="PUB_ID")
    p.add_argument("--cancel", metavar="PUB_ID", help="отменить запланированную")
    p.add_argument("--delete", metavar="PUB_ID", help="удалить опубликованное на площадке")
    p.add_argument("--manual", action="store_true", help="с --delete: удалено вручную, только записать")
    p.add_argument("--exists", choices=["yes", "no"])
    p.add_argument("--url")
    p.add_argument("--remote-id")
    p.add_argument("--no-remote", action="store_true", help="без сверки аккаунта по сети")
    p.set_defaults(fn=cmd_publish)
    p = sub.add_parser("campaign", help="кампании: сводка по площадкам")
    p.add_argument("action", choices=["status", "list"])
    p.add_argument("id", nargs="?")
    p.set_defaults(fn=cmd_campaign)
    p = sub.add_parser("calendar", help="календарь публикаций")
    p.set_defaults(fn=cmd_calendar)
    p = sub.add_parser("search", help="поиск объектов")
    for k in ("project", "tag", "platform", "type", "campaign", "status"):
        p.add_argument(f"--{k}")
    p.add_argument("--from", dest="date_from")
    p.add_argument("--to", dest="date_to")
    p.set_defaults(fn=cmd_search)
    p = sub.add_parser("stale", help="устаревшие каноны и варианты")
    p.set_defaults(fn=cmd_stale)
    p = sub.add_parser("site", help="сайт: сборка, локальный staging, выгрузка на хостинг")
    p.add_argument("action", choices=["build", "serve", "deploy"])
    p.add_argument("--dry-run", action="store_true", help="deploy: только показать изменения (rsync --dry-run)")
    p.add_argument("--env", default="staging", choices=["staging", "production"])
    p.add_argument("--out")
    p.add_argument("--no-drafts", action="store_true")
    p.add_argument("--port", type=int, default=8000)
    p.add_argument("--rebuild", action="store_true")
    p.set_defaults(fn=cmd_site)
    p = sub.add_parser("preview", help="предпросмотр объекта или кампании")
    p.add_argument("id")
    p.add_argument("--serve", action="store_true", help="открыть на локальном сервере (сайт — от корня)")
    p.add_argument("--port", type=int, default=8001)
    p.set_defaults(fn=cmd_preview)
    p = sub.add_parser("health", help="здоровье площадок, учётных данных, сайта, хранилища")
    p.add_argument("--remote", action="store_true")
    p.set_defaults(fn=cmd_health)
    p = sub.add_parser("bundle", help="выгрузка всей базы (резервная копия)")
    p.add_argument("--out", default="build/export")
    p.set_defaults(fn=cmd_bundle)
    p = sub.add_parser("capabilities", help="матрица возможностей площадок")
    p.add_argument("--write", action="store_true")
    p.set_defaults(fn=cmd_capabilities)
    p = sub.add_parser("events", help="событие проекта → черновик кампании")
    p.add_argument("kind")
    p.add_argument("--project", required=True)
    p.add_argument("--source", default="manual")
    p.add_argument("--source-id", required=True)
    p.add_argument("--title", required=True)
    p.add_argument("--apply", action="store_true")
    p.set_defaults(fn=cmd_events)
