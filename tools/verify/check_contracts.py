#!/usr/bin/env python3
"""Публичные контракты только растут: коды диагностики и типы событий (D-05, D-12).

Имя кода диагностики и типа события — публичный контракт: по нему пишутся
ожидания тестов и корпуса, по нему читаются сохранённые события. Проверка:

  * каждое имя перечисления есть в реестре tools/verify/registry/<реестр>.txt,
    и каждое имя реестра есть в перечислении (удалить или переименовать
    нельзя — только добавить);
  * реестр не потерял строк относительно origin/main (если ветка есть):
    удаление из реестра вместе с кодом тоже ловится.

    python3 tools/verify/check_contracts.py

Код возврата: 0 — чисто; 1 — нарушения.
"""
import os
import re
import subprocess

from common import Findings, main, read, root

CONTRACTS = [
    # (перечисление, файл, реестр)
    ("DiagCode", "core/src/main/kotlin/com/dpsmpx/dpai/core/diagnostics/DiagCode.kt",
     "tools/verify/registry/diag_codes.txt"),
    ("EventType", "core/src/main/kotlin/com/dpsmpx/dpai/core/events/EventType.kt",
     "tools/verify/registry/event_types.txt"),
]


def enum_entries(text, name):
    m = re.search(r"enum\s+class\s+" + name + r"\b[^{]*\{(.*?)(?:;\s*\n|\}\s*$)", text, re.S)
    if not m:
        return None
    body = re.sub(r"//[^\n]*|/\*.*?\*/", "", m.group(1), flags=re.S)
    return re.findall(r"^\s*([A-Z][A-Z0-9_]*)\s*(?:\(|,|$)", body, re.M)


def registry_entries(text):
    return [l.split("#", 1)[0].strip() for l in text.splitlines() if l.split("#", 1)[0].strip()]


def base_registry(rel):
    try:
        out = subprocess.run(["git", "show", f"origin/main:{rel}"], cwd=root(),
                             capture_output=True, check=True).stdout.decode("utf-8")
        return registry_entries(out)
    except (subprocess.CalledProcessError, FileNotFoundError):
        return None


def check():
    f = Findings("контракты")
    total = 0
    for name, src, reg in CONTRACTS:
        has_src = os.path.isfile(os.path.join(root(), src))
        has_reg = os.path.isfile(os.path.join(root(), reg))
        if not has_src and not has_reg:
            continue
        if not has_src:
            f.add(src, f"нет файла перечисления {name}, а реестр {reg} есть")
            continue
        entries = enum_entries(read(src), name)
        if entries is None:
            f.add(src, f"не найдено перечисление {name}")
            continue
        registry = registry_entries(read(reg)) if has_reg else []
        if len(set(registry)) != len(registry):
            f.add(reg, "повтор имени в реестре")
        for e in sorted(set(entries) - set(registry)):
            f.add(reg, f"{name}.{e} нет в реестре — добавить строку")
        for e in sorted(set(registry) - set(entries)):
            f.add(src, f"{name}.{e} есть в реестре, но удалён или переименован — контракт только растёт")
        base = base_registry(reg)
        if base is not None:
            for e in sorted(set(base) - set(registry)):
                f.add(reg, f"{e} был в реестре origin/main и пропал")
        total += len(entries)
    return f.finish(f"имён — {total}")


if __name__ == "__main__":
    main(check)
