#!/bin/bash
# ============================================================
# Хук начала сессии Claude Code (CLAUDE.md, «Не терять работу
# между сессиями»; docs/PROCESS.md, раздел 1):
#
#   * чем богато окружение: python3 и git — для tools/check/run.sh;
#     JDK и Android SDK — для сборки с этапа 1 (без SDK собирается
#     только ядро, D-03);
#   * ветки с работой, не влитой в main, — свежие первыми: с них
#     продолжают, а не делают заново.
#
# Ничего не ставит и не пишет; любая ошибка — мимо: хук не должен
# мешать начать сессию.
# ============================================================
PROJ="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"

{
    echo "== Начало сессии: DPmetaApp =="
    echo "Правила — CLAUDE.md; этап и шаги — ROADMAP/ROADMAP.md; хвосты — TODO.md."
    for tool in python3 git java; do
        if command -v "$tool" >/dev/null 2>&1; then
            echo "  $tool: есть"
            continue
        fi
        case "$tool" in
            java) why="сборка ядра (этап 1) недоступна" ;;
            *)    why="проверки tools/check недоступны" ;;
        esac
        echo "  ! $tool: нет — $why"
    done
    sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -n "$sdk" ] && [ -d "$sdk" ]; then
        echo "  Android SDK: $sdk"
    else
        echo "  Android SDK: нет — :app не собирается, полный режим проверок назовёт это пропуском"
    fi
} || true

{
    git -C "$PROJ" fetch -q --prune origin 2>/dev/null || echo "  ! git fetch не удался — список веток может быть устаревшим"
    echo "Ветки с работой, не влитой в main (свежие первыми):"
    found=0
    while read -r ref; do
        n="$(git -C "$PROJ" rev-list --count "origin/main..$ref" 2>/dev/null || echo 0)"
        [ "$n" -gt 0 ] || continue
        found=1
        echo "  ${ref#origin/}: коммитов $n, последний — $(git -C "$PROJ" log -1 --format='%cr: %s' "$ref")"
    done < <(git -C "$PROJ" for-each-ref --sort=-committerdate --format='%(refname:short)' \
                 refs/remotes/origin 2>/dev/null | grep -v -e '^origin$' -e '^origin/HEAD$' -e '^origin/main$' | head -8)
    [ "$found" -eq 1 ] || echo "  нет"
    echo "  Перед работой сверить с ними задачу: git log origin/main..origin/<ветка>."
    echo "Открытые PR — через GitHub (MCP); незаконченное — в их описании и TODO.md."
    echo "Текущая ветка: $(git -C "$PROJ" branch --show-current 2>/dev/null)"
} || true
exit 0
