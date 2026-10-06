"""Переписывание истории Git: личные слова автора — из всех коммитов (Д40).

Запускает только автор у себя (`python3 -m tools.cs history rewrite`): нужен
его список личных слов (`secrets.private_words_path()`), которого в сессии
ИИ нет, и принудительный пуш всех веток. Порядок:

    1. зеркальная копия origin в рабочий каталог вне репозитория + резервная копия;
    2. проверка всех объектов: сколько совпадений (сами слова не печатаются);
    3. git filter-repo: слова в текстовых файлах и сообщениях коммитов → ***, в двоичных —
       звёздочки той же длины (размер и смещения внутри файла не меняются);
    4. повторная проверка всех объектов — ноль, иначе отказ без пуша;
    5. «переписать» → git push --force всех веток и тегов;
    6. commit-map (старый → новый хэш) — рядом, для ссылок из срезов (DPmeta).

Рабочий каталог хранит резервную копию со старой историей — после проверки
нового клона его удаляют (docs/SECURITY.md, §4).
"""
from __future__ import annotations

import datetime as dt
import os
import shutil
import subprocess
import sys
from pathlib import Path

from . import secrets
from .common import ROOT

MASK = "***"
CONFIRM = "переписать"


def variants(word: str) -> list[str]:
    """Написания основы для побайтовой замены: filter-repo сравнивает байты, регистр — вручную.

    Слова списка нормализованы (нижний регистр, ё = е) — для «е» добавляется и «ё».
    """
    forms = {word}
    if "е" in word:
        forms |= {word.replace("е", "ё", 1), word.replace("е", "ё")}
    out = set()
    for f in forms:
        out |= {f, f.capitalize(), f.upper()}
    return sorted(out, key=lambda x: (-len(x), x))


def expressions(words: list[str]) -> str:
    """Файл правил git filter-repo --replace-message (сообщения коммитов и тегов)."""
    return "".join(f"literal:{v}==>{MASK}\n" for w in words for v in variants(w))


# Замена в содержимом файлов. --replace-text filter-repo пропускает двоичные файлы,
# поэтому — свой обработчик для всех: текст → ***, двоичный → «*» той же длины.
# Пары читаются из файла (PAIRS_ENV), а не из командной строки: в них сами слова.
PAIRS_ENV = "DPSMPX_REWRITE_PAIRS"
BLOB_CALLBACK = f"""
global _dpsmpx_pairs
try:
    _dpsmpx_pairs
except NameError:
    import json, os
    _dpsmpx_pairs = [bytes.fromhex(v) for v in json.load(open(os.environ["{PAIRS_ENV}"], encoding="utf-8"))]
data = blob.data
binary = b"\\0" in data[:8000]
for v in _dpsmpx_pairs:
    if v in data:
        data = data.replace(v, b"*" * len(v) if binary else {MASK.encode()!r})
blob.data = data
"""


def pairs(words: list[str]) -> str:
    import json
    return json.dumps([v.encode("utf-8").hex() for w in words for v in variants(w)])


def scan_objects(git_dir: Path, words: list[str]) -> dict[str, int]:
    """Совпадения во всех объектах репозитория (файлы, деревья, коммиты, теги): {тип: число объектов}.

    Слова не возвращаются — только счёт, чтобы вывод можно было показать сессии.
    """
    p = subprocess.run(["git", "--git-dir", str(git_dir), "cat-file", "--batch-all-objects", "--batch"],
                       capture_output=True, check=True)
    data, i, hits = p.stdout, 0, {}
    while i < len(data):
        nl = data.index(b"\n", i)
        _sha, kind, size = data[i:nl].split()
        start = nl + 1
        body = data[start:start + int(size)]
        i = start + int(size) + 1
        text = secrets.norm(body.decode("utf-8", "ignore"))
        if any(w in text for w in words):
            k = kind.decode()
            hits[k] = hits.get(k, 0) + 1
    return hits


def git(*args: str, cwd: Path | None = None, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True, check=check)


def filter_repo_available() -> bool:
    return git("filter-repo", "--version", check=False).returncode == 0


