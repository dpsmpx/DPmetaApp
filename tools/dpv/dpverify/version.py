"""Версии DP Verify и диапазоны вида ">=1.0, <2" из ключа `dpv` профиля (ТЗ, §7.1).

Версия — `MAJOR.MINOR.PATCH` с необязательным `-devN` или `-rcN`; пропущенные
части — нули (`1.0` = `1.0.0`). Предварительная версия младше выпуска:
`1.0.0-rc1 < 1.0.0`.
"""
import re

from .errors import UsageError

_VERSION = re.compile(r"^(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-(dev|rc)(\d+))?$")
_COMPARATOR = re.compile(r"^(>=|<=|==|>|<)\s*(\S+)$")
_PRE = {"dev": 0, "rc": 1}


def parse(text):
    """Строка версии → кортеж для сравнения; неверная — UsageError."""
    m = _VERSION.match(text.strip())
    if not m:
        raise UsageError(f"неверная версия «{text}»: нужна вида 1.2.3, 1.2.3-rc1 или 1.2.3-dev1")
    major, minor, patch, pre, pre_n = m.groups()
    release = (int(major), int(minor or 0), int(patch or 0))
    # Выпуск старше любой предварительной версии того же номера.
    tail = (2, 0) if pre is None else (_PRE[pre], int(pre_n))
    return release + tail


def parse_range(text):
    """Диапазон ">=1.0, <2" → список (оператор, версия); неверный — UsageError."""
    parts = [p.strip() for p in text.split(",")]
    if not parts or any(not p for p in parts):
        raise UsageError(f"неверный диапазон версий «{text}»: нужен вида \">=1.0, <2\"")
    out = []
    for part in parts:
        m = _COMPARATOR.match(part)
        if not m:
            raise UsageError(f"неверное условие «{part}» в диапазоне «{text}»: есть >=, <=, ==, >, <")
        out.append((m.group(1), parse(m.group(2))))
    return out


def satisfies(version, range_text):
    """Удовлетворяет ли версия диапазону."""
    v = parse(version)
    for op, bound in parse_range(range_text):
        # Граница без предварительной части («<2») сравнивается как 2.0.0-dev0:
        # 2.0.0-rc1 в «<2» не входит.
        if op == "<" and bound[3:] == (2, 0):
            bound = bound[:3] + (0, 0)
        ok = {
            ">=": v >= bound, "<=": v <= bound, "==": v == bound,
            ">": v > bound, "<": v < bound,
        }[op]
        if not ok:
            return False
    return True
