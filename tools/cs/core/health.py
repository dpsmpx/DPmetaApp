"""Проверка здоровья (PLATFORM_VISION §107): площадки, учётные данные, сайт, хранилище, ИИ.

Только чтение и локальные проверки; сеть — только с --remote (сверка
аккаунта адаптером), и не из сессии ИИ для рабочих адресатов.
"""
from __future__ import annotations

import datetime as _dt
import subprocess

from .credentials import EnvCredentialStore, ref_environment
from .ledger import Ledger
from .repo import Repo


def report(repo: Repo, remote: bool = False) -> list[tuple[str, str, str]]:
    """(раздел, что, состояние)."""
    out = []
    store = EnvCredentialStore(meta_file=repo.path("local", "credentials.yaml"))
    caps = repo.capabilities()
    for d in repo.destinations().values():
        cap = caps.get(d["platform"], {})
        st = d.get("status")
        line = f"{st}; автоматизация: {cap.get('automation', '?')}"
        if d.get("blocked_by"):
            line += f"; ждёт: {', '.join(d['blocked_by'])}"
        out.append(("площадка", d["id"], line))
        if d.get("credential_ref"):
            env = ref_environment(d["credential_ref"])
            mark = "" if env == d["environment"] else f" (имя для {env}, адресат {d['environment']} — ошибка)"
            out.append(("учётные данные", d["credential_ref"], store.health(d["credential_ref"]) + mark))
        if remote and d.get("status") == "active" and d["environment"] != "production":
            from ..adapters import adapter_class
            try:
                problems = adapter_class(d["platform"])(d, repo, store).check_identity()
                out.append(("сверка аккаунта", d["id"], "ок" if not problems else "; ".join(problems)))
            except Exception as e:  # noqa: BLE001 — показать любую ошибку адаптера
                out.append(("сверка аккаунта", d["id"], f"ошибка: {getattr(e, 'code', type(e).__name__)}"))
    try:
        from ..site.build import build
        rep = build(repo, "staging", repo.path("build", "health-site"))
        out.append(("сайт", "staging-сборка", f"страниц {len(rep.pages)}, ошибок {len(rep.errors)}, предупреждений {len(rep.warnings)}"))
        prod = build(repo, "production", repo.path("build", "health-site-prod"))
        out.append(("сайт", "production-сборка", f"страниц {len(prod.pages)}, записей {len(prod.entries)}; деплой: "
                    + ("настроен" if ((repo.site().get('environments') or {}).get('production') or {}).get("deploy") else "не настроен (ОР-11)")))
    except Exception as e:  # noqa: BLE001
        out.append(("сайт", "сборка", f"ошибка: {e}"))
    r = subprocess.run(["git", "status", "--porcelain"], cwd=repo.root, capture_output=True, text=True)
    out.append(("хранилище", "git", "чисто" if not r.stdout.strip() else f"незакоммиченных файлов: {len(r.stdout.splitlines())}"))
    r = subprocess.run(["git", "log", "-1", "--format=%cs"], cwd=repo.root, capture_output=True, text=True)
    out.append(("хранилище", "последний коммит", r.stdout.strip() or "?"))
    backups = sorted(repo.path("build", "export").glob("dpsmpx-content-*.tar.gz"))
    out.append(("хранилище", "локальная выгрузка", backups[-1].name if backups else "нет (python3 -m tools.cs bundle)"))
    for env in ("production", "staging"):
        unknown = [p.id for p in Ledger(repo, env).publications().values() if p.state in ("PUBLISHING", "UNKNOWN")]
        out.append(("журнал", env, f"исход неизвестен: {', '.join(unknown)}" if unknown else "без незавершённых"))
    out.append(("ИИ", "поставщик", "session (Claude Code в репозитории); внешних API нет — офлайн-ядро не зависит от ИИ"))
    out.append(("дата", "проверка", _dt.date.today().isoformat()))
    return out
