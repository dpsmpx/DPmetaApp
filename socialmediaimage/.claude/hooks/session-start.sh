#!/bin/bash
# Начало сессии (CLAUDE.md, «С чего начинать сессию»): зависимости проверок,
# где остановилась прошлая сессия, ветки с невлитой работой.
set -uo pipefail
cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}" || exit 0

# Зависимости tools/cs — в облачной сессии ставятся, если их нет (идемпотентно).
if ! python3 -c "import yaml, jsonschema" 2>/dev/null; then
  if [ "${CLAUDE_CODE_REMOTE:-}" = "true" ]; then
    python3 -m pip install -q --require-hashes -r tools/requirements.txt >/dev/null 2>&1 \
      || echo "! не удалось установить зависимости: python3 -m pip install --require-hashes -r tools/requirements.txt"
  else
    echo "! нужны PyYAML и jsonschema: python3 -m pip install --require-hashes -r tools/requirements.txt"
  fi
fi
if ! command -v node >/dev/null 2>&1; then
  echo "! Node.js не найден — рендер недоступен; проверки: tools/check.sh --fast"
fi

echo "== Начало сессии: SocialMediaImage =="
echo "1. Прочитать docs/session/HANDOFF.md — где остановилась прошлая сессия."
echo "2. Затем docs/context/AUTHOR_MASTER_CONTEXT.md; под задачу — DECISIONS.md, TODO.md."
echo

timeout 20 git fetch --prune origin >/dev/null 2>&1 || echo "! git fetch не удался — список веток может быть устаревшим"
base=origin/main
git rev-parse --verify -q "$base" >/dev/null || base=main
echo "Ветки с работой, не влитой в $base (продолжать с них, а не делать заново):"
found=0
while read -r br; do
  [ -z "$br" ] && continue
  n=$(git rev-list --count "$base..$br" 2>/dev/null || echo 0)
  if [ "$n" -gt 0 ]; then
    found=1
    echo "  $br — коммитов сверх $base: $n; последний: $(git log -1 --format='%cs' "$br")"
  fi
done < <(git for-each-ref --format='%(refname:short)' refs/remotes/origin refs/heads | grep -vE '^(origin/)?(main|HEAD)$|^origin$')
[ $found -eq 0 ] && echo "  нет"
echo "Открытые PR — через GitHub (MCP: list_pull_requests); незаконченное — в описании PR и TODO.md."
echo "Текущая ветка: $(git branch --show-current)"
exit 0
