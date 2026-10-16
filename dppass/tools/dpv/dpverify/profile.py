"""Профиль проекта `tools/dpverify.toml`, схема 1 (ТЗ, §7).

Любая ошибка профиля — UsageError (код 2) с путём к ключу: неизвестный ключ,
неверный тип, нераспознанный регэксп или маска, несовместимая версия DP
Verify (N-10).
"""
import datetime
import hashlib
import os
import re
import tomllib
from dataclasses import dataclass, field

from . import VERSION, version
from .commands import RULES as COMMAND_RULES
from .errors import UsageError
from .globs import MaskSet

DEFAULT_PATH = "tools/dpverify.toml"
LANGUAGES = {"kotlin", "java", "cpp", "c", "glsl", "python", "shell", "markdown"}
LEVELS = ("static", "full")

REQUIRED = object()
# Правила dpv.profile о самих исключениях и линии: исключением не принимаются, в линию не пишутся (waivers.py).
OWN_RULES = ("waiver-unused", "waiver-expired", "waiver-todo-missing", "baseline-stale")


@dataclass
class Tool:
    name: str
    probe: list
    version_regex: re.Pattern = None
    min: str = None


@dataclass
class Command:
    id: str
    run: list
    level: str = "full"
    paths: MaskSet = None
    affected_args: list = field(default_factory=list)
    requires: list = field(default_factory=list)
    timeout: float = 1800.0
    allow_skip_in_ci: str = ""
    after: list = field(default_factory=list)
    summary: re.Pattern = None


@dataclass
class Waiver:
    """Исключение `[[waiver]]` (F-16): принимает находки проверки насовсем, с причиной."""

    where: str  # «[[waiver]] №N» — для сообщений и улики находок dpv.profile
    check: str
    path: str  # маска, как записана
    paths: MaskSet
    reason: str
    rule: str = None
    match: re.Pattern = None  # по улике находки (F-16)
    until: datetime.date = None
    todo: str = None


@dataclass
class Block:
    """Участок файла между метками — всегда в границах задачи (`[scope].blocks`)."""

    file: str
    begin: str
    end: str


@dataclass
class Profile:
    path: str
    sha256: str
    dpv_range: str
    name: str
    languages: list
    main_branch: str
    exclude: MaskSet
    logs: str
    modules: dict
    process: dict
    budget: dict
    affected: dict
    tools: dict
    checks: dict
    commands: list
    declared_masks: list  # (где в профиле, MaskSet) — для dpv.profile, dead-glob
    key_count: int
    waivers: list = field(default_factory=list)  # [Waiver]
    baseline: str = "tools/dpverify.baseline.json"  # путь от корня (F-18)
    scope: dict = None  # always_allow: MaskSet, flag_only: MaskSet, blocks: [Block]


def parse_duration(text, where):
    m = re.fullmatch(r"(\d+)(s|m|h)", text.strip()) if isinstance(text, str) else None
    if not m:
        raise UsageError(f"{where}: длительность вида \"30s\", \"5m\" или \"1h\", а не {text!r}")
    return float(int(m.group(1)) * {"s": 1, "m": 60, "h": 3600}[m.group(2)])


def _type_error(where, want, value):
    raise UsageError(f"{where}: нужен {want}, а не {type(value).__name__} {value!r}")


def _str(value, where, empty=False):
    if not isinstance(value, str):
        _type_error(where, "текст", value)
    if not empty and not value.strip():
        raise UsageError(f"{where}: пустая строка")
    return value


def _list(value, where):
    if not isinstance(value, list) or not all(isinstance(x, str) and x for x in value):
        _type_error(where, "список непустых строк", value)
    return value


def _regex(value, where):
    _str(value, where)
    try:
        return re.compile(value, re.M)
    except re.error as e:
        raise UsageError(f"{where}: регэксп не разобран: {e}") from None


