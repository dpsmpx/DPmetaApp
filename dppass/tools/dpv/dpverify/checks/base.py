"""Встроенная проверка — внутренний API (V-04): публичного API плагинов нет."""
from dataclasses import dataclass

from ..globs import MaskSet


@dataclass
class Context:
    repo: object
    profile: object
    mode: str  # local | ci
    level: str
    package_root: str
    log: object  # файл журнала проверки, открытый на запись
    registry: dict = None  # {id: класс} всех встроенных проверок


def default_settings(cls):
    return {"enabled": cls.MANDATORY, "allow_empty": None, **{k: d for k, (_, d) in cls.SETTINGS.items()}}


class Check:
    ID = ""
    LEVEL = "static"  # static — на всех уровнях; full — только в полном; affected — только в affected
    MANDATORY = False
    SETTINGS = {}  # {ключ: (вид, по умолчанию)} — как в profile.py
    SCANNED = ""  # что считается в scanned (для explain и docs/CHECKS.md)
    SUMMARY = ""  # что ловит — одной фразой (dpv explain, dpv list)
    ORIGIN = ""  # откуда правила: скрипты проектов, которые проверка заменяет (ТЗ, §8)
    # {правило: (что ловит, как чинить, улика для match исключения)}; шаблон правила —
    # с «<…>» в конце: «undefined-<вид>», «extra:<id>».
    RULES = {}

    MASK_KEYS = ()  # ключи-списки масок: проверяются при загрузке и на dead-glob

    def __init__(self, settings):
        self.settings = settings

    @classmethod
    def validate(cls, settings, modules, where):
        """Маски и связи настроек — при загрузке профиля; ошибка — UsageError (код 2)."""
        for key in cls.MASK_KEYS:
            if settings.get(key):
                MaskSet(settings[key], modules)

    @classmethod
    def knows_rule(cls, rule):
        """Правило есть у проверки: точно или по шаблону «префикс<…>»."""
        for known in cls.RULES:
            head, sep, _ = known.partition("<")
            if rule == known or (sep and rule.startswith(head) and len(rule) > len(head)):
                return True
        return False

    @classmethod
    def masks(cls, settings, modules):
        """[(где в профиле, MaskSet)] — для dead-glob в dpv.profile."""
        return [(f'[checks."{cls.ID}"].{key}', MaskSet(settings[key], modules))
                for key in cls.MASK_KEYS if settings.get(key)]

    def run(self, ctx):
        """→ model.Outcome."""
        raise NotImplementedError
