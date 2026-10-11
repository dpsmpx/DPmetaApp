#!/usr/bin/env python3
"""Формат текстовых файлов: UTF-8, LF, перевод строки в конце, без хвостовых
пробелов и табуляций, без невидимых и двунаправленных символов (D-18).

Двунаправленные и невидимые символы опасны в коде: строка выглядит не тем,
чем является (Trojan Source). Неразрывный пробел допустим — это типографика.

    python3 tools/verify/check_format.py

Код возврата: 0 — чисто; 1 — находки.
"""
from common import Findings, files, is_text, main, root
import os

# Исключения — с причиной (docs/PROCESS.md, «Правила»).
EXEMPT = {
    # Текст автора: не правится ради удобства (D-19); в нём нет перевода строки в конце.
    "docs/direction/MASTER_TECHNICAL_TASK.md": {"final-newline"},
    # Создан Gradle (`gradle wrapper`), пакетный файл Windows — с CRLF намеренно.
    "gradlew.bat": {"cr"},
}

FORBIDDEN = {
    0x200B: "ZERO WIDTH SPACE", 0x200C: "ZERO WIDTH NON-JOINER", 0x200D: "ZERO WIDTH JOINER",
    0x2060: "WORD JOINER", 0xFEFF: "BOM / ZERO WIDTH NO-BREAK SPACE", 0x00AD: "SOFT HYPHEN",
    0x202A: "LRE", 0x202B: "RLE", 0x202C: "PDF", 0x202D: "LRO", 0x202E: "RLO",
    0x2066: "LRI", 0x2067: "RLI", 0x2068: "FSI", 0x2069: "PDI", 0x200E: "LRM", 0x200F: "RLM",
}


def check():
    f = Findings("формат")
    count = 0
    for rel in files():
        if not is_text(rel):
            continue
        count += 1
        exempt = EXEMPT.get(rel, set())
        data = open(os.path.join(root(), rel), "rb").read()
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError as e:
            f.add(rel, f"не UTF-8 (байт {e.start})")
            continue
        if b"\r" in data and "cr" not in exempt:
            f.add(rel, "CR или CRLF в конце строки — нужен LF")
        if data and not data.endswith(b"\n") and "final-newline" not in exempt:
            f.add(rel, "нет перевода строки в конце файла")
        for no, line in enumerate(text.split("\n"), 1):
            stripped = line.rstrip("\r")
            if stripped != stripped.rstrip(" \t"):
                f.add(f"{rel}:{no}", "хвостовые пробелы")
            if "\t" in stripped and not rel.endswith(".bat"):
                f.add(f"{rel}:{no}", "табуляция")
            for ch in stripped:
                if ord(ch) in FORBIDDEN:
                    f.add(f"{rel}:{no}", f"символ U+{ord(ch):04X} {FORBIDDEN[ord(ch)]}")
                    break
    return f.finish(f"файлов — {count}")


if __name__ == "__main__":
    main(check)
