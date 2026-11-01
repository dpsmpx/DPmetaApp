#!/usr/bin/env bash
# Все проверки репозитория (Д3: GitHub Actions нет — проверяет этот скрипт в сессии).
#
#   tools/check.sh             full: все проверки + все тесты (с рендером)
#   tools/check.sh --fast      full без Chromium: без тестов рендера и снимков сайта (siteshots)
#   tools/check.sh --quick     quick: схемы, объекты, секреты, ссылки, адресаты, матрица — без тестов
#   tools/check.sh --affected  affected: проверки по изменённым файлам (от origin/main), тесты — если менялся код
#   tools/check.sh --release   release: full + сборка production-сайта и сводки кампаний
#   tools/check.sh claims      только названные проверки (без тестов)
#
# Проверки — python3 -m tools.cs check (docs/ARCHITECTURE.md). Красное — не
# коммитить как готовое (CLAUDE.md).
set -uo pipefail
cd "$(dirname "$0")/.."

fast=0
level=full
names=()
for a in "$@"; do
  case "$a" in
    --fast) fast=1 ;;
    --quick) level=quick ;;
    --affected) level=affected ;;
    --release) level=release ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
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
run_tests=1
if [ "$level" = quick ] && [ ${#names[@]} -eq 0 ]; then
  names=(schemas items secrets links destinations capabilities)
fi
if [ "$level" = affected ] && [ ${#names[@]} -eq 0 ]; then
  aff=$(python3 -m tools.cs affected)
  echo "затронуто: $aff"
  case "$aff" in *"+tests"*) run_tests=1; fast=1 ;; *) run_tests=0 ;; esac
  read -r -a names <<< "${aff/ +tests/}"
  [ $run_tests -eq 1 ] && names=()
fi
# --fast: без Chromium — и в проверках (siteshots), и в тестах рендера
[ $fast -eq 1 ] && export CS_SKIP_RENDER=1
python3 -m tools.cs check "${names[@]}" || status=1

if [ "$level" = release ]; then
  echo
  echo "release: production-сайт и кампании"
  python3 -m tools.cs site build --env production --out build/release-site || status=1
  for c in data/campaigns/CMP-*.yaml; do
    [ -f "$c" ] && python3 -m tools.cs campaign status "$(basename "$c" .yaml)"
  done
fi

if [ ${#names[@]} -eq 0 ] && [ "$level" != quick ] && [ $run_tests -eq 1 ]; then
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

# Запись проверки для выгрузки (Д47, DPmetaApp:D-48): release без --fast, всё зелёное, дерево системы
# чистое — какой коммит проверен; ярлык выгрузки отказывает без неё и если система менялась после.
if [ "$level" = release ] && [ $status -eq 0 ] && [ $fast -eq 0 ]; then
  echo
  if [ -n "$(git status --porcelain -- . ':!data/website/site-check.json')" ]; then
    echo "запись проверки не сделана: в системе есть незакоммиченное — проверяется только закоммиченное"
  else
    python3 - "$(git rev-parse HEAD)" <<'PY'
import datetime, json, sys
rec = {"commit": sys.argv[1], "level": "release",
       "checked": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}
open("data/website/site-check.json", "w", encoding="utf-8").write(json.dumps(rec, ensure_ascii=False, indent=1) + "\n")
PY
    echo "запись проверки: data/website/site-check.json — $(git rev-parse --short=12 HEAD); закоммитить и запушить"
  fi
fi

echo
if [ $status -eq 0 ]; then echo "✓ tools/check.sh: всё зелёное"; else echo "✗ tools/check.sh: есть ошибки"; fi
exit $status
