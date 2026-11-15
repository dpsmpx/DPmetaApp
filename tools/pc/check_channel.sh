#!/bin/sh
# Проверка канала к домашнему ПК с телефона (DPmetaApp:D-88, docs/PC_CHANNEL.md): Termux или
# терминал Linux приложения, VPN включён, как обычно. Только соединения: внешний адрес
# телефона по IPv4/IPv6 (api.ipify.org, api6.ipify.org) и попытка TCP до ПК. Ничего не меняет.
# Итог — без полных адресов: можно прислать в сессию как есть.
#   sh tools/pc/check_channel.sh <адрес ПК: IPv6 из check_channel.ps1 или внешний IPv4> [порт, по умолчанию 22]
set -u

addr="${1:-}"
port="${2:-22}"
command -v curl >/dev/null 2>&1 || { echo "нужен curl: pkg install curl (Termux) или apk add curl"; exit 2; }

say() { printf '%s: %s\n' "$1" "$2"; }

if curl -6 -s --max-time 10 https://api6.ipify.org >/dev/null 2>&1; then v6=да; else v6=нет; fi
if curl -4 -s --max-time 10 https://api.ipify.org >/dev/null 2>&1; then v4=да; else v4=нет; fi

tcp=пропущено
if [ -n "$addr" ]; then
    case "$addr" in
        *:*) url="telnet://[$addr]:$port" ;;
        *)   url="telnet://$addr:$port" ;;
    esac
    # curl печатает «Connected to», когда TCP установлен; дальше ждать нечего.
    if curl -v --connect-timeout 7 --max-time 8 "$url" </dev/null 2>&1 | grep -q "Connected to"; then
        tcp=да
    else
        tcp=нет
    fi
fi

echo ""
echo "=== Канал к ПК с телефона: итог (адреса скрыты — можно прислать в сессию) ==="
say "Телефон: выход по IPv6" "$v6"
say "Телефон: выход по IPv4" "$v4"
case "$addr" in
    *:*) say "Адрес ПК" "IPv6 $(printf '%s' "$addr" | cut -d: -f1-2):…" ;;
    "")  say "Адрес ПК" "не задан" ;;
    *)   say "Адрес ПК" "IPv4 $(printf '%s' "$addr" | cut -d. -f1-2).…" ;;
esac
say "TCP до ПК, порт $port" "$tcp"
