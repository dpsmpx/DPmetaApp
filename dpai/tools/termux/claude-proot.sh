#!/data/data/com.termux/files/usr/bin/sh
# Claude Code на Termux-target — внутри proot-distro (Alpine), D-33:
# нативной сборки для Android нет, musl-сборке нужен загрузчик из proot
# (docs/research/CLAUDE_CODE_CLI.md, §9). Ставится как $PREFIX/bin/claude,
# поэтому агент dpai вызывает `claude` как на любом target.
#
# Рабочий каталог и CLAUDE_CONFIG_DIR слота (D-29) передаются внутрь;
# файловая система Termux в proot видна по тем же путям.
set -e
CC="${DPAI_CLAUDE_MUSL:-$HOME/.local/share/claude-musl}"
if [ -n "$CLAUDE_CONFIG_DIR" ]; then
    set -- -e "CLAUDE_CONFIG_DIR=$CLAUDE_CONFIG_DIR" -- /opt/claude/claude "$@"
else
    set -- -- /opt/claude/claude "$@"
fi
exec proot-distro login alpine -b "$CC:/opt/claude" -w "$PWD" "$@"
