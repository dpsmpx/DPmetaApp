"""dpv.profile — профиль не устарел: маски живые, команды на месте,
запланированное не появилось (ТЗ, §8.1).

Ошибки разбора профиля — не находки, а код 2 (profile.py). Маски
настроек проверок (`skip`, `historical` …) тоже сверяются; `forbid`,
`external` и `planned` — нет: они по смыслу ни с чем не совпадают (на
`external` и `planned` никто не ссылается — находка docs.links). Правила
исключений и базовой линии (`waiver-*`, `baseline-stale`, dead-glob маски
исключения) судятся после всех проверок — waivers.py: им нужны находки
прогона.
"""
import os

from ..model import Finding, Outcome
from .base import Check, default_settings
from .docs import Links, PathEntries


class ProfileCheck(Check):
    ID = "dpv.profile"
    MANDATORY = True
    SCANNED = "значения профиля"
    SUMMARY = "мёртвые маски, лишние и просроченные исключения, устаревшую базовую линию, пропавшие команды"
    ORIGIN = "идея VoxelRPG:tools/hostcheck/affected.py --self-check"
    RULES = {
        "dead-glob": ("маска профиля ни с чем не совпала: exclude, [modules], [affected], paths команд, "
                      "маски настроек проверок, [scope], path исключения",
                      "исправить маску или убрать её из профиля (V-07)", "место в профиле и маска"),
        "command-missing": ("первый элемент run команды — путь в репозитории, которого нет",
                            "исправить путь; имя из PATH не сверяется — его отсутствие даёт not-started",
                            "id команды и путь"),
        "planned-exists": ('путь из [checks."docs.links"].planned появился', "убрать запись из planned",
                           "путь записи"),
        "waiver-unused": ("исключение не приняло ни одной находки, хотя его проверка прошла целиком",
                          "находку исправили — убрать исключение (V-08)", "проверка, правило и маска исключения"),
        "waiver-expired": ("срок исключения (until) прошёл; просроченное ничего не принимает",
                           "исправить находку и убрать исключение или продлить until с причиной",
                           "проверка, правило и маска исключения"),
        "waiver-todo-missing": ("пункта TODO.md из todo исключения нет; такое исключение ничего не принимает",
                                "пункт выполнен — исправить находку и убрать исключение; иначе — вернуть пункт",
                                "проверка, правило и маска исключения"),
        "baseline-stale": ("запись базовой линии, чьей находки больше нет (F-18)",
                           "dpv baseline prune и закоммитить линию (V-09)", "отпечаток записи"),
    }

    def run(self, ctx):
        profile, repo = ctx.profile, ctx.repo
        where_profile = os.path.relpath(profile.path, repo.root).replace(os.sep, "/")
        findings = []
        paths = repo.all_paths()
        declared = list(profile.declared_masks)
        for cid, cls in ctx.registry.items():
            settings = profile.checks.get(cid) or default_settings(cls)
            if settings["enabled"]:
                declared += cls.masks(settings, profile.modules)
        for where, masks in declared:
            for mask in masks.dead(paths):
                findings.append(Finding(
                    "dead-glob", f"маска «{mask}» в {where} ни с чем не совпала", path=where_profile,
                    hint="исправить маску или убрать её из профиля (V-07)", evidence=f"{where}\0{mask}"))
        for cmd in profile.commands:
            exe = cmd.run[0]
            if "/" not in exe:
                continue  # имя из PATH: его отсутствие — not-started при запуске
            target = exe if os.path.isabs(exe) else os.path.join(repo.root, exe)
            if not os.path.exists(target):
                findings.append(Finding(
                    "command-missing", f"{cmd.id}: нет {exe}", path=where_profile,
                    hint="исправить путь команды в профиле", evidence=f"{cmd.id}\0{exe}"))
        links = profile.checks.get(Links.ID) or default_settings(Links)
        for p in PathEntries(links["planned"], profile.modules).appeared(repo):
            findings.append(Finding(
                "planned-exists", f"запланированный путь «{p}» появился", path=where_profile,
                hint=f'убрать запись из [checks."{Links.ID}"].planned', evidence=p))
        return Outcome(profile.key_count, findings,
                       f"профиль: значений {profile.key_count}, масок {sum(len(m.masks) for _, m in declared)}, "
                       f"команд {len(profile.commands)}")
