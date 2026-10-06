#!/usr/bin/env bash
# hostcheck — все проверки DPpass одной командой (docs/PROCESS.md, «Проверки»).
# Обёртка над DP Verify (D-50): прежние ключи, проверки — tools/dpv/dpv по
# профилю tools/dpverify.toml.
#
#   tools/hostcheck/run.sh            dpv run full — всё, что можно на этой машине; пропуск — громко, но не ошибка
#   tools/hostcheck/run.sh --quick    dpv run static — только быстрые проверки (без Gradle)
#   tools/hostcheck/run.sh --full     dpv run full --ci — перед слиянием (CI нет, D-23): на закоммиченном
#                                     дереве, каждый пропуск (нет Android SDK, NDK, Go) — ошибка
#
# Код возврата — как у dpv: 0 — пройдено; 1 — есть ✗; 2 — неверный вызов
# или профиль; 3 — сбой DP Verify. Итог для PR — tools/dpv/dpv report --md.
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 2

case "${1:-}" in
  "") exec "${PYTHON:-python3}" tools/dpv/dpv run full ;;
  --quick) exec "${PYTHON:-python3}" tools/dpv/dpv run static ;;
  --full) exec "${PYTHON:-python3}" tools/dpv/dpv run full --ci ;;
  -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
  *) echo "неизвестный параметр: $1 (есть --quick, --full)"; exit 2 ;;
esac
