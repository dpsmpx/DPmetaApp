#!/bin/bash
# Проверки репозитория: tools/check/run.sh [static|full [--strict]] [--verbose] [--write-status]
#   static — структура, текст, секреты, ссылки, номера, процесс, ядро, wrapper (секунды);
#   full   — то же, самотесты проверок, проверки подсистемы dpai/ и ./gradlew build из корня
#            (перед переводом PR из черновика и перед вливанием);
#   --strict — с full: пропуск сборки (нет JDK, нет Android SDK) — ошибка (перед выпуском).
# Коды возврата: 0 — пройдено, 1 — находки, 2 — неверный вызов, 3 — сбой проверки.
# Подробности — tools/check/check.py и docs/PROCESS.md, раздел 2.
set -euo pipefail
exec python3 -B -X utf8 "$(dirname "$0")/check.py" "$@"
