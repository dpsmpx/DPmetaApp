#!/usr/bin/env python3
"""Разбор журнала упавшего шага Gradle: временный отказ загрузки или нет.

run.sh повторяет шаг Gradle, только если отказ — временный сбой загрузки
зависимостей, а не код и не настройка (docs/PROCESS.md, «Правила»). Решают
причины, а не обёртки: строку `> Task :x FAILED` и `Could not resolve all
files …` Gradle пишет при любом отказе разрешения зависимостей — и при 429
от Maven Central через прокси, и при опечатке в версии.

Повтор — только если все три условия:

- есть временная причина: HTTP 429 или 5xx, обрыв или тайм-аут соединения;
- нет постоянной: артефакт не найден, другой ответ 4xx, сертификат, нет
  подходящего варианта — повтор их не исправит;
- нет признаков сбоя кода: ошибка компилятора, упавший тест, упавший
  процесс тестов, тесты не найдены, ошибка в сценарии сборки.

Неопознанная причина — не повтор.

    python3 tools/verify/gradle_log.py <журнал>

Код возврата: 0 — шаг можно повторить; 1 — нет.
"""
import os
import re
import sys

from common import root

TRANSIENT = re.compile(
    r"status code (429|5\d\d)\b"                  # Received status code 429 from server
    r"|HTTP/1\.[01] (429|5\d\d)\b"                # прокси не открыл туннель
    r"|HTTP response code: (429|5\d\d)\b"         # загрузка дистрибутива wrapper
    r"|Connection reset|Read timed out|[Cc]onnect timed out|SocketTimeoutException")
PERMANENT = re.compile(
    r"Could not find "                            # нет артефакта, версии, метода в сценарии
    r"|status code (4(?!29)\d\d)\b"               # 4xx, кроме 429: доступ, адрес
    r"|PKIX path building failed|unable to find valid certification path"
    r"|No matching variant")
CODE = re.compile(
    r"^e: "                                       # ошибка компилятора Kotlin
    r"|Compilation error|Compilation failed"
    r"|There were failing tests"
    r"|^[^>\s].* > .*FAILED$"                     # строка упавшего теста: `CliTest > version() FAILED`
    r"|Test process encountered an unexpected problem"
    r"|finished with non-zero exit value"         # упал процесс тестов или команды
    r"|did not discover any tests"
    r"|A problem occurred evaluating",            # исключение в сценарии сборки
    re.MULTILINE)


def retryable(text):
    """Временный отказ загрузки — и ни постоянного отказа, ни признака сбоя кода."""
    return bool(TRANSIENT.search(text)) and not PERMANENT.search(text) and not CODE.search(text)


def main(argv):
    if len(argv) != 1:
        print("использование: gradle_log.py <журнал>", file=sys.stderr)
        return 2
    path = argv[0] if os.path.isabs(argv[0]) else os.path.join(root(), argv[0])
    with open(path, encoding="utf-8", errors="replace") as fh:
        return 0 if retryable(fh.read()) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