def _date(value, where):
    """Дата ГГГГ-ММ-ДД: строкой или датой TOML (`until = 2026-12-31`)."""
    if isinstance(value, datetime.date) and not isinstance(value, datetime.datetime):
        return value
    if isinstance(value, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
        try:
            return datetime.date.fromisoformat(value)
        except ValueError:
            pass
    raise UsageError(f"{where}: нужна дата ГГГГ-ММ-ДД, а не {value!r}")


def _path(value, where):
    """Путь от корня репозитория: без «/» в начале, без «..»."""
    _str(value, where)
    if value.startswith("/") or ".." in value.split("/"):
        raise UsageError(f"{where}: нужен путь от корня репозитория, а не {value!r}")
    return value


def _table(value, where):
    if not isinstance(value, dict):
        _type_error(where, "таблица", value)
    return value


def _keys(table, allowed, where):
    unknown = sorted(set(table) - set(allowed))
    if unknown:
        raise UsageError(f"{where}: неизвестный ключ {unknown[0]!r} (есть: {', '.join(sorted(allowed))})")


def _section(data, name, spec, where=None):
    """Таблица по спецификации {ключ: (вид, по умолчанию)} → словарь значений."""
    where = where or f"[{name}]"
    table = _table(data.get(name, {}), where)
    _keys(table, spec, where)
    out = {}
    for key, (kind, default) in spec.items():
        if key not in table:
            if default is REQUIRED:
                raise UsageError(f"{where}: нет обязательного ключа {key!r}")
            out[key] = default
            continue
        out[key] = _value(table[key], kind, f"{where}.{key}")
    return out


def _value(value, kind, where):
    if isinstance(kind, tuple) and kind[0] == "tables":
        items = _list_of_tables(value, where)
        return [_section({"t": item}, "t", kind[1], f"{where}[{i + 1}]") for i, item in enumerate(items)]
    if kind == "str":
        return _str(value, where)
    if kind == "list":
        return _list(value, where)
    if kind == "regex":
        return _regex(value, where)
    if kind == "duration":
        return parse_duration(value, where)
    if kind == "date":
        return _date(value, where)
    if kind == "bool":
        if not isinstance(value, bool):
            _type_error(where, "true или false", value)
        return value
    if kind == "int":
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            _type_error(where, "целое ≥ 0", value)
        return value
    if kind.startswith("enum:"):
        choices = kind[5:].split("|")
        if value not in choices:
            raise UsageError(f"{where}: одно из {', '.join(choices)}, а не {value!r}")
        return value
    raise AssertionError(kind)


def _count_keys(value):
    if isinstance(value, dict):
        return sum(_count_keys(v) for v in value.values()) or 1
    if isinstance(value, list) and value and all(isinstance(v, dict) for v in value):
        return sum(_count_keys(v) for v in value)
    return 1


PROJECT = {
    "name": ("str", REQUIRED), "languages": ("list", []), "main_branch": ("str", "main"),
    "exclude": ("list", []), "logs": ("str", "build/dpv"),
}
PROCESS = {
    "agent_rules": ("str", "CLAUDE.md"), "todo": ("str", "TODO.md"), "todo_item": ("regex", None),
    "roadmap": ("str", None), "decisions": ("str", None), "decision_heading": ("regex", None),
    "decision_ref": ("regex", None), "decision_required_fields": ("list", []),
    "provenance": ("str", None), "foreign_projects": ("list", []), "foreign_window": ("int", 24),
}
BUDGET = {"static": ("duration", 10.0), "affected": ("duration", 300.0), "full": ("duration", 1800.0)}
AFFECTED = {
    "on_unmapped": ("enum:full|static", "full"), "full_on": ("list", []),
    "static_only": ("list", ["**/*.md"]),
}
TOOL = {"name": ("str", REQUIRED), "probe": ("list", REQUIRED), "version_regex": ("regex", None),
        "min": ("str", None)}
COMMAND = {
    "id": ("str", REQUIRED), "run": ("list", REQUIRED), "level": ("enum:static|full", "full"),
    "paths": ("list", []), "affected_args": ("list", []), "requires": ("list", []),
    "timeout": ("duration", 1800.0), "allow_skip_in_ci": ("str", ""), "after": ("list", []),
    "summary": ("regex", None),
}
WAIVER = {
    "check": ("str", REQUIRED), "rule": ("str", None), "path": ("str", REQUIRED), "match": ("regex", None),
    "reason": ("str", REQUIRED), "until": ("date", None), "todo": ("str", None),
}
BASELINE = {"file": ("str", "tools/dpverify.baseline.json")}
BLOCK = {"file": ("str", REQUIRED), "begin": ("str", REQUIRED), "end": ("str", REQUIRED)}
SCOPE = {"always_allow": ("list", ["TODO.md"]), "flag_only": ("list", []), "blocks": (("tables", BLOCK), [])}
TOP = {"schema", "dpv", "project", "modules", "process", "budget", "affected", "tool", "checks",
       "command", "waiver", "baseline", "scope"}
DEFAULT_SUMMARY = re.compile(r"^\s*(✓|ok)\b.*", re.M)


def find(root, explicit=None):
    path = explicit or os.path.join(root, DEFAULT_PATH)
    if not os.path.isfile(path):
        if explicit:
            raise UsageError(f"нет профиля {explicit}")
        raise UsageError(f"нет профиля {DEFAULT_PATH} в {root}; другой путь — --profile")
    return path


def load(path, registry):
    """Читает и проверяет профиль. `registry` — {id: класс встроенной проверки}."""
    with open(path, "rb") as f:
        raw = f.read()
    try:
        data = tomllib.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, tomllib.TOMLDecodeError) as e:
        raise UsageError(f"профиль {path} не разобран: {e}") from None

    _keys(data, TOP, "профиль")
    if data.get("schema") != 1:
        raise UsageError(f"профиль: нужен schema = 1, а не {data.get('schema')!r}")
    if "dpv" not in data:
        raise UsageError("профиль: нет обязательного ключа 'dpv' (диапазон версий DP Verify)")
    dpv_range = _str(data["dpv"], "dpv")
    if not version.satisfies(VERSION, dpv_range):
        raise UsageError(f"профиль требует DP Verify {dpv_range}, а это {VERSION}")

    project = _section(data, "project", PROJECT)
    bad = sorted(set(project["languages"]) - LANGUAGES)
    if bad:
        raise UsageError(f"[project].languages: неизвестный язык {bad[0]!r} (есть: {', '.join(sorted(LANGUAGES))})")

    modules = {}
    for name, masks in _table(data.get("modules", {}), "[modules]").items():
        modules[name] = _list(masks, f"[modules].{name}")
    declared = []
    for name, masks in modules.items():
        declared.append((f"[modules].{name}", MaskSet(masks)))
    exclude = MaskSet(project["exclude"], modules)
    if project["exclude"]:
        declared.append(("[project].exclude", exclude))

    process = _section(data, "process", PROCESS)
    budget = _section(data, "budget", BUDGET)
    affected = _section(data, "affected", AFFECTED)
    affected_table = data.get("affected", {})
    for key in ("full_on", "static_only"):
        affected[key] = MaskSet(affected[key], modules)
        if key in affected_table and affected[key].masks:
            declared.append((f"[affected].{key}", affected[key]))

    tools = {}
    for i, item in enumerate(_list_of_tables(data.get("tool", []), "[[tool]]")):
        where = f"[[tool]] №{i + 1}"
        t = _section({"t": item}, "t", TOOL, where)
        if not t["probe"]:
            raise UsageError(f"{where}.probe: пустая команда")
        if t["name"] in tools:
            raise UsageError(f"{where}: инструмент {t['name']!r} уже описан")
        if t["min"] is not None:
            version.parse(t["min"])
            if t["version_regex"] is None:
                raise UsageError(f"{where}: min без version_regex — версию нечем прочитать")
        tools[t["name"]] = Tool(t["name"], t["probe"], t["version_regex"], t["min"])

    checks = {}
    for cid, settings in _table(data.get("checks", {}), "[checks]").items():
        where = f'[checks."{cid}"]'
        if cid not in registry:
            raise UsageError(f"{where}: неизвестная проверка (в DP Verify {VERSION} есть: {', '.join(registry)})")
        cls = registry[cid]
        spec = {"enabled": ("bool", cls.MANDATORY), "allow_empty": ("str", None), **cls.SETTINGS}
        values = _section({"c": settings}, "c", spec, where)
        cls.validate(values, modules, where)
        if cls.MANDATORY and not values["enabled"]:
            raise UsageError(f"{where}: обязательную проверку не выключить (V-12) — "
                             "принять отдельные находки можно исключением")
        checks[cid] = values

    commands, ids = [], set(registry)
    for i, item in enumerate(_list_of_tables(data.get("command", []), "[[command]]")):
        c = _section({"c": item}, "c", COMMAND, f"[[command]] №{i + 1}")
        where = f"[[command]] {c['id']}"
        if not re.fullmatch(r"project\.[a-z0-9][a-z0-9._-]*", c["id"]):
            raise UsageError(f"{where}: id — project.<имя> латиницей в нижнем регистре")
        if c["id"] in ids:
            raise UsageError(f"{where}: id уже занят")
        if not c["run"]:
            raise UsageError(f"{where}.run: пустая команда")
        ids.add(c["id"])
        for name in c["requires"]:
            if name not in tools:
                raise UsageError(f"{where}.requires: инструмента {name!r} нет в [[tool]]")
        paths = MaskSet(c["paths"], modules)
        if c["paths"]:
            declared.append((f"{where}.paths", paths))
        commands.append(Command(
            id=c["id"], run=c["run"], level=c["level"], paths=paths,
            affected_args=c["affected_args"], requires=c["requires"], timeout=c["timeout"],
            allow_skip_in_ci=c["allow_skip_in_ci"], after=c["after"],
            summary=c["summary"] or DEFAULT_SUMMARY,
        ))
    levels = {c.id: c.level for c in commands}
    for c in commands:
        for dep in c.after:
            if dep not in ids:
                raise UsageError(f"[[command]] {c.id}.after: нет проверки {dep!r}")
            if c.level == "static" and levels.get(dep) == "full":
                raise UsageError(f"[[command]] {c.id}.after: static-команда не может ждать full-команду {dep}")
    _no_cycles(commands)
    for cid, values in checks.items():
        for dep in values.get("after") or []:
            if dep not in levels:
                raise UsageError(f'[checks."{cid}"].after: нет команды {dep!r} в [[command]]')

    waivers = []
    for i, item in enumerate(_list_of_tables(data.get("waiver", []), "[[waiver]]")):
        where = f"[[waiver]] №{i + 1}"
        w = _section({"w": item}, "w", WAIVER, where)
        if w["check"] not in ids:
            raise UsageError(f"{where}.check: проверки {w['check']!r} нет "
                             f"(встроенные — {', '.join(registry)}; команды — [[command]])")
        rule = w["rule"]
        cls = registry.get(w["check"])
        known = list(cls.RULES) if cls is not None else list(COMMAND_RULES)
        if rule is not None and rule in OWN_RULES:
            raise UsageError(f"{where}.rule: {rule} — о самих исключениях и линии; их не принять исключением")
        if rule is not None and known and not (cls.knows_rule(rule) if cls is not None else rule in known):
            raise UsageError(f"{where}.rule: у {w['check']} нет правила {rule!r} (есть: {', '.join(known)})")
        # Маску исключения сверяет прогон: живая — если совпала с файлом или путём находки
        # (required-missing, missing-file — пути, которых нет); dead-glob — waivers.apply_waivers.
        paths = MaskSet([_path(w["path"], f"{where}.path")], modules)
        waivers.append(Waiver(where=where, check=w["check"], path=w["path"], paths=paths, reason=w["reason"],
                              rule=w["rule"], match=w["match"], until=w["until"], todo=w["todo"]))

    baseline = _path(_section(data, "baseline", BASELINE)["file"], "[baseline].file")

    scope_raw = _section(data, "scope", SCOPE)
    scope_table = data.get("scope", {})
    scope = {"always_allow": MaskSet(scope_raw["always_allow"], modules),
             "flag_only": MaskSet(scope_raw["flag_only"], modules),
             "blocks": [Block(_path(b["file"], f"[scope].blocks[{i + 1}].file"), b["begin"], b["end"])
                        for i, b in enumerate(scope_raw["blocks"])]}
    for key in ("always_allow", "flag_only"):
        if key in scope_table and scope[key].masks:
            declared.append((f"[scope].{key}", scope[key]))

    return Profile(
        path=path, sha256=hashlib.sha256(raw).hexdigest(), dpv_range=dpv_range,
        name=project["name"], languages=project["languages"], main_branch=project["main_branch"],
        exclude=exclude, logs=project["logs"].rstrip("/"), modules=modules, process=process,
        budget=budget, affected=affected, tools=tools, checks=checks, commands=commands,
        declared_masks=declared, key_count=_count_keys(data),
        waivers=waivers, baseline=baseline, scope=scope,
    )


def _list_of_tables(value, where):
    if not isinstance(value, list) or not all(isinstance(x, dict) for x in value):
        _type_error(where, "массив таблиц", value)
    return value


def _no_cycles(commands):
    deps = {c.id: [d for d in c.after if d.startswith("project.")] for c in commands}
    state = {}

    def visit(node, chain):
        if state.get(node) == "done":
            return
        if state.get(node) == "open":
            raise UsageError("[[command]].after: цикл " + " → ".join(chain + [node]))
        state[node] = "open"
        for dep in deps.get(node, []):
            visit(dep, chain + [node])
        state[node] = "done"

    for c in commands:
        visit(c.id, [])
