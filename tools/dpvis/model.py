"""Модель экосистемы: каталоги, чтение, проверка и сборка (D-02, ТЗ §8).

Источник — model/ecosystem.toml (схема 1); сборка — data/model.json
(`dpvis-model/1`). Сборка детерминирована: одинаковый источник — побайтно
одинаковый файл (F-07).
"""

from __future__ import annotations

import hashlib
import json
import re
import tomllib
from pathlib import Path

MODEL_SCHEMA = "dpvis-model/1"
SOURCE_SCHEMA = 1

# ------------------------------------------------------------- каталоги
# Порядок — порядок показа в легенде и фильтрах. Подписи — для читателей
# (просмотрщик, DPmetaApp): формат самоописывающий.

NODE_KINDS = {
    "repo": "репозиторий",
    "actor": "участник",
    "service": "сервис",
    "device": "устройство",
    "platform": "площадка",
    "upstream": "внешний источник",
}

DRIVERS = {
    "human": "решает автор",
    "ai": "ИИ и автоматика",
    "check": "проверка",
    "data": "данные и знание",
}

FLOW_KINDS = {
    "work": ("работа сессий", "ai"),
    "decision": ("решение автора", "human"),
    "knowledge": ("знание и правила", "data"),
    "facts": ("факты о проектах", "data"),
    "metrics": ("числа и счётчики", "data"),
    "format": ("формат данных", "data"),
    "delivery": ("поставка копией", "check"),
    "check": ("проверка и сверка", "check"),
    "read": ("чтение для показа", "ai"),
    "deploy": ("выгрузка и установка", "human"),
    "publish": ("публикация", "human"),
    "service": ("сетевой сервис", "data"),
    "notify": ("уведомления", "ai"),
    "build": ("внешнее при сборке", "data"),
    "host": ("где живёт", "data"),
}

STATUSES = {
    "active": "работает",
    "planned": "план",
    "blocked": "заблокирован",
    "off": "выключен",
}

TRANSPORTS = {
    "git": "git: ветки, PR, вливание",
    "clone": "чтение клона",
    "copy": "копия файлов",
    "http": "HTTP",
    "rsync": "rsync",
    "intent": "вызов приложения Android",
    "push": "мгновенный канал",
    "api": "API площадки",
    "manual": "руками автора",
    "knowledge": "решение, записанное с двух сторон",
}

VISIBILITY = {
    "public": "открытый",
    "private": "закрытый",
    "undisclosed": "закрытый, имя не публикуется",
}

GROUPS = {
    "control": "управление экосистемой",
    "core": "ядро",
    "sync": "слой синхронизации",
    "support": "поддерживающий слой",
    "new": "новый, место не определено",
    "periphery": "периферия",
}

# Пробы сайта и сервисов сняты (D-19): DPvisualisation к ним не обращается.
SIGNAL_TYPES = ("git-commits", "git-path")
SIGNAL_WHO = ("ai", "human", "merge", "any")
SIGNAL_SCOPES = ("main", "all")

ID_RE = re.compile(r"^[a-z][a-z0-9_.-]*$")
REF_RE = re.compile(r"^(?P<project>[A-Za-z][A-Za-z0-9_.-]*):(?P<rest>\S.*)$")
CONTRACT_RE = re.compile(r"^DPmeta:C-\d{2}$")
SLUG_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

NODE_KEYS = {
    "id", "name", "kind", "layer", "role", "refs", "repo", "clone", "group",
    "visibility", "stage", "status", "note", "row",
    "public_role", "public_stage",
}
REGISTRY_KEYS = {"node", "path", "contracts", "exclude", "exclude_contracts", "exclude_reason"}
FLOW_KEYS = {
    "id", "from", "to", "kind", "what", "transport", "refs", "contracts",
    "status", "driver", "trigger", "format", "note", "bidirectional", "via",
    "signal", "public_what",
}
TOP_KEYS = {
    "schema", "title", "as_of", "timezone", "sources", "allow", "registry",
    "layer", "node", "flow",
}


class ModelError(Exception):
    """Модель не прошла проверку; `problems` — список «место: что не так»."""

    def __init__(self, problems: list[str]):
        super().__init__("\n".join(problems))
        self.problems = problems


def load_source(path: Path) -> tuple[dict, bytes]:
    """Читает TOML-источник модели; возвращает данные и сырые байты."""
    raw = path.read_bytes()
    try:
        data = tomllib.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, tomllib.TOMLDecodeError) as exc:
        raise ModelError([f"{path}: не читается как TOML: {exc}"]) from exc
    return data, raw


