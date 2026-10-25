"""Чтение клонов git — только чтение, без рабочего дерева (D-03, ТЗ §6.3).

Читается основная ветка клона (`origin/HEAD`, иначе `main`, `master`) и
ветки `origin/*`; текст коммита нужен только для признака ИИ и не
сохраняется (D-08).
"""

from __future__ import annotations

import os
import subprocess
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

from . import rules

_ENV = dict(os.environ, GIT_TERMINAL_PROMPT="0", LC_ALL="C", GIT_OPTIONAL_LOCKS="0")


class GitError(Exception):
    """Команда git не удалась; текст — команда и её stderr."""


def git(repo: Path, *args: str, timeout: int = rules.GIT_TIMEOUT_S) -> str:
    try:
        proc = subprocess.run(
            ["git", "-C", str(repo), *args],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=timeout, env=_ENV, check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise GitError(f"git {' '.join(args)}: {exc}") from exc
    if proc.returncode != 0:
        raise GitError(f"git {' '.join(args)}: {proc.stderr.strip()[:300]}")
    return proc.stdout


def is_clone(path: Path) -> bool:
    try:
        return git(path, "rev-parse", "--is-inside-work-tree", "--git-dir").strip() != ""
    except GitError:
        return False


def default_ref(repo: Path) -> str | None:
    """Основная ветка: origin/HEAD, затем origin/main, origin/master, main, master."""
    try:
        ref = git(repo, "symbolic-ref", "-q", "refs/remotes/origin/HEAD").strip()
        if ref:
            return ref.removeprefix("refs/remotes/")
    except GitError:
        pass
    for candidate in ("refs/remotes/origin/main", "refs/remotes/origin/master",
                      "refs/heads/main", "refs/heads/master"):
        try:
            git(repo, "rev-parse", "--verify", "-q", candidate)
            return candidate.removeprefix("refs/remotes/").removeprefix("refs/heads/")
        except GitError:
            continue
    return None


def branch_refs(repo: Path) -> list[str]:
    """Ветки клона: `origin/*` без HEAD; в клоне без удалённых — локальные."""
    out = git(repo, "for-each-ref", "--format=%(refname)", "refs/remotes/origin")
    refs = [line.removeprefix("refs/remotes/") for line in out.split()
            if line != "refs/remotes/origin/HEAD"]
    if not refs:
        out = git(repo, "for-each-ref", "--format=%(refname)", "refs/heads")
        refs = [line.removeprefix("refs/heads/") for line in out.split()]
    return sorted(refs)


@dataclass
class Commit:
    sha: str
    time: datetime          # время коммиттера, UTC
    parents: int
    ai: bool

    @property
    def merge(self) -> bool:
        return self.parents >= 2


_SEP_FIELD = "\x1f"
_SEP_RECORD = "\x1e"


def commits(repo: Path, refs: list[str], since: datetime | None = None,
            paths: list[str] | None = None, first_parent: bool = False) -> list[Commit]:
    """Коммиты, достижимые из refs (без повторов), новые первыми."""
    if not refs:
        return []
    args = ["log", "--format=%H%x1f%P%x1f%an%x1f%ct%x1f%B%x1e"]
    if since is not None:
        args.append(f"--since={int(since.timestamp())}")
    if first_parent:
        args.append("--first-parent")
    args.extend(refs)
    args.append("--")
    if paths:
        args.extend(paths)
    out = git(repo, *args)
    result = []
    for record in out.split(_SEP_RECORD):
        record = record.strip("\n")
        if not record:
            continue
        parts = record.split(_SEP_FIELD, 4)
        if len(parts) < 5:
            continue
        sha, parents, author, ctime, body = parts
        ai = author.strip() in rules.AI_AUTHORS or bool(rules.AI_TRAILER.search(body))
        result.append(Commit(
            sha=sha,
            time=datetime.fromtimestamp(int(ctime), tz=timezone.utc),
            parents=len(parents.split()),
            ai=ai,
        ))
    return result


def first_commit_time(repo: Path, ref: str, skip: frozenset[str] = frozenset()) -> datetime | None:
    """Время первого коммита основной ветки; коммиты skip (влитая история, D-24) — не в счёт."""
    out = git(repo, "log", "--reverse", "--format=%H %ct", ref)
    for line in out.splitlines():
        sha, _, ct = line.partition(" ")
        if sha and sha not in skip:
            return datetime.fromtimestamp(int(ct), tz=timezone.utc)
    return None


_OWN, _FOREIGN = 1, 2


def foreign(repo: Path, refs: list[str], roots: set[str]) -> tuple[frozenset[str], set[str]]:
    """Коммиты влитой истории (D-24): их история сходится только к корням roots.

    Корень (коммит без родителей) из roots — чужой, любой другой — свой;
    коммит наследует пометки родителей. Чужой — тот, у кого только чужая
    пометка: коммиты влитого репозитория до слияния. Слияние и всё после
    него видит оба корня — это работа самого репозитория. Родитель за
    границей неполного клона — свой: такой коммит считается (занизить
    чужое безопаснее, чем потерять своё). Второе значение — корни roots,
    которых нет среди корней доступной истории (опечатка, неполный клон).
    """
    if not roots or not refs:
        return frozenset(), set()
    out = git(repo, "rev-list", "--parents", "--topo-order", "--reverse", *refs)
    marks: dict[str, int] = {}
    seen_roots: set[str] = set()
    for line in out.splitlines():
        sha, *parents = line.split()
        if not parents:
            marks[sha] = _FOREIGN if sha in roots else _OWN
            seen_roots.add(sha)
            continue
        mark = 0
        for p in parents:
            mark |= marks.get(p, _OWN)
        marks[sha] = mark
    return frozenset(sha for sha, mark in marks.items() if mark == _FOREIGN), set(roots) - seen_roots


def is_shallow(repo: Path) -> bool:
    try:
        return git(repo, "rev-parse", "--is-shallow-repository").strip() == "true"
    except GitError:
        return False


@dataclass
class Branch:
    name: str
    ahead: int
    time: datetime
    method: str             # cherry — по изменениям; rev-list — по коммитам


def unmerged_branches(repo: Path, main: str, skip: frozenset[str] = frozenset()) -> list[Branch]:
    """Ветки с работой, которой нет в основной (`DPmeta:M-08`).

    Сначала `rev-list --count main..ветка`; если там что-то есть —
    `git cherry`: коммит, чьё изменение уже есть в основной под другим
    хешем, не считается невлитым. `git cherry` в неполном клоне может
    потребовать догрузки — тогда остаётся счёт по коммитам. Ветка, чья
    вершина — во влитой истории (skip, D-24), не считается.
    """
    result = []
    for ref in branch_refs(repo):
        if ref == main:
            continue
        try:
            ahead = int(git(repo, "rev-list", "--count", f"{main}..{ref}").strip() or 0)
        except GitError:
            continue
        if ahead == 0:
            continue
        method = "rev-list"
        try:
            out = git(repo, "cherry", main, ref, timeout=rules.CHERRY_TIMEOUT_S)
            ahead = sum(1 for line in out.splitlines() if line.startswith("+"))
            method = "cherry"
        except GitError:
            pass
        if ahead == 0:
            continue
        tip_sha, _, tip_ct = git(repo, "log", "-1", "--format=%H %ct", ref).strip().partition(" ")
        if tip_sha in skip:
            continue
        tip = int(tip_ct)
        name = ref.removeprefix("origin/")
        result.append(Branch(name=name, ahead=ahead,
                             time=datetime.fromtimestamp(tip, tz=timezone.utc), method=method))
    result.sort(key=lambda b: (-b.time.timestamp(), b.name))
    return result


def show(repo: Path, ref: str, path: str) -> str:
    """Файл из ветки (а не из рабочего дерева)."""
    return git(repo, "show", f"{ref}:{path}")


def fetch(repo: Path) -> None:
    git(repo, "fetch", "--prune", "--quiet", "origin", timeout=rules.FETCH_TIMEOUT_S)
