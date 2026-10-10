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
  клона проверить нечего — список пуст;
- предложения задач — пункты docs/SYNC_QUEUE.md этого проекта, которые
  сессия может сделать сама: открытые по своему полю «Состояние», не
  ждущие автора и не привязанные к RISK-NN, не «открытый вопрос» и с полем
  «Предложение». Ключ `sq-NN`, цель — поля «Где» («Где и что») и
  «Предложение» с ссылкой `DPmeta:SQ-NN` (без состояния: цель не меняется
  от прохода к проходу), приоритет — один для всех (`LOW`: риск пункта не
  срочность), вид работы `docs`. Задачу ставит и одобряет оркестратор по
  своей политике; DPmeta её не исполняет (M-01, M-04).

Чтение у оркестратора строгое: лишних полей нет, ключи проектов не
повторяются. Без `--paths` поле `repository` не пишется: путь клона —
свойство устройства, а не экосистемы. Без сети; читает клоны (только
`git ls-tree` и `git log`), ничего не пишет, кроме файла из --out. Один и
тот же вход — одинаковые байты. Коды возврата: 0 — манифест построен;
2 — неверный вызов, реестр или запись файла.
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
# Один уровень `Priority` для всех предложений: риск пункта — не срочность, а пункты,
# которые решает автор, в манифест не попадают вовсе (blocked).
PRIORITY = "LOW"
OPERATION = "docs"

ROW = re.compile(r"^\| (SQ-\d+) \| ([^|]+) \| ([^|]+) \| ([^|]+) \| ([^|]+) \|$")
HEAD = re.compile(r"^### (SQ-\d+) — (.+)$")
# «- **Поле:** текст» и «- **Поле** (уточнение): текст»
FIELD = re.compile(r"^- \*\*([^*]+?):?\*\*:? ?(.*)$")
# Пункт ждёт автора или решения по риску — сессия его сделать не может.
BLOCKED = re.compile(r"RISK-\d+|автор", re.I)


def key_of(name: str) -> str:
    return re.sub(r"[^a-z0-9_-]+", "-", name.lower()).strip("-")


def plain(text: str) -> str:
    """Текст Markdown одной строкой: без обратных кавычек и звёздочек."""
    return re.sub(r"\s+", " ", text.replace("`", "").replace("**", "")).strip()


def is_open(state: str) -> bool:
    return state.strip().startswith(("открыт", "частично"))


def status(state: str, kind: str = "") -> str:
    """closed | open | blocked — что значит состояние пункта для сессии проекта."""
    if not is_open(state):
        return "closed"
    if BLOCKED.search(state) or kind.strip().lower().startswith("открытый вопрос"):
        return "blocked"
    return "open"


def read_queue(text: str) -> dict[str, dict]:
    """Пункты очереди: номер → репозитории, заголовок, риск, состояние сводки, поля пункта."""
    items: dict[str, dict] = {}
    for line in text.splitlines():
        m = ROW.match(line)
        if m:
            num, repos, title, risk, state = (g.strip() for g in m.groups())
            items[num] = {"repos": [r.strip().strip("`") for r in repos.split(",")], "title": plain(title),
                          "risk": risk, "state": state, "proposal": "", "fields": {}}
    current, field_name, buf = None, None, []

    def flush():
        if current in items and field_name and buf:
            # строки поля — одной строкой; пункты вложенного списка («- …») — через «;»
            text = ""
            for x in buf:
                if x.startswith("- "):
                    text += ("; " if text.strip() else "") + x[2:]
                else:
                    text += " " + x
            items[current]["fields"].setdefault(field_name, plain(text))
            if field_name == "Предложение":
                items[current]["proposal"] = items[current]["fields"][field_name]

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


def item_status(item: dict) -> str:
    """Статус пункта по его собственному полю «Состояние» (сводка — запасной источник)."""
    f = item["fields"]
    return status(f.get("Состояние") or item["state"], f.get("Вид", ""))


def objective(num: str, item: dict, name: str) -> str:
    """Цель задачи: где и что предлагается; без состояния — оно меняется от прохода к проходу."""
    f = item["fields"]
    where = f.get("Где") or f.get("Где и что")
    parts = [f"Где: {where}"] if where else []
    parts.append(f"Предложение: {item['proposal']}")
    others = [r for r in item["repos"] if r != name]
    if others:
        parts.append(f"Пункт касается и {', '.join(others)}: здесь — только часть для {name}.")
    return " ".join(parts) + f" (DPmeta:{num})"


def suggestions(queue: dict[str, dict], name: str) -> list[dict]:
    out = []
    for num, item in sorted(queue.items(), key=lambda kv: int(kv[0].split("-")[1])):
        if name not in item["repos"] or not item["proposal"] or item_status(item) != "open":
            continue
        out.append({"key": num.lower(), "title": f"{num}: {item['title']}",
                    "objective": objective(num, item, name), "priority": PRIORITY, "operation": OPERATION})
    return out


def has_path(root: Path, ref: str, path: str) -> bool:
    return bool(xref.git(root, "ls-tree", "--name-only", ref, "--", path).strip())


def project(repo: xref.Repo, root: Path | None, queue: dict[str, dict], paths: bool) -> dict:
    state = {"place": repo.place}
    decisions = (repo.decisions or {}).get("path")
    wanted = [*ENTRY_DOCS, *([decisions] if decisions else [])]
    documents: list[str] = []  # только то, что есть в основной ветке клона
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
        try:
            a.out.write_text(text, encoding="utf-8")
        except OSError as e:
            print(f"manifest: {e}", file=sys.stderr)
            return 2
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