def _project_names(nodes: list[dict]) -> set[str]:
    """Имена проектов для ссылок `Проект:…` — имена репозиториев из slug."""
    names = set()
    for node in nodes:
        repo = node.get("repo")
        if isinstance(repo, str) and "/" in repo:
            names.add(repo.split("/", 1)[1])
    return names


def validate(src: dict) -> list[str]:
    """Проверяет источник модели (F-05, F-06). Пустой список — модель верна."""
    problems: list[str] = []

    def bad(place: str, text: str) -> None:
        problems.append(f"{place}: {text}")

    for key in sorted(set(src) - TOP_KEYS):
        bad("модель", f"неизвестный ключ {key!r}")
    if src.get("schema") != SOURCE_SCHEMA:
        bad("модель", f"schema должна быть {SOURCE_SCHEMA}, а не {src.get('schema')!r}")
    for key in ("title", "as_of", "timezone"):
        if not isinstance(src.get(key), str) or not src.get(key):
            bad("модель", f"нет строки {key}")
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", str(src.get("as_of", ""))):
        bad("модель", "as_of — дата ГГГГ-ММ-ДД")

    allow = src.get("allow", {})
    hosts = allow.get("hosts", []) if isinstance(allow, dict) else []
    if not isinstance(hosts, list) or hosts:
        bad("allow", "hosts — пустой список: к сайту и сервисам DPvisualisation не обращается (D-19)")

    layers = src.get("layer", [])
    layer_ids: list[str] = []
    for i, layer in enumerate(layers):
        place = f"layer[{i}]"
        lid = layer.get("id")
        if not isinstance(lid, str) or not ID_RE.match(lid):
            bad(place, f"неверный id {lid!r}")
            continue
        if lid in layer_ids:
            bad(place, f"повтор id {lid!r}")
        if not layer.get("name"):
            bad(place, "нет name")
        layer_ids.append(lid)

    nodes = src.get("node", [])
    flows = src.get("flow", [])
    projects = _project_names(nodes) if isinstance(nodes, list) else set()
    node_by_id: dict[str, dict] = {}

    def check_refs(place: str, refs: object) -> None:
        if not isinstance(refs, list) or not all(isinstance(r, str) for r in refs):
            bad(place, "refs — список строк")
            return
        for ref in refs:
            m = REF_RE.match(ref)
            if not m:
                bad(place, f"ссылка {ref!r} — не в форме Проект:путь или Проект:номер")
            elif m.group("project") not in projects:
                bad(place, f"ссылка {ref!r}: проекта {m.group('project')!r} нет среди репозиториев модели")

    for i, node in enumerate(nodes):
        nid = node.get("id")
        place = f"node[{i}] {nid}"
        for key in sorted(set(node) - NODE_KEYS):
            bad(place, f"неизвестный ключ {key!r}")
        if not isinstance(nid, str) or not ID_RE.match(nid):
            bad(place, f"неверный id {nid!r}")
            continue
        if nid in node_by_id:
            bad(place, f"повтор id {nid!r}")
        node_by_id[nid] = node
        if not node.get("name"):
            bad(place, "нет name")
        if not node.get("role"):
            bad(place, "нет role")
        for key in ("public_role", "public_stage"):
            if key in node and not (isinstance(node[key], str) and node[key].strip()):
                bad(place, f"{key} — непустая строка (публичная формулировка, D-15)")
        kind = node.get("kind")
        if kind not in NODE_KINDS:
            bad(place, f"неизвестный вид {kind!r}")
        if node.get("layer") not in layer_ids:
            bad(place, f"неизвестный слой {node.get('layer')!r}")
        if node.get("status", "active") not in STATUSES:
            bad(place, f"неизвестное состояние {node.get('status')!r}")
        if node.get("visibility", "private") not in VISIBILITY:
            bad(place, f"неизвестная видимость {node.get('visibility')!r}")
        refs = node.get("refs", [])
        check_refs(place, refs)
        if not refs:
            bad(place, "нет ссылок на источники (refs)")
        if kind == "repo":
            repo = node.get("repo")
            if not isinstance(repo, str) or not SLUG_RE.match(repo):
                bad(place, f"у репозитория нет slug repo (владелец/имя): {repo!r}")
            if node.get("group") not in GROUPS:
                bad(place, f"неизвестная группа {node.get('group')!r}")
            if "visibility" not in node:
                bad(place, "у репозитория должна быть visibility")
        elif "repo" in node or "group" in node:
            bad(place, "repo и group — только у вида repo")
        if "row" in node and not isinstance(node["row"], (int, float)):
            bad(place, "row — число")

    flow_ids: set[str] = set()
    for i, flow in enumerate(flows):
        fid = flow.get("id")
        place = f"flow[{i}] {fid}"
        for key in sorted(set(flow) - FLOW_KEYS):
            bad(place, f"неизвестный ключ {key!r}")
        if not isinstance(fid, str) or not ID_RE.match(fid):
            bad(place, f"неверный id {fid!r}")
            continue
        if fid in flow_ids:
            bad(place, f"повтор id {fid!r}")
        flow_ids.add(fid)
        for end in ("from", "to"):
            if flow.get(end) not in node_by_id:
                bad(place, f"{end}: нет узла {flow.get(end)!r}")
        if flow.get("from") == flow.get("to"):
            bad(place, "from и to — один узел")
        if flow.get("via") is not None and flow.get("via") not in node_by_id:
            bad(place, f"via: нет узла {flow.get('via')!r}")
        if flow.get("kind") not in FLOW_KINDS:
            bad(place, f"неизвестный вид {flow.get('kind')!r}")
        if flow.get("status", "active") not in STATUSES:
            bad(place, f"неизвестное состояние {flow.get('status')!r}")
        if "driver" in flow and flow["driver"] not in DRIVERS:
            bad(place, f"неизвестно, кто движет: {flow['driver']!r}")
        if flow.get("transport") not in TRANSPORTS:
            bad(place, f"неизвестный транспорт {flow.get('transport')!r}")
        if not flow.get("what"):
            bad(place, "нет what — что передаётся")
        if "public_what" in flow and not (isinstance(flow["public_what"], str) and flow["public_what"].strip()):
            bad(place, "public_what — непустая строка (публичная формулировка, D-15)")
        if not isinstance(flow.get("bidirectional", False), bool):
            bad(place, "bidirectional — да или нет")
        refs = flow.get("refs", [])
        contracts = flow.get("contracts", [])
        check_refs(place, refs)
        if not isinstance(contracts, list):
            bad(place, "contracts — список")
            contracts = []
        for c in contracts:
            if not isinstance(c, str) or not CONTRACT_RE.match(c):
                bad(place, f"контракт {c!r} — не в форме DPmeta:C-NN")
        if not refs and not contracts:
            bad(place, "нет ни ссылок (refs), ни контрактов (contracts)")
        signal = flow.get("signal")
        if signal is not None:
            _check_signal(place, signal, node_by_id, bad)
        if signal is not None and flow.get("status", "active") != "active":
            bad(place, "сигнал — только у потока в состоянии active")

    registry = src.get("registry")
    if registry is not None:
        if not isinstance(registry, dict):
            bad("registry", "должна быть таблица")
        else:
            for key in sorted(set(registry) - REGISTRY_KEYS):
                bad("registry", f"неизвестный ключ {key!r}")
            excl = registry.get("exclude", [])
            if not isinstance(excl, list) or not all(isinstance(x, str) and SLUG_RE.match(x) for x in excl):
                bad("registry", "exclude — список slug (владелец/имя)")
            exc = registry.get("exclude_contracts", [])
            if not isinstance(exc, list) or not all(isinstance(x, str) and re.fullmatch(r"C-\d{2}", x) for x in exc):
                bad("registry", "exclude_contracts — список номеров C-NN")
            if (excl or exc) and not (isinstance(registry.get("exclude_reason"), str) and registry["exclude_reason"].strip()):
                bad("registry", "исключения — только с причиной exclude_reason")
            in_model = {n.get("repo", "").lower() for n in nodes if isinstance(n, dict)}
            for slug in excl if isinstance(excl, list) else []:
                if isinstance(slug, str) and slug.lower() in in_model:
                    bad("registry", f"{slug} исключён из сверки, но есть узлом модели")
            rnode = node_by_id.get(registry.get("node"))
            if rnode is None or rnode.get("kind") != "repo":
                bad("registry", f"node: нет узла-репозитория {registry.get('node')!r}")
            for key in ("path", "contracts"):
                if not isinstance(registry.get(key), str):
                    bad("registry", f"нет пути {key}")

    if not nodes:
        bad("модель", "нет узлов")
    return problems


