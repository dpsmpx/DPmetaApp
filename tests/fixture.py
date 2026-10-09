"""Синтетическая экосистема для тестов: репозитории git с заданными датами.

Клоны создаются здесь же, в каталоге теста; содержимое других
репозиториев не копируется (CLAUDE.md, «Другие репозитории»).
"""

from __future__ import annotations

import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from tools.dpvis import model as modelmod


def utc(text: str) -> datetime:
    return datetime.fromisoformat(text).replace(tzinfo=timezone.utc)


class Repo:
    """Репозиторий-источник: коммиты с явным временем, автором и веткой."""

    def __init__(self, path: Path):
        self.path = path
        path.mkdir(parents=True)
        self.count = 0
        self.git("init", "-q", "-b", "main")
        self.git("config", "commit.gpgsign", "false")

    def git(self, *args: str, when: datetime | None = None, author: str = "dpsmpx") -> str:
        env = dict(os.environ)
        email = "noreply@anthropic.com" if author == "Claude" else "dpsmpx@example.invalid"
        env.update(GIT_AUTHOR_NAME=author, GIT_AUTHOR_EMAIL=email,
                   GIT_COMMITTER_NAME=author, GIT_COMMITTER_EMAIL=email)
        if when is not None:
            stamp = when.strftime("%Y-%m-%dT%H:%M:%S+0000")
            env.update(GIT_AUTHOR_DATE=stamp, GIT_COMMITTER_DATE=stamp)
        proc = subprocess.run(["git", "-C", str(self.path), *args], env=env,
                              capture_output=True, text=True, check=True)
        return proc.stdout

    def commit(self, when: str, author: str = "dpsmpx", message: str = "правка",
               files: dict[str, str] | None = None) -> None:
        self.count += 1
        files = files or {f"f{self.count:03d}.txt": message}
        for name, text in files.items():
            target = self.path / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text, encoding="utf-8")
            self.git("add", name)
        self.git("commit", "-q", "-m", message, when=utc(when), author=author)

    def branch(self, name: str) -> None:
        self.git("checkout", "-q", "-b", name)

    def checkout(self, name: str) -> None:
        self.git("checkout", "-q", name)

    def merge(self, branch: str, when: str) -> None:
        self.git("merge", "-q", "--no-ff", "-m", f"Merge pull request from {branch}", branch,
                 when=utc(when))

    def clone_to(self, dest: Path) -> Path:
        subprocess.run(["git", "clone", "-q", "--no-hardlinks", str(self.path), str(dest)],
                       check=True, capture_output=True)
        return dest


def model_from(src: dict) -> dict:
    """Модель из словаря-источника (как TOML) — через ту же проверку и сборку."""
    return modelmod.compile_model(src, repr(src).encode("utf-8"))


