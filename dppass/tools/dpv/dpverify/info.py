"""Справочные команды: `dpv list`, `dpv explain`, `dpv doctor` (ТЗ, §9.1; F-12, N-11).

Ничего не запускают из проверок проекта и не пишут на диск (V-06): `doctor`
зовёт только `git`, `bash` и пробы `[[tool]]` (F-26).
"""
import io
import platform
import re
import subprocess
import sys

from . import VERSION, version
from .checks.base import Context, default_settings
from .checks.integrity import Integrity
from .commands import RULES as COMMAND_RULES, Tools
from .errors import UsageError

LEVEL = {"static": "static", "full": "full", "affected": "только affected"}
WAIVER_PATH = {True: "<маска пути находки>", False: "**"}


def _kind(cls):
    return "обязательная" if cls.MANDATORY else "необязательная"


def _state(cls, profile):
    if cls.MANDATORY:
        return "обязательная"
    settings = profile.checks.get(cls.ID) or default_settings(cls)
    return "включена" if settings["enabled"] else f'выключена — [checks."{cls.ID}"] enabled = true'


# ---- dpv list ----

def list_lines(profile, registry):
    rows = []
    for cid, cls in registry.items():
        rows.append((cid, "встроенная", LEVEL[cls.LEVEL], _state(cls, profile)))
    for c in profile.commands:
        what = []
        if c.paths.masks:
            what.append("триггеры: " + ", ".join(c.paths.masks))
        elif c.level == "full":
            what.append("только полным прогоном")
        if c.affected_args:
            what.append("делегирование: " + " ".join(c.affected_args))
        if c.requires:
            what.append("требует: " + ", ".join(c.requires))
        if c.after:
            what.append("после: " + ", ".join(c.after))
        rows.append((c.id, "команда", c.level, " · ".join(what)))
    width = max(len(r[0]) for r in rows) + 2
    builtin = sum(1 for r in rows if r[1] == "встроенная")
    lines = [f"dpv list · {profile.name} · проверок {len(rows)}: встроенных {builtin}, "
             f"команд {len(profile.commands)}"]
    lines += [f"  {cid:<{width}} {kind:<11} {level:<15} {what}".rstrip() for cid, kind, level, what in rows]
    lines.append("что ловит проверка и как чинить — dpv explain ID[/правило]")
    return lines


# ---- dpv explain ----

def _value(value):
    if isinstance(value, re.Pattern):
        return f"'{value.pattern}'"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, str):
        return f'"{value}"'
    if isinstance(value, list) and value and all(isinstance(x, dict) for x in value):
        return f"записей {len(value)}"
    if isinstance(value, list):
        return "[" + ", ".join(f'"{x}"' for x in value) + "]"
    return "—" if value is None else str(value)


def _waiver(cid, rule, evidence, has_path):
    lines = ["  [[waiver]]", f'  check = "{cid}"']
    if rule:
        lines.append(f'  rule = "{rule}"')
    lines.append(f'  path = "{WAIVER_PATH[has_path]}"')
    lines.append(f"  match = '<регэксп по улике: {evidence}>'   # необязательно" if evidence
                 else "  match = '<регэксп по улике>'   # необязательно")
    lines += ['  reason = "<почему это не ошибка>"',
              "  until = <ГГГГ-ММ-ДД>   # или todo = \"<подстрока пункта TODO.md>\""]
    return lines


def explain_lines(profile, registry, target):
    cid, _, rule = target.partition("/")
    commands = {c.id: c for c in profile.commands}
    if cid in registry:
        return _explain_builtin(profile, registry[cid], rule)
    if cid in commands:
        return _explain_command(commands[cid], rule)
    known = ", ".join(list(registry) + list(commands))
    raise UsageError(f"explain: проверки {cid!r} нет (есть: {known})")


def _rule(rules, cid, rule, knows):
    if rule and not knows(rule):
        raise UsageError(f"explain: у {cid} нет правила {rule!r} (есть: {', '.join(rules)})")
    if not rule:
        return list(rules.items())
    for name, meta in rules.items():
        if name == rule or (name.endswith(">") and rule.startswith(name.split("<")[0])):
            return [(rule, meta)]
    return []


def _explain_builtin(profile, cls, rule):
    picked = _rule(cls.RULES, cls.ID, rule, cls.knows_rule)
    lines = [f"{cls.ID}{'/' + rule if rule else ''} — встроенная, {_kind(cls)}, уровень {LEVEL[cls.LEVEL]}"
             + ("" if cls.MANDATORY else f" ({_state(cls, profile)})"),
             f"  ловит: {cls.SUMMARY}", f"  откуда: {cls.ORIGIN}",
             f"  scanned: {cls.SCANNED}; 0 — ✗ empty, если нет allow_empty (V-07)", "  правила:"]
    for name, (what, fix, _) in picked:
        lines += [f"    {name} — {what}", f"        чинить: {fix}"]
    settings = profile.checks.get(cls.ID)
    if cls.SETTINGS:
        lines.append(f'  настройки [checks."{cls.ID}"]:')
        for key, (_, default) in cls.SETTINGS.items():
            if settings is not None and settings.get(key) != default:
                lines.append(f"    {key} = {_value(settings[key])}   (по умолчанию {_value(default)})")
            else:
                lines.append(f"    {key} = {_value(default)}")
    evidence = picked[0][1][2] if len(picked) == 1 else ""
    lines.append("  исключение — находка принята насовсем, с причиной (F-16):")
    lines += _waiver(cls.ID, rule, evidence, True)
    lines.append(f"  описание — docs/CHECKS.md, раздел {cls.ID}")
    return lines