def _check_signal(place: str, signal: object, node_by_id: dict, bad) -> None:
    if not isinstance(signal, dict):
        bad(place, "signal — таблица")
        return
    stype = signal.get("type")
    if stype not in SIGNAL_TYPES:
        bad(place, f"неизвестный сигнал {stype!r}")
        return
    allowed = {"git-commits": {"type", "repo", "who", "scope"},
               "git-path": {"type", "repo", "paths"}}[stype]
    for key in sorted(set(signal) - allowed):
        bad(place, f"сигнал {stype}: неизвестный ключ {key!r}")
    if stype in ("git-commits", "git-path"):
        target = node_by_id.get(signal.get("repo"))
        if target is None or target.get("kind") != "repo":
            bad(place, f"сигнал {stype}: repo — не узел-репозиторий: {signal.get('repo')!r}")
    if stype == "git-commits":
        if signal.get("who", "any") not in SIGNAL_WHO:
            bad(place, f"сигнал git-commits: who — одно из {', '.join(SIGNAL_WHO)}")
        if signal.get("scope", "main") not in SIGNAL_SCOPES:
            bad(place, f"сигнал git-commits: scope — одно из {', '.join(SIGNAL_SCOPES)}")
    if stype == "git-path":
        paths = signal.get("paths")
        if not isinstance(paths, list) or not paths or not all(isinstance(p, str) and p for p in paths):
            bad(place, "сигнал git-path: paths — непустой список путей")