def base_source() -> dict:
    """Маленькая модель: автор, сессии, два репозитория, сайт с пробой."""
    return {
        "schema": 1,
        "title": "Тестовая экосистема",
        "as_of": "2026-10-05",
        "timezone": "+03:00",
        "allow": {"hosts": []},
        "registry": {"node": "beta", "path": "tools/ecosystem.toml", "contracts": "docs/CONTRACTS.md"},
        "layer": [{"id": "actors", "name": "Участники"}, {"id": "product", "name": "Продукты"},
                  {"id": "infra", "name": "Инфраструктура"}],
        "node": [
            {"id": "author", "name": "Автор", "kind": "actor", "layer": "actors",
             "role": "решения", "refs": ["Beta:README.md"]},
            {"id": "claude", "name": "Сессии", "kind": "actor", "layer": "actors",
             "role": "работа", "refs": ["Beta:README.md"]},
            {"id": "alpha", "name": "Alpha", "kind": "repo", "layer": "product",
             "repo": "owner/Alpha", "group": "core", "visibility": "private",
             "role": "продукт", "refs": ["Alpha:README.md"]},
            {"id": "beta", "name": "Beta", "kind": "repo", "layer": "product",
             "repo": "owner/Beta", "group": "core", "visibility": "undisclosed",
             "role": "реестр", "refs": ["Beta:README.md"]},
            {"id": "ghost", "name": "Ghost", "kind": "repo", "layer": "product",
             "repo": "owner/Ghost", "group": "new", "visibility": "public",
             "role": "нет клона", "refs": ["Alpha:README.md"]},
            {"id": "site", "name": "Сайт", "kind": "service", "layer": "infra",
             "role": "сайт", "refs": ["Beta:README.md"]},
            {"id": "phone", "name": "Телефон", "kind": "device", "layer": "infra",
             "role": "устройство", "refs": ["Beta:README.md"]},
        ],
        "flow": [
            {"id": "work.alpha", "from": "claude", "to": "alpha", "kind": "work", "what": "работа",
             "transport": "git", "refs": ["Alpha:README.md"],
             "signal": {"type": "git-commits", "repo": "alpha", "who": "ai", "scope": "all"}},
            {"id": "decide.alpha", "from": "author", "to": "alpha", "kind": "decision", "what": "вливание",
             "transport": "git", "refs": ["Alpha:README.md"], "contracts": ["DPmeta:C-01"],
             "signal": {"type": "git-commits", "repo": "alpha", "who": "merge"}},
            {"id": "facts.alpha.beta", "from": "alpha", "to": "beta", "kind": "facts", "what": "факты",
             "transport": "clone", "contracts": ["DPmeta:C-09"],
             "signal": {"type": "git-path", "repo": "beta", "paths": ["docs/facts/"]}},
            {"id": "plan.beta.site", "from": "beta", "to": "site", "kind": "deploy", "what": "выгрузка",
             "transport": "rsync", "status": "planned", "refs": ["Beta:README.md"]},
            {"id": "manual.phone", "from": "author", "to": "phone", "kind": "decision", "what": "«да»",
             "transport": "manual", "refs": ["Beta:README.md"]},
            {"id": "kit.beta.site", "from": "beta", "to": "site", "kind": "format", "what": "комплект схемы",
             "transport": "copy", "refs": ["Beta:README.md"]},
        ],
    }


REGISTRY = """schema = 1

[[repo]]
name = "Alpha"
slug = "owner/Alpha"

[[repo]]
name = "Beta"
slug = "owner/Beta"

[[repo]]
name = "Gamma"
slug = "owner/Gamma"
"""

CONTRACTS = """# Контракты

## C-01 — первый

текст

## C-02 — второй

текст
"""


def build_ecosystem(tmp: Path) -> Path:
    """Источники и клоны Alpha и Beta; возвращает каталог клонов.

    Время среза в тестах — 2026-10-05T12:00:00Z (15:00 по +03:00).
    Alpha: ИИ-коммиты 3 и 4 октября, человек 1 октября, вливание
    5 октября в 10:00Z (2 ч до среза), ветка claude/feature с одним
    невлитым коммитом 5 октября в 11:00Z и коммит после среза.
    Beta: реестр и контракты, факт 20 сентября.
    """
    src = tmp / "src"
    alpha = Repo(src / "Alpha")
    alpha.commit("2026-10-01T08:00:00", author="dpsmpx", message="начало")
    alpha.commit("2026-10-03T08:00:00", author="Claude", message="работа ИИ один секрет-текст-коммита")
    alpha.branch("claude/merged")
    alpha.commit("2026-10-04T08:00:00", author="dpsmpx",
                 message="соавторство\n\nCo-Authored-By: Claude <noreply@anthropic.com>")
    alpha.checkout("main")
    alpha.merge("claude/merged", "2026-10-05T10:00:00")
    alpha.branch("claude/feature")
    alpha.commit("2026-10-05T11:00:00", author="Claude", message="невлитая работа")
    alpha.checkout("main")
    alpha.commit("2026-10-05T13:00:00", author="dpsmpx", message="после среза")

    beta = Repo(src / "Beta")
    beta.commit("2026-09-20T08:00:00", author="Claude", message="факт",
                files={"docs/facts/F-0001.md": "факт"})
    beta.commit("2026-09-21T08:00:00", author="dpsmpx", message="реестр",
                files={"tools/ecosystem.toml": REGISTRY, "docs/CONTRACTS.md": CONTRACTS})

    clones = tmp / "clones"
    clones.mkdir()
    alpha.clone_to(clones / "alpha")
    beta.clone_to(clones / "Beta")
    return clones
