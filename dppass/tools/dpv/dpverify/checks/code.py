"""code.forbidden — запрещённые API и конструкции по правилам профиля (ТЗ, §8.2).

Правило — регэксп по строке кода в файлах `files`, кроме `allow`. Что
считается кодом, решает `scope`: `code` — без комментариев, строки
остаются (имя алгоритма шифрования — строка); `tokens` — без комментариев
и строк; `text` — как есть. Разборщик комментариев — по расширению
(textscan.language, L-04); для остальных файлов — только `text`, иначе код 2:
правило не должно молча проверять комментарии как код. В Kotlin блоки
`/* */` вкладываются, и «/*» внутри комментария — находка `nested-comment`
(V-21): путь `tests/data/**` в KDoc открывает вложенный комментарий.

Идея — DPpass:tools/security/check_forbidden_apis.py (свой код автора).
"""
import re

from .. import textscan
from ..errors import UsageError
from ..globs import MaskSet
from ..model import Finding, Outcome
from ..profile import REQUIRED
from .base import Check

RULE = {"id": ("str", REQUIRED), "regex": ("regex", REQUIRED), "files": ("list", REQUIRED),
        "allow": ("list", []), "reason": ("str", REQUIRED), "scope": ("enum:code|tokens|text", "code")}
RULE_ID = re.compile(r"[a-z0-9][a-z0-9-]*")
RESERVED = ("empty", "nested-comment")
SHOWN = 80  # символов совпадения в сообщении


class Forbidden(Check):
    ID = "code.forbidden"
    SCANNED = "файлы, к которым относится хотя бы одно правило"
    SUMMARY = "запрещённые API и конструкции по правилам профиля: регэксп по коду без комментариев"
    ORIGIN = "DPpass:tools/security/check_forbidden_apis.py; ограничения импортов DPpass:tools/hostcheck/check_modules.py"
    SETTINGS = {"rule": (("tables", RULE), [])}
    RULES = {
        "<id правила>": ("совпадение регэкспа правила профиля в файле из files вне allow",
                         "убрать вызов; разрешённое место — allow правила, ложное срабатывание — [[waiver]] "
                         "с причиной", "совпавший текст"),
        "nested-comment": ("«/*» внутри комментария Kotlin — вложенный комментарий, а не текст (V-21)",
                           "писать tests/data/… или без звёздочки после косой черты", "строка с «/*»"),
    }

    @classmethod
    def validate(cls, settings, modules, where):
        seen = set()
        for i, rule in enumerate(settings["rule"]):
            at = f"{where}.rule[{i + 1}]"
            if not RULE_ID.fullmatch(rule["id"]) or rule["id"] in RESERVED:
                raise UsageError(f"{at}.id: латиница в нижнем регистре, цифры и «-», не {' и '.join(RESERVED)}")
            if rule["id"] in seen:
                raise UsageError(f"{at}.id: правило {rule['id']!r} уже есть")
            seen.add(rule["id"])
            MaskSet(rule["files"], modules)
            MaskSet(rule["allow"], modules)

    @classmethod
    def masks(cls, settings, modules):
        out = []
        for i, rule in enumerate(settings["rule"]):
            for key in ("files", "allow"):
                if rule[key]:
                    out.append((f'[checks."{cls.ID}"].rule[{i + 1}].{key} ({rule["id"]})',
                                MaskSet(rule[key], modules)))
        return out

    def run(self, ctx):
        modules = ctx.profile.modules
        rules = [(r, MaskSet(r["files"], modules), MaskSet(r["allow"], modules)) for r in self.settings["rule"]]
        findings, scanned = [], 0
        for path in ctx.repo.files():
            mine = [r for r, files, allow in rules if files.match(path) and not allow.match(path)]
            if not mine:
                continue
            text = ctx.repo.text(path)
            if text is None:
                continue  # двоичный файл — не код
            scanned += 1
            lang = textscan.language(path)
            views = {}
            if lang == "kotlin":
                nested, lines = [], text.split("\n")
                views["code"] = textscan.code_view(text, lang, True, nested)
                for n in sorted(set(nested)):
                    findings.append(Finding(
                        "nested-comment", "«/*» внутри комментария — в Kotlin это вложенный комментарий, а не текст",
                        path=path, line=n, evidence=lines[n - 1].strip(),
                        hint="писать tests/data/… или без звёздочки после косой черты (V-21)"))
            for rule in mine:
                scope = rule["scope"]
                if scope != "text" and lang is None:
                    raise UsageError(f'[checks."{self.ID}"] правило {rule["id"]}: scope = "{scope}", а для {path} '
                                     'разборщика комментариев нет (L-04) — scope = "text" или сузить files')
                if scope not in views:
                    views[scope] = (text if scope == "text" else textscan.code_view(text, lang, scope == "code"))
                for n, line in enumerate(views[scope].split("\n"), 1):
                    for m in rule["regex"].finditer(line):
                        found = m.group(0).strip()
                        shown = found if len(found) <= SHOWN else found[:SHOWN] + "…"
                        findings.append(Finding(
                            rule["id"], f"«{shown}» — {rule['reason']}", path=path, line=n, evidence=found,
                            hint=f"правило {rule['id']} профиля: разрешённое место — allow, ложное срабатывание — "
                                 f"[[waiver]] с причиной; dpv explain {self.ID}"))
        return Outcome(scanned, findings, f"файлов {scanned}, правил {len(rules)}")