def compile_model(src: dict, raw: bytes, source_path: str = "model/ecosystem.toml") -> dict:
    """Собирает `dpvis-model/1` из проверенного источника; значения по умолчанию — явно."""
    problems = validate(src)
    if problems:
        raise ModelError(problems)
    nodes = []
    for node in src["node"]:
        out = {
            "id": node["id"],
            "name": node["name"],
            "kind": node["kind"],
            "layer": node["layer"],
            "role": node["role"],
            "status": node.get("status", "active"),
            "visibility": node.get("visibility", "private"),
            "refs": list(node.get("refs", [])),
        }
        for key in ("repo", "clone", "group", "stage", "note", "row", "public_role", "public_stage"):
            if key in node:
                out[key] = node[key]
        nodes.append(out)
    flows = []
    for flow in src["flow"]:
        out = {
            "id": flow["id"],
            "from": flow["from"],
            "to": flow["to"],
            "kind": flow["kind"],
            "driver": flow.get("driver", FLOW_KINDS[flow["kind"]][1]),
            "status": flow.get("status", "active"),
            "what": flow["what"],
            "transport": flow["transport"],
            "bidirectional": flow.get("bidirectional", False),
            "contracts": list(flow.get("contracts", [])),
            "refs": list(flow.get("refs", [])),
        }
        for key in ("trigger", "format", "note", "via", "public_what"):
            if key in flow:
                out[key] = flow[key]
        if "signal" in flow:
            sig = dict(flow["signal"])
            if sig["type"] == "git-commits":
                sig.setdefault("who", "any")
                sig.setdefault("scope", "main")
            out["signal"] = sig
        flows.append(out)
    model = {
        "schema": MODEL_SCHEMA,
        "title": src["title"],
        "as_of": src["as_of"],
        "timezone": src["timezone"],
        "source": {"path": source_path, "sha256": hashlib.sha256(raw).hexdigest()},
        "sources": list(src.get("sources", [])),
        "allow": {"hosts": list(src.get("allow", {}).get("hosts", []))},
        "catalog": catalog(),
        "layers": [{"id": layer["id"], "name": layer["name"]} for layer in src.get("layer", [])],
        "nodes": nodes,
        "flows": flows,
    }
    if "registry" in src:
        model["registry"] = dict(src["registry"])
    return model


def catalog() -> dict:
    """Каталоги с подписями — часть формата, чтобы читатель не держал их у себя."""
    return {
        "node_kinds": [{"id": k, "name": v} for k, v in NODE_KINDS.items()],
        "flow_kinds": [{"id": k, "name": v[0], "driver": v[1]} for k, v in FLOW_KINDS.items()],
        "drivers": [{"id": k, "name": v} for k, v in DRIVERS.items()],
        "statuses": [{"id": k, "name": v} for k, v in STATUSES.items()],
        "transports": [{"id": k, "name": v} for k, v in TRANSPORTS.items()],
        "visibility": [{"id": k, "name": v} for k, v in VISIBILITY.items()],
        "groups": [{"id": k, "name": v} for k, v in GROUPS.items()],
    }


def dumps(obj: dict) -> str:
    """Канонический JSON: ключи упорядочены, UTF-8 как есть, перевод строки в конце."""
    return json.dumps(obj, ensure_ascii=False, indent=1, sort_keys=True) + "\n"


def build(source: Path, out: Path, source_path: str = "model/ecosystem.toml") -> dict:
    """Читает, проверяет и записывает модель; возвращает собранную модель."""
    src, raw = load_source(source)
    model = compile_model(src, raw, source_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(dumps(model), encoding="utf-8")
    return model


def expected_text(source: Path, source_path: str = "model/ecosystem.toml") -> str:
    """Какой текст должен лежать в data/model.json для этого источника."""
    src, raw = load_source(source)
    return dumps(compile_model(src, raw, source_path))
