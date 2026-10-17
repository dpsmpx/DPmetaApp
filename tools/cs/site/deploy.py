"""Выгрузка рабочего сайта на хостинг (Д35, Д36; docs/platforms/WEBSITE.md, «Выгрузка»).

Хостинг — Beget, выгрузка — rsync по SSH готовой production-сборки.
Адрес (`логин@сервер:путь`) — в переменной окружения автора из
`deploy.target_ref` (в Git его нет), ключ — в его SSH-агенте; SSH — с
`BatchMode=yes`: без ключа отказ, а не вопрос пароля. Путь обязан
кончаться `deploy.guard_suffix` (`public_html/`): `--delete` не тронет
другой каталог по ошибке в адресе. Из `deploy.protect` rsync ничего не
удаляет (`.well-known/` — выпуск сертификата).

Кто может выгружать (Д36): автор; из сессии Claude Code — только
локальной и только для сайта (`local_session_production` политики);
облачная — никогда. Выпуск открывает автор (Д34, с 05.10.2026 —
`environments.production.live: true`); при `false` рабочая выгрузка отказывает.

Водяной знак (Д41, `deploy.mark.required`): перед rsync сборка получает
метку ключом автора (`tools/cs/mark/`); нет ключа — отказ. После выгрузки —
манифест выпуска в `data/provenance/site-releases/` и метки времени на него;
нет сети — выгрузка не падает, метки ставит `mark stamp`.
"""
from __future__ import annotations

import json
import re
import subprocess
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

from ..core.credentials import EnvCredentialStore
from ..core.obs import is_ai_session, is_remote_session
from ..core.repo import Repo

TARGET = re.compile(r"^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+:(?P<path>[^\s]+)$")


@dataclass
class DeployResult:
    ok: bool
    command: list = field(default_factory=list)
    changes: list = field(default_factory=list)
    problems: list = field(default_factory=list)
    output: str = ""
    release: Path | None = None  # манифест выпуска (Д41)
    stamps: list = field(default_factory=list)  # метки времени на манифест
    marked: int = 0  # файлов с меткой


def env_config(repo: Repo, env: str = "production") -> dict:
    return ((repo.site().get("environments") or {}).get(env) or {})


def config(repo: Repo, env: str = "production") -> dict | None:
    return env_config(repo, env).get("deploy") or None


def session_may_deploy(repo: Repo, platform: str = "website") -> str | None:
    """None — можно; иначе причина отказа (Д26, Д36)."""
    if not is_ai_session():
        return None
    if is_remote_session():
        return "из облачной сессии ИИ рабочая выгрузка запрещена (Д36): ключа здесь нет — запускает автор или локальная сессия"
    if platform not in (repo.policy().get("local_session_production") or []):
        return f"рабочая публикация на {platform} из сессии ИИ запрещена (Д26) — запускает автор у себя"
    return None


def guard(repo: Repo, env: str = "production", store: EnvCredentialStore | None = None,
          dry_run: bool = False) -> list[str]:
    """Почему выгружать нельзя (пусто — можно). dry_run — показ изменений, не выпуск:
    live и вид сессии не мешают, нужны настройка и адрес."""
    out = []
    ecfg, cfg = env_config(repo, env), config(repo, env)
    if not cfg:
        return [f"выгрузка {env} не настроена (data/website/site.yaml, deploy)"]
    if not dry_run:
        if env == "production" and not ecfg.get("live"):
            out.append("выпуск сайта закрыт: environments.production.live: false (Д34 — открывает автор)")
        why = session_may_deploy(repo)
        if why:
            out.append(why)
    if cfg.get("kind") == "rsync":
        target = (store or EnvCredentialStore()).get(cfg.get("target_ref"))
        if not target:
            out.append(f"нет адреса выгрузки: переменная {cfg.get('target_ref')} (docs/operations/CREDENTIALS.md, «Сайт: Beget»)")
        else:
            out += target_problems(str(target), cfg)
    elif cfg.get("kind") != "command":
        out.append(f"deploy.kind {cfg.get('kind')} — нужно rsync или command")
    return out


def target_problems(target: str, cfg: dict) -> list[str]:
    m = TARGET.match(target)
    if not m:
        return ["адрес выгрузки — не вида логин@сервер:путь"]
    suffix = cfg.get("guard_suffix") or ""
    path = m.group("path")
    if suffix and not (path if path.endswith("/") else path + "/").endswith(suffix):
        return [f"путь выгрузки не кончается на {suffix} — --delete не запускаю"]
    return []


