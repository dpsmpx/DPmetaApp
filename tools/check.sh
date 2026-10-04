#!/usr/bin/env bash
# Все проверки репозитория (Д3: GitHub Actions нет — проверяет этот скрипт в сессии).
#
#   tools/check.sh           всё: проверки данных и контента + тесты (с рендером)
#   tools/check.sh --fast    без тестов, которым нужен рендер (Node + Chromium)
#   tools/check.sh claims    только названные проверки (без тестов)
#
# Проверки — python3 -m tools.cs check (docs/ARCHITECTURE.md). Красное — не
# коммитить как готовое (CLAUDE.md).
set -uo pipefail
cd "$(dirname "$0")/.."

fast=0
names=()
for a in "$@"; do
  case "$a" in
    --fast) fast=1 ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) names+=("$a") ;;
  esac
done

python3 - <<'PY' || exit 2
import sys
missing = []
for mod in ("yaml", "jsonschema"):
    try:
        __import__(mod)
    except ImportError:
        missing.append(mod)
if sys.version_info < (3, 10):
    missing.append("Python 3.10+")
if missing:
    print("нужны: " + ", ".join(missing) + " (pip install pyyaml jsonschema)", file=sys.stderr)
    sys.exit(1)
PY

status=0
python3 -m tools.cs check "${names[@]}" || status=1

if [ ${#names[@]} -eq 0 ]; then
  echo
  if [ $fast -eq 1 ]; then
    export CS_SKIP_RENDER=1
    echo "тесты (без рендера):"
  else
    echo "тесты:"
  fi
  out=$(python3 -m unittest discover -s tests -t . 2>&1); rc=$?
  echo "$out" | tail -n 25
  [ $rc -eq 0 ] || status=1
fi

echo
if [ $status -eq 0 ]; then echo "✓ tools/check.sh: всё зелёное"; else echo "✗ tools/check.sh: есть ошибки"; fi
exit $status
