#!/usr/bin/env python3
"""Разбор журнала упавшего шага Gradle: отказ загрузки или сбой кода.

run.sh повторяет шаг Gradle, только если отказ — загрузка зависимостей, а
не код (docs/PROCESS.md, «Правила»). Строка `> Task :x FAILED` о причине не
говорит: Gradle пишет её и тогда, когда задача не смогла скачать свой
classpath (429 от Maven Central через прокси). Поэтому решают признаки
сбоя кода — ошибка компилятора, упавший тест, — а не слово FAILED.

    python3 tools/verify/gradle_log.py <журнал>

Код возврата: 0 — отказ загрузки и нет признаков сбоя кода, шаг можно
повторить; 1 — нет (сбой кода, тест или причина не опознана).
"""
import os
import re
import sys

from common import root

DOWNLOAD = re.compile(r"status code 429|Could not (GET|HEAD|download|resolve)")
CODE = re.compile(
    r"^e: "                          # ошибка компилятора Kotlin
    r"|Compilation error|Compilation failed"
    r"|There were failing tests"
    r"|^[^>\s].* > .*FAILED$",       # строка упавшего теста: `CliTest > version() FAILED`
    re.MULTILINE)


def download_only(text):
    """Отказ — загрузка зависимостей, и нет ни одного признака сбоя кода."""
    return bool(DOWNLOAD.search(text)) and not CODE.search(text)


def main(argv):
    if len(argv) != 1:
        print("использование: gradle_log.py <журнал>", file=sys.stderr)
        return 2
    path = argv[0] if os.path.isabs(argv[0]) else os.path.join(root(), argv[0])
    with open(path, encoding="utf-8", errors="replace") as fh:
        return 0 if download_only(fh.read()) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
