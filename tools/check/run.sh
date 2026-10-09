#!/bin/bash
# Проверки репозитория: tools/check/run.sh [static|full] [--verbose] [--write-status]
#   static — структура, текст, секреты, ссылки, номера, процесс (секунды);
#   full   — то же и самотесты проверок (перед переводом PR из черновика и перед вливанием).
# Коды возврата: 0 — пройдено, 1 — находки, 2 — неверный вызов, 3 — сбой проверки.
# Подробности — tools/check/check.py и docs/PROCESS.md, раздел 2.
set -euo pipefail
exec python3 -B -X utf8 "$(dirname "$0")/check.py" "$@"
