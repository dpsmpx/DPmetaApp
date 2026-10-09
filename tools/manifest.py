#!/usr/bin/env python3
"""Манифест экосистемы `dpai.ecosystem` v1 для DP_AI_Orcestrator (M-11, C-13).

Строит из того, что DPmeta уже ведёт, документ, который читает адаптер
`ManifestEcosystem` оркестратора (`DP_AI_Orcestrator:docs/ECOSYSTEM.md`, §2):

- проекты — реестр tools/ecosystem.toml, кроме архива (`place = "archive"`,
  M-10); ключ — имя проекта строчными;
- состояние — место в экосистеме и, если рядом есть клон, основная ветка,
  её последний коммит и дата;
- документы — точки входа проекта (`CLAUDE.md`, `README.md`, `TODO.md`,
  журнал решений из реестра), которые есть в основной ветке клона; без
  клона — только журнал решений из реестра;
- предложения задач — открытые пункты docs/SYNC_QUEUE.md этого проекта:
  ключ `sq-NN`, заголовок, цель — поле «Предложение», приоритет — по риску,
  вид работы `docs`. Задачу ставит и одобряет оркестратор по своей
  политике; DPmeta её не исполняет (M-01, M-04).

Чтение у оркестратора строгое: лишних полей нет, ключи проектов не
повторяются. Без `--paths` поле `repository` не пишется: путь клона —
свойство устройства, а не экосистемы. Без сети; читает клоны (только
`git ls-tree` и `git log`), ничего не пишет, кроме файла из --out. Один и
тот же вход — одинаковые байты. Коды возврата: 0 — манифест построен;
2 — неверный вызов или реестр.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import tomllib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import activity  # noqa: E402
import xref  # noqa: E402

FORMAT = "dpai.ecosystem"
VERSION = 1
ENTRY_DOCS = ("CLAUDE.md", "README.md", "TODO.md")
# Риск пункта очереди → уровень `Priority` оркестратора (CRITICAL … BACKGROUND).
PRIORITY = {"высокий": "HIGH", "средний": "NORMAL", "низкий": "LOW"}
OPERATION = "docs"

ROW = re.compile(r"^\| (SQ-\d+) \| ([^|]+) \| ([^|]+) \| ([^|]+) \| ([^|]+) \|$")
HEAD = re.compile(r"^### (SQ-\d+) — (.+)$")
FIELD = re.compile(r"^- \*\*([^*]+):\*\* ?(.*)$")


def key_of(name: str) -> str:
    return re.sub(r"[^a-z0-9_-]+", "-", name.lower()).strip("-")


def plain(text: str) -> str:
    """Текст Markdown одной строкой: без обратных кавычек и звёздочек."""
    return re.sub(r"\s+", " ", text.replace("`", "").replace("**", "")).strip()


def is_open(state: str) -> bool:
    return state.strip().startswith(("открыт", "частично"))


def read_queue(text: str) -> dict[str, dict]:
    """Пункты очереди: номер → репозитории, заголовок, риск, состояние, предложение."""
    items: dict[str, dict] = {}
    for line in text.splitlines():
        m = ROW.match(line)
        if m:
            num, repos, title, risk, state = (g.strip() for g in m.groups())
            items[num] = {"repos": [r.strip() for r in repos.split(",")], "title": plain(title),
                          "risk": risk, "state": state, "proposal": ""}
    current, field_name, buf = None, None, []

    def flush():
        if current in items and field_name == "Предложение" and buf:
            items[current]["proposal"] = plain(" ".join(buf))

    for line in text.splitlines():
        h = HEAD.match(line)
        if h or line.startswith("## "):
            flush()
            current, field_name, buf = (h.group(1) if h else None), None, []
            continue
        f = FIELD.match(line)
        if f:
            flush()
            field_name, buf = f.group(1), [f.group(2)]
        elif field_name and line.startswith("  "):
            buf.append(line.strip())
        elif field_name:
            # пустая строка или другой пункт списка («Повторный проход» и т. п.) — поле кончилось
            flush()
            field_name, buf = None, []
    flush()
    return items


def priority(risk: str) -> str:
    word = risk.split()[0].strip(",(") if risk.split() else ""
    return PRIORITY.get(word, "NORMAL")


def suggestions(queue: dict[str, dict], name: str) -> list[dict]:
    out = []
    for num, item in sorted(queue.items(), key=lambda kv: int(kv[0].split("-")[1])):
        if name not in item["repos"] or not is_open(item["state"]):
            continue
        objective = item["proposal"] or item["title"]
        out.append({"key": num.lower(), "title": f"{num}: {item['title']}",
                    "objective": f"{objective} (DPmeta:{num}, состояние: {plain(item['state'])})",
                    "priority": priority(item["risk"]), "operation": OPERATION})
    return out


def has_path(root: Path, ref: str, path: str) -> bool:
    return bool(xref.git(root, "ls-tree", "--name-only", ref, "--", path).strip())


def project(repo: xref.Repo, root: Path | None, queue: dict[str, dict], paths: bool) -> dict:
    state = {"place": repo.place}
    decisions = (repo.decisions or {}).get("path")
    wanted = [*ENTRY_DOCS, *([decisions] if decisions else [])]
    documents = [decisions] if decisions else []
    if root is not None:
        base = activity.default_branch(root)
        if base:
            line = xref.git(root, "log", "-1", "--format=%h %cs", base).strip()
            state["branch"] = base.removeprefix("origin/")
            if line:
                state["head"], state["updated"] = line.split(" ", 1)
            documents = [p for p in wanted if has_path(root, base, p)]
        else:
            state["clone"] = "без основной ветки"
    else:
        state["clone"] = "нет"
    doc = {"key": key_of(repo.name), "name": repo.name}
    if paths and root is not None:
        doc["repository"] = str(root)
    doc["state"] = state
    doc["documents"] = documents
    doc["suggestions"] = suggestions(queue, repo.name)
    return doc


def build(repos: list[xref.Repo], roots: dict[str, Path | None], queue_text: str, paths: bool = False) -> str:
    queue = read_queue(queue_text)
    projects = [project(r, roots.get(r.name), queue, paths) for r in repos if r.place != "archive"]
    keys = [p["key"] for p in projects]
    dup = sorted({k for k in keys if keys.count(k) > 1})
    if dup:
        raise ValueError(f"ключи проектов повторяются: {', '.join(dup)}")
    doc = {"format": FORMAT, "version": VERSION, "projects": projects}
    return json.dumps(doc, ensure_ascii=False, indent=1) + "\n"


def main(argv: list[str] | None = None) -> int:
    here = Path(__file__).resolve().parent
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--registry", type=Path, default=here / "ecosystem.toml")
    ap.add_argument("--queue", type=Path, default=here.parent / "docs" / "SYNC_QUEUE.md")
    ap.add_argument("--root", type=Path, default=here.parent.parent,
                    help="каталог, в котором лежат клоны (по умолчанию — родитель DPmeta)")
    ap.add_argument("--clone", action="append", default=[], metavar="ИМЯ=ПУТЬ",
                    help="клон репозитория не по пути из реестра")
    ap.add_argument("--paths", action="store_true",
                    help="писать `repository` — путь клона на этом устройстве")
    ap.add_argument("--out", type=Path, help="записать манифест в файл, а не на экран")
    a = ap.parse_args(argv)

    try:
        repos = xref.load_registry(a.registry)
        queue_text = a.queue.read_text(encoding="utf-8")
    except (OSError, ValueError, TypeError, tomllib.TOMLDecodeError) as e:
        print(f"manifest: {e}", file=sys.stderr)
        return 2
    missing = [r.name for r in repos if not r.place]
    if missing:
        print(f"manifest: нет поля place у {', '.join(missing)} (M-10)", file=sys.stderr)
        return 2
    by_name = {r.name: r for r in repos}
    overrides = {}
    for item in a.clone:
        name, sep, path = item.partition("=")
        if not sep or name not in by_name:
            print(f"manifest: --clone {item}: нужно ИМЯ=ПУТЬ с именем из реестра", file=sys.stderr)
            return 2
        overrides[name] = Path(path)
    roots = {}
    for r in repos:
        root = overrides.get(r.name) or xref.find_clone(a.root, r)
        roots[r.name] = root.resolve() if (root / ".git").exists() else None
    try:
        text = build(repos, roots, queue_text, a.paths)
    except ValueError as e:
        print(f"manifest: {e}", file=sys.stderr)
        return 2
    if a.out:
        a.out.write_text(text, encoding="utf-8")
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
