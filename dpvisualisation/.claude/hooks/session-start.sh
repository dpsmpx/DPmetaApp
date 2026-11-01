#!/bin/bash
# Начало сессии (CLAUDE.md, «Не терять работу между сессиями»): что нужно для
# проверок и ветки с работой, не влитой в main, — продолжать с них.
set -uo pipefail
cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}" || exit 0

echo "== Начало сессии: DPvisualisation =="
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)' 2>/dev/null \
  || echo "! нужен Python 3.11+ — без него не работают tools/dpvis и tools/check"
command -v node >/dev/null 2>&1 || echo "! нет Node.js — тесты просмотрщика (full) не запустятся"
echo "Правила — CLAUDE.md; решения — docs/direction/DECISIONS.md; незаконченное — TODO.md."
echo "Проверки: tools/check/run.sh (после правки); перед вливанием — full и независимая проверка (D-21)."
echo

timeout 20 git fetch --prune origin >/dev/null 2>&1 || echo "! git fetch не удался — список веток может быть устаревшим"
base=origin/main
git rev-parse --verify -q "$base" >/dev/null || base=main
echo "Ветки с работой, не влитой в $base (задача там уже начата — продолжать с ветки):"
found=0
while read -r br; do
  [ -z "$br" ] && continue
  n=$(git rev-list --count "$base..$br" 2>/dev/null || echo 0)
  if [ "$n" -gt 0 ]; then
    found=1
    echo "  $br — коммитов сверх $base: $n; последний: $(git log -1 --format='%cs %s' "$br")"
  fi
done < <(git for-each-ref --format='%(refname:short)' refs/remotes/origin refs/heads | grep -vE '^(origin/)?(main|HEAD)$|^origin$')
[ $found -eq 0 ] && echo "  нет"
echo "Открытые PR — через GitHub; что не закончено — в описании PR («Осталось») и TODO.md."
echo "Текущая ветка: $(git branch --show-current)"
exit 0
