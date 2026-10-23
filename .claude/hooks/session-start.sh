#!/bin/bash
# Хук начала веб-сессии Claude Code (docs/PROCESS.md, «Работа между сессиями»).
#
#   * окружение проверок: JDK 21, python3, git — только сообщение, без установки;
#   * ветки с работой, не влитой в main, — свежие первыми: с них продолжают,
#     а не делают заново; ветки сборок (`*_debug`, D-67) — не работа, их нет.
#
# Только в веб-сессии; ничего не меняет в рабочем дереве.
set -uo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
    exit 0
fi

PROJ="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"

for tool in java python3 git; do
    command -v "$tool" >/dev/null 2>&1 || echo "session-start: нет $tool — tools/verify/run.sh doctor"
done

{
    git -C "$PROJ" fetch -q origin 2>/dev/null || true
    echo "session-start: ветки с работой, не влитой в main (свежие первыми):"
    found=0
    while read -r ref; do
        n="$(git -C "$PROJ" rev-list --count "origin/main..$ref" 2>/dev/null || echo 0)"
        [ "$n" -gt 0 ] || continue
        found=1
        echo "  ${ref#origin/}: коммитов $n, последний — $(git -C "$PROJ" log -1 --format='%cr: %s' "$ref")"
    done < <(git -C "$PROJ" for-each-ref --sort=-committerdate --format='%(refname:short)' \
                 refs/remotes/origin | grep -v -e '^origin$' -e '^origin/HEAD$' -e '^origin/main$' -e '_debug$' | head -8)
    [ "$found" -eq 1 ] || echo "  нет"
    echo "  Перед работой сверить с ними задачу: git log origin/main..origin/<ветка>."
} || true