def _explain_command(cmd, rule):
    picked = _rule(COMMAND_RULES, cmd.id, rule, lambda r: r in COMMAND_RULES)
    trig = ", ".join(cmd.paths.masks) or ("— (только полным прогоном)" if cmd.level == "full" else "—")
    lines = [f"{cmd.id}{'/' + rule if rule else ''} — команда проекта, уровень {cmd.level}",
             f"  запуск: {' '.join(cmd.run)}   (без оболочки, F-25; код 0 — ✓)",
             f"  триггеры affected: {trig}"
             + (f"; делегирование: {' '.join(cmd.affected_args)} (F-10)" if cmd.affected_args else ""),
             f"  требует: {', '.join(cmd.requires) or '—'} · после: {', '.join(cmd.after) or '—'} · "
             f"пропуск в CI: {cmd.allow_skip_in_ci or 'запрещён'}",
             "  правила:"]
    for name, (what, fix, _) in picked:
        lines += [f"    {name} — {what}", f"        чинить: {fix}"]
    evidence = picked[0][1][2] if len(picked) == 1 else ""
    lines.append("  исключение (находка команды без пути — path = \"**\"); известная поломка — "
                 "скорее базовая линия с пунктом TODO.md (TR-6):")
    lines += _waiver(cmd.id, rule, evidence, False)
    lines.append("  описание — docs/CHECKS.md, раздел «Команды проекта»")
    return lines


# ---- dpv doctor ----

def _probe(args, rx):
    try:
        r = subprocess.run(args, capture_output=True, stdin=subprocess.DEVNULL, timeout=30)
    except (OSError, subprocess.TimeoutExpired):
        return None
    m = re.search(rx, (r.stdout + r.stderr).decode("utf-8", "replace")) if r.returncode == 0 else None
    return m.group(1) if m else ""


def doctor(repo, profile, package_root, registry):
    """→ (строки, код): 2 — нет python ≥ 3.11, git или bash (F-13); 1 — не хватает инструмента
    [[tool]] или копия не сходится с манифестом; 0 — всё на месте."""
    lines = [f"dpv doctor · DP Verify {VERSION} · {profile.name}"]
    code = 0

    def row(ok, text):
        lines.append(f"  {'✓' if ok else '✗'} {text}")

    py = sys.version_info >= (3, 11)
    row(py, f"python {platform.python_version()} — нужен ≥ 3.11 (V-01)")
    for name, args, rx, need in (("git", ["git", "--version"], r"git version (\S+)", "2.30"),
                                 ("bash", ["bash", "--version"], r"version (\d+\.\d+\.\d+)", "5.0")):
        found = _probe(args, rx)
        if found is None:
            row(False, f"{name}: нет — без него DP Verify не работает (F-13)")
            py = False
            continue
        try:
            fresh = version.parse(re.match(r"\d+(?:\.\d+){0,2}", found).group(0)) >= version.parse(need)
        except (AttributeError, UsageError):
            fresh = True
        row(True, f"{name} {found or '?'}" + ("" if fresh else f" — ниже {need}: без гарантий (N-02)"))
    if not py:
        code = 2
    ctx = Context(repo, profile, "local", "static", package_root, io.StringIO(), registry)
    integrity = Integrity(default_settings(Integrity)).run(ctx)
    row(not integrity.findings, f"копия: {integrity.summary or 'не сверена'} (dpv.integrity)")
    for f in integrity.findings:
        lines.append(f"      {f.path + '  ' if f.path else ''}[{f.rule}] {f.message}")
    if integrity.findings and code == 0:
        code = 1
    tools = Tools(profile.tools, repo.root)
    users = {}
    for c in profile.commands:
        for name in c.requires:
            users.setdefault(name, []).append(c.id)
    for name in profile.tools:
        reason = tools.missing(name)
        who = ", ".join(users.get(name, [])) or "никому из команд"
        found = tools.versions.get(name)
        if reason:
            row(False, f"{reason} — нужен: {who}")
            code = code or 1
        else:
            row(True, f"{name}{' ' + found if found else ''} — нужен: {who}")
    total = {0: "✓ окружение в порядке", 1: "✗ чего-то не хватает — команды с requires будут пропущены",
             2: "✗ DP Verify не работает без python ≥ 3.11, git и bash (F-13)"}[code]
    lines.append(total)
    return lines, code
