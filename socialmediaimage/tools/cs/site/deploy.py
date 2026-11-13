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

Вид `command` (Д55): команда выгрузки — у автора, а не в репозитории. В
`site.yaml` — только `command_ref`, имя переменной окружения
`CS_<ОКР>_SITE_DEPLOY_COMMAND` (окружение — как у выгрузки); значение —
массив JSON аргументов, первый — абсолютный путь программы. Команда
исполняется без оболочки, в выводе не печатается; список аргументов в самом
`site.yaml` (`command`) не исполняется никогда.

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

from ..core.credentials import EnvCredentialStore, ref_environment
from ..core.obs import is_ai_session, is_remote_session
from ..core.repo import Repo

TARGET = re.compile(r"^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+:(?P<path>[^\s]+)$")
COMMAND_REF = re.compile(r"^CS_(DEV|STAGING|PROD)_SITE_DEPLOY_COMMAND$")
SHOWN_COMMAND = "<команда выгрузки автора>"
MAX_COMMAND_ARGS = 64


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
    if (cfg.get("mark") or {}).get("required"):
        from ..mark import key as mkey
        try:
            if mkey.load() is None:
                out.append(f"нет ключа метки (Д41): {mkey.key_path()} — один раз на машине автора: "
                           "python3 -m tools.cs mark init")
        except ValueError as e:
            out.append(f"ключ метки не читается: {e}")
    if cfg.get("kind") == "rsync":
        target = (store or EnvCredentialStore()).get(cfg.get("target_ref"))
        if not target:
            out.append(f"нет адреса выгрузки: переменная {cfg.get('target_ref')} (docs/operations/CREDENTIALS.md, «Сайт: Beget»)")
        else:
            out += target_problems(target.reveal(), cfg)
    elif cfg.get("kind") == "command":
        out += author_command(cfg, env, store)[1]
    else:
        out.append("deploy.kind — rsync или command с командой автора (Д55)")
    return out


def author_command(cfg: dict, env: str, store: EnvCredentialStore | None = None) -> tuple[list[str] | None, list[str]]:
    """Команда выгрузки автора (Д55): аргументы и пусто — или None и почему нельзя.
    Список в самом site.yaml не исполняется; значение переменной — массив JSON строк,
    первая — абсолютный путь программы; окружение ссылки — то же, что у выгрузки."""
    if "command" in cfg:
        return None, ["deploy.command в site.yaml не исполняется (Д55): команда — у автора, в конфигурации — только command_ref"]
    ref = cfg.get("command_ref")
    if not isinstance(ref, str) or not COMMAND_REF.match(ref):
        return None, ["deploy.command_ref — имя переменной CS_<ОКР>_SITE_DEPLOY_COMMAND (Д55)"]
    if ref_environment(ref) != env:
        return None, [f"deploy.command_ref {ref} — не для окружения {env}"]
    value = (store or EnvCredentialStore()).get(ref)
    if value is None:
        return None, [f"нет команды выгрузки: переменная {ref} (docs/operations/CREDENTIALS.md, «Сайт: команда выгрузки»)"]
    try:
        argv = json.loads(value.reveal())
    except ValueError:
        return None, [f"переменная {ref} — не массив JSON"]
    if (not isinstance(argv, list) or not argv or len(argv) > MAX_COMMAND_ARGS
            or not all(isinstance(a, str) and a and "\x00" not in a for a in argv)):
        return None, [f"переменная {ref} — массив JSON непустых строк, не длиннее {MAX_COMMAND_ARGS}"]
    if not argv[0].startswith("/"):
        return None, [f"переменная {ref}: программа — абсолютным путём, не из PATH"]
    return argv, []


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


def mark_build(src: Path, key: bytes, objs: dict | None = None) -> list[dict]:
    """Метка сборки ровно один раз: сборка уже с меткой (те же sha256, что записаны рядом) — не трогать."""
    from ..mark import release
    side = mark_sidecar(src)
    if side.is_file():
        files = json.loads(side.read_text(encoding="utf-8"))
        have = {p.relative_to(src).as_posix() for p in src.rglob("*") if p.is_file()}
        if have == {f["path"] for f in files} and all(
                release.sha256((src / f["path"]).read_bytes()) == f["sha256"] for f in files):
            return files
    files = release.mark_build(src, key, objs)
    side.write_text(json.dumps(files, ensure_ascii=False), encoding="utf-8")
    return files


def run(repo: Repo, src: Path, env: str = "production", dry_run: bool = False,
        store: EnvCredentialStore | None = None, runner=subprocess.run, now=None,
        post=None, stamp_runner=subprocess.run, objects: dict | None = None) -> DeployResult:
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
        files = mark_build(src, key, objects)  # повторяемо: пробный прогон видит те же байты, что и выгрузка
    if cfg.get("kind") == "command":
        argv, why = author_command(cfg, env, store)
        if argv is None:
            return DeployResult(False, problems=why)
        if dry_run:
            return DeployResult(True, [SHOWN_COMMAND], problems=["kind: command — показа изменений нет"])
        r = runner(argv, cwd=repo.root, capture_output=True, text=True)
        if r.returncode != 0:
            return DeployResult(False, [SHOWN_COMMAND], [], [f"выгрузка: код {r.returncode}"], (r.stderr or "")[-2000:])
        res = DeployResult(True, [SHOWN_COMMAND], [], output=(r.stdout or "")[-4000:],
                           marked=sum(1 for f in files or [] if f.get("method")))
        if files is not None:
            try:  # команда уже прошла: сбой записи манифеста выгрузку не роняет (Д41)
                res.release, res.stamps = record_release(repo, env, files, key, mcfg, now, post, stamp_runner)
            except Exception as e:
                res.problems.append(f"манифест выпуска не записан ({type(e).__name__}: {e}) — сайт выгружен")
        return res
    target = (store or EnvCredentialStore()).get(cfg["target_ref"])
    if target is None:
        return DeployResult(False, problems=["адрес выгрузки больше недоступен"])
    problems = target_problems(target.reveal(), cfg)
    if problems:
        return DeployResult(False, problems=problems)
    argv = rsync_argv(src, target.reveal(), cfg, dry_run)
    r = runner(argv, cwd=repo.root, capture_output=True, text=True)
    shown = [a if not TARGET.match(a.rstrip("/")) else "<адрес выгрузки>" for a in argv]
    changes = [ln for ln in (r.stdout or "").splitlines() if ln[:1] in "<>ch*." and ln.strip()]
    if r.returncode != 0:
        return DeployResult(False, shown, changes, [f"выгрузка: код {r.returncode}"], (r.stderr or "")[-2000:])
    res = DeployResult(True, shown, changes, output=(r.stdout or "")[-4000:],
                       marked=sum(1 for f in files or [] if f.get("method")))
    if files is not None and not dry_run:
        try:  # rsync уже прошёл: сбой записи манифеста или меток времени выгрузку не роняет (Д41)
            res.release, res.stamps = record_release(repo, env, files, key, mcfg, now, post, stamp_runner)
        except Exception as e:
            res.problems.append(f"манифест выпуска не записан ({type(e).__name__}: {e}) — сайт выгружен; "
                                "повторить выгрузку: rsync и метка повторяемы, манифест запишется")
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