def rewrite(origin: str, workdir: Path, words: list[str], confirm=input, push: bool = True,
            say=print) -> int:
    """Весь порядок из описания модуля. Код: 0 — сделано (или нечего делать), 1 — отказ."""
    if not words:
        say("✗ список личных слов пуст — переписывать нечего искать")
        return 1
    workdir.mkdir(parents=True, exist_ok=True)
    os.chmod(workdir, 0o700)
    repo, backup = workdir / "repo.git", workdir / "backup.git"
    if repo.exists() or backup.exists():
        say(f"✗ {workdir} уже содержит копии — выберите другой каталог")
        return 1
    say("== зеркальная копия")
    r = git("clone", "--quiet", "--mirror", origin, str(repo), check=False)
    if r.returncode != 0:
        say("✗ копия не получилась (сеть или доступ к GitHub): " + r.stderr[-500:])
        return 1
    shutil.copytree(repo, backup, symlinks=True)
    before = scan_objects(repo, words)
    say(f"   совпадений до: {sum(before.values())} объектов {dict(sorted(before.items()))}")
    if not before:
        say("✓ в истории личных слов нет — переписывать нечего")
        return 0

    expr, pfile = workdir / "expressions.txt", workdir / "pairs.json"
    expr.write_text(expressions(words), encoding="utf-8")
    pfile.write_text(pairs(words), encoding="utf-8")
    os.chmod(expr, 0o600)
    os.chmod(pfile, 0o600)
    say("== git filter-repo")
    try:
        r = subprocess.run(["git", "filter-repo", "--force", "--blob-callback", BLOB_CALLBACK,
                            "--replace-message", str(expr)],
                           cwd=repo, capture_output=True, text=True, env={**os.environ, PAIRS_ENV: str(pfile)})
    finally:
        expr.unlink(missing_ok=True)  # в обоих файлах — сами слова
        pfile.unlink(missing_ok=True)
    if r.returncode != 0:
        say("✗ filter-repo: " + (r.stderr or r.stdout)[-1500:])
        return 1

    after = scan_objects(repo, words)
    say(f"   совпадений после: {sum(after.values())} объектов {dict(sorted(after.items()))}")
    if after:
        say("✗ после замены совпадения остались (другое написание или имя в пути файла) — пуша не будет")
        return 1
    cmap = repo / "filter-repo" / "commit-map"
    if cmap.is_file():
        shutil.copyfile(cmap, workdir / "commit-map.txt")
        say(f"   соответствие хэшей: {workdir / 'commit-map.txt'}")
    heads = git("for-each-ref", "--format=%(refname)", "refs/heads", "refs/tags", cwd=repo).stdout.split()
    say(f"   ветки и теги к пушу: {len(heads)}")
    if not push:
        return 0
    if confirm(f"Перезаписать историю на GitHub? Наберите «{CONFIRM}»: ").strip() != CONFIRM:
        say("отменено — GitHub не тронут; копии — в " + str(workdir))
        return 1
    say("== принудительный пуш")
    r = git("push", "--force", origin, "refs/heads/*:refs/heads/*", "refs/tags/*:refs/tags/*", cwd=repo, check=False)
    if r.returncode != 0:
        say("✗ пуш: " + (r.stderr or r.stdout)[-1500:])
        say(f"  резервная копия старой истории — {backup}")
        return 1
    say("✓ история на GitHub переписана")
    return 0


def next_steps(origin: str, workdir: Path, repo_dir: Path) -> str:
    return f"""
Дальше (docs/SECURITY.md, §4, «История с личными словами»):
  1. Новый клон вместо старого — в старом прежняя история:
       mv {repo_dir} {repo_dir}.old && git clone {origin} {repo_dir}
       cd {repo_dir} && tools/check.sh --quick
  2. Соответствие хэшей — в репозиторий (ссылки срезов DPmeta):
       mkdir -p data/history && cp {workdir}/commit-map.txt data/history/commit-map-{dt.date.today()}.txt
       git add data/history && git commit -m "История переписана (Д40): соответствие хэшей" && git push
  3. Когда новый клон проверен — удалить старое (там прежняя история):
       rm -rf {repo_dir}.old {workdir}
  4. GitHub хранит прежние коммиты в ссылках PR — запрос в поддержку GitHub
     на их удаление (support.github.com, «Remove sensitive data»).
  5. Другие сессии: ветки от прежней истории не вливать — пересобрать от нового main.
"""


def cmd_rewrite(a) -> int:
    from .core.obs import is_ai_session
    if is_ai_session():
        print("переписывание истории — только у автора (Д40): нужен его список личных слов и принудительный пуш")
        return 2
    if not sys.stdin.isatty():
        print("только интерактивно (терминал автора)")
        return 2
    words = secrets.private_words()
    if words is None:
        print(f"нет списка личных слов: {secrets.private_words_path()} (docs/SECURITY.md, §3)")
        return 2
    if not filter_repo_available():
        print("нет git filter-repo: python3 -m pip install git-filter-repo")
        return 2
    repo_dir = ROOT
    origin = git("remote", "get-url", "origin", cwd=repo_dir).stdout.strip()
    if git("status", "--porcelain", cwd=repo_dir).stdout.strip():
        print("есть незакоммиченное — закоммитить и запушить: новый клон его не унесёт")
        return 2
    local = git("for-each-ref", "--format=%(refname:short) %(upstream:track)", "refs/heads", cwd=repo_dir).stdout
    if "ahead" in local:
        print("есть незапушенные коммиты — сначала git push: после переписывания их не перенести")
        return 2
    workdir = Path(a.workdir).expanduser() if a.workdir else \
        Path.home() / f"dpsmpx-history-rewrite-{dt.datetime.now():%Y%m%d-%H%M}"
    if repo_dir in workdir.resolve().parents or workdir.resolve() == repo_dir:
        print("рабочий каталог — вне репозитория")
        return 2
    rc = rewrite(origin, workdir, words, push=not a.no_push)
    if rc == 0 and not a.no_push and (workdir / "commit-map.txt").is_file():
        print(next_steps(origin, workdir, repo_dir))
    return rc


def register(sub) -> None:
    p = sub.add_parser("history", help="история Git: переписать личные слова (Д40) — только автор")
    hs = p.add_subparsers(dest="action", required=True)
    r = hs.add_parser("rewrite", help="заменить слова списка личных слов во всей истории и запушить")
    r.add_argument("--workdir", help="рабочий каталог вне репозитория (по умолчанию ~/dpsmpx-history-rewrite-…)")
    r.add_argument("--no-push", action="store_true", help="только переписать копию и проверить, без пуша")
    r.set_defaults(fn=cmd_rewrite)
