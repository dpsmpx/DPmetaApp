#!/bin/bash
# Хук начала веб-сессии Claude Code (шаблон process-kit DP Verify).
#
#   * проектная часть — пакеты и инструменты, которые нужны командам
#     профиля tools/dpverify.toml (их requires);
#   * общая — dpv doctor (чего не хватает) и ветки с невлитой работой.
#
# Только в веб-сессии; повторный запуск ничего не ставит заново.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
    exit 0
fi

PROJ="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"

# ---- Проектная часть ----
# dpv:fill пакеты проекта, например:
#   PKGS=(cmake ninja-build)
#   MISSING=(); for p in "${PKGS[@]}"; do dpkg -s "$p" >/dev/null 2>&1 || MISSING+=("$p"); done
#   [ "${#MISSING[@]}" -eq 0 ] || { apt-get update -qq; apt-get install -y -qq "${MISSING[@]}"; }

# ---- Окружение для DP Verify ----
"$PROJ/tools/dpv/dpv" doctor || true

# ---- Невлитая работа прошлых сессий ----
#
# Сессию обрывает лимит, и сделанное живёт только в запушенных ветках
# (CLAUDE.md, «Не терять работу между сессиями»). Здесь — ветки с
# коммитами, которых нет в основной ветке, свежие первыми: с них
# продолжают, а не делают заново. Блок — из хуков VoxelRPG и DPlang.
{
    MAIN="$(sed -n 's/^main_branch *= *"\(.*\)"/\1/p' "$PROJ/tools/dpverify.toml" 2>/dev/null | head -1)"
    MAIN="${MAIN:-main}"
    # refspec явно: клон с одной веткой (--single-branch, --depth) другие ветки не видит
    git -C "$PROJ" fetch -q origin '+refs/heads/*:refs/remotes/origin/*' 2>/dev/null || true
    echo "session-start: ветки с работой, не влитой в $MAIN (свежие первыми):"
    found=0
    while read -r ref; do
        n="$(git -C "$PROJ" rev-list --count "origin/$MAIN..$ref" 2>/dev/null || echo 0)"
        [ "$n" -gt 0 ] || continue
        found=1
        echo "  ${ref#origin/}: коммитов $n, последний — $(git -C "$PROJ" log -1 --format='%cr: %s' "$ref")"
    done < <(git -C "$PROJ" for-each-ref --sort=-committerdate --format='%(refname:short)' \
                 refs/remotes/origin | grep -v -e '^origin$' -e '^origin/HEAD$' -e "^origin/$MAIN\$" | head -8)
    [ "$found" -eq 1 ] || echo "  нет"
    echo "  Перед работой сверить с ними задачу: git log origin/$MAIN..origin/<ветка>."
} || true