def rsync_argv(src: Path, target: str, cfg: dict, dry_run: bool = False) -> list[str]:
    argv = ["rsync", "-rlz", "--checksum", "--delete-delay", "--delay-updates", "--itemize-changes",
            "-e", "ssh -o BatchMode=yes"]
    for p in cfg.get("protect") or []:
        argv.append(f"--filter=P {p}")
    if dry_run:
        argv.append("--dry-run")
    dest = target if target.endswith("/") else target + "/"
    return argv + [f"{src}/", dest]


def mark_sidecar(src: Path) -> Path:
    """Записи метки сборки — рядом с каталогом сборки, не в нём (на хостинг не уходят)."""
    return src.with_name(src.name + ".mark.json")


def mark_build(src: Path, key: bytes) -> list[dict]:
    """Метка сборки ровно один раз: сборка уже с меткой (те же sha256, что записаны рядом) — не трогать."""
    from ..mark import release
    side = mark_sidecar(src)
    if side.is_file():
        files = json.loads(side.read_text(encoding="utf-8"))
        have = {p.relative_to(src).as_posix() for p in src.rglob("*") if p.is_file()}
        if have == {f["path"] for f in files} and all(
                release.sha256((src / f["path"]).read_bytes()) == f["sha256"] for f in files):
            return files
    files = release.mark_build(src, key)
    side.write_text(json.dumps(files, ensure_ascii=False), encoding="utf-8")
    return files


def run(repo: Repo, src: Path, env: str = "production", dry_run: bool = False,
        store: EnvCredentialStore | None = None, runner=subprocess.run, now=None,
        post=None, stamp_runner=subprocess.run) -> DeployResult:
    """Выгрузить готовую сборку src. dry_run — только показать изменения (rsync --dry-run)."""
    problems = guard(repo, env, store, dry_run)
    if problems:
        return DeployResult(False, problems=problems)
    cfg = config(repo, env)
    mcfg = cfg.get("mark") or {}
    files, key = None, None
    if mcfg.get("required"):
        from ..mark import key as mkey
        key = mkey.load()
        if key is None:
            return DeployResult(False, problems=[f"нет ключа метки (Д41): {mkey.key_path()} — один раз на машине автора: "
                                                 "python3 -m tools.cs mark init"])
        files = mark_build(src, key)  # повторяемо: пробный прогон видит те же байты, что и выгрузка
    if cfg.get("kind") == "command":
        if dry_run:
            return DeployResult(True, list(cfg.get("command") or []), problems=["kind: command — показа изменений нет"])
        argv = list(cfg.get("command") or [])
    else:
        argv = rsync_argv(src, str((store or EnvCredentialStore()).get(cfg["target_ref"])), cfg, dry_run)
    r = runner(argv, cwd=repo.root, capture_output=True, text=True)
    shown = [a if not TARGET.match(a.rstrip("/")) else "<адрес выгрузки>" for a in argv]
    changes = [ln for ln in (r.stdout or "").splitlines() if ln[:1] in "<>ch*." and ln.strip()]
    if r.returncode != 0:
        return DeployResult(False, shown, changes, [f"выгрузка: код {r.returncode}"], (r.stderr or "")[-2000:])
    res = DeployResult(True, shown, changes, output=(r.stdout or "")[-4000:],
                       marked=sum(1 for f in files or [] if f.get("method")))
    if files is not None and not dry_run:
        res.release, res.stamps = record_release(repo, env, files, key, mcfg, now, post, stamp_runner)
    return res


def record_release(repo: Repo, env: str, files: list[dict], key: bytes, mcfg: dict, now=None, post=None,
                   stamp_runner=subprocess.run) -> tuple[Path, list]:
    """Манифест выпуска и метки времени на него (Д41): ни сеть, ни служба выгрузку не роняют."""
    import urllib.request
    from ..mark import release, stamp
    m = release.manifest(files, key, repo.site_base(env), release.git_head(repo.root), release.release_id(now))
    mp = release.write_manifest(repo.root, m)
    stamps = stamp.stamp_all(mp, mcfg.get("tsa") or stamp.TSA_DEFAULT, post or urllib.request.urlopen, stamp_runner)
    return mp, stamps


def verify(base: str, paths: list[str], fetch=urllib.request.urlopen, timeout: float = 15) -> dict[str, bool | None]:
    """Страницы на хостинге: True — отдаётся и это наша страница (canonical), False — 404/чужая, None — не дошли."""
    out = {}
    for path in paths:
        url = base.rstrip("/") + path
        try:
            with fetch(urllib.request.Request(url, headers={"User-Agent": "dpsmpx-cs-verify"}), timeout=timeout) as r:
                body = r.read(200_000).decode("utf-8", "replace")
                out[path] = r.status == 200 and f'rel="canonical" href="{url}"' in body
        except urllib.error.HTTPError as e:
            out[path] = False if e.code in (404, 410) else None
        except (urllib.error.URLError, OSError):
            out[path] = None
    return out
