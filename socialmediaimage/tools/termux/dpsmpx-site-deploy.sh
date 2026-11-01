#!/data/data/com.termux/files/usr/bin/bash
# Выгрузка сайта dpsmpx.com одной кнопкой Termux:Widget (Д35, Д36).
# Только вызывает систему: rsync, адресов и путей сервера здесь нет — адрес
# берёт `site deploy` из CS_PROD_SITE_DEPLOY_TARGET (~/.config/dpsmpx/deploy.env).
# Установка (docs/operations/CREDENTIALS.md, «Ярлык Termux:Widget»):
#   mkdir -p ~/.shortcuts && chmod 700 ~/.shortcuts
#   install -m 700 ~/DPmetaApp/socialmediaimage/tools/termux/dpsmpx-site-deploy.sh ~/.shortcuts/
# Каталог — ~/.shortcuts/, не ~/.shortcuts/tasks/: оттуда ярлык идёт без
# терминала и не может спросить «да».
set -eu

PY="${DPSMPX_PY:-/data/data/com.termux/files/usr/bin/python3}"   # Python из pkg, с jsonschema
REPO="${DPSMPX_REPO:-$HOME/DPmetaApp/socialmediaimage}"   # система — подсистема DPmetaApp (Д45)
ENVF="$HOME/.config/dpsmpx/deploy.env"
SITE="https://dpsmpx.com/"   # HTTPS подтвердил автор 12.10.2026 (В5)

[ -t 0 ] && trap 'printf "\nEnter — закрыть "; read -r _' EXIT
fail() { echo "✗ $*"; exit 1; }
[ -z "${CLAUDECODE:-}${CLAUDE_CODE_REMOTE:-}" ] || fail "ярлык — только для автора; сессия ИИ выгружает python3 -m tools.cs site deploy после «да» (Д36)"

[ -f "$ENVF" ] || fail "нет $ENVF — CREDENTIALS.md, Termux, шаг 6"
. "$ENVF"
cd "$REPO" || fail "нет каталога $REPO"
[ "$(git branch --show-current)" = main ] || fail "ветка не main — отказ"
if [ -n "$(git status --porcelain -- . ':!data/provenance/site-releases')" ]; then
  fail "есть незакоммиченное — отказ"
fi
if [ -n "$(git status --porcelain -- data/provenance/site-releases)" ]; then
  fail "манифест прошлой выгрузки не закоммичен (Д41): git add data/provenance/site-releases && git commit && git push — затем снова"
fi
git pull --ff-only || fail "git pull не прошёл — сеть или расхождение с GitHub"

echo "== пробный прогон"
out=$("$PY" -m tools.cs site deploy --dry-run) || { echo "$out"; fail "пробный прогон не прошёл — выгрузки не будет"; }
echo "$out"
n=$(printf '%s\n' "$out" | sed -n 's/^ *изменений: \([0-9][0-9]*\).*/\1/p')
[ -n "$n" ] || fail "не нашёл строку «изменений: N» — выгрузки не будет"
if [ "$n" = 0 ]; then
  echo "Сервер совпадает со сборкой — выгружать нечего."
  exit 0
fi
if printf '%s\n' "$out" | grep -q '^ *\*deleting'; then
  echo "⚠ Есть удаления (*deleting) — удаляться должно только старое содержимое сайта."
fi

printf 'Изменений: %s. Выгрузить на хостинг? Наберите да: ' "$n"
read -r ok || ok=""
[ "$ok" = да ] || fail "отменено"

echo "== выгрузка"
rc=0
"$PY" -m tools.cs site deploy || rc=$?

code=$("$PY" -c 'import sys, urllib.request as u, urllib.error as e
try:
    print(u.urlopen(sys.argv[1], timeout=15).status)
except e.HTTPError as x:
    print(x.code)
except Exception:
    print("нет ответа")' "$SITE")
echo "$SITE — $code"
[ "$code" = 200 ] || fail "главная отвечает не 200. Если 500 — Beget не принимает строку .htaccess (Options -Indexes): сообщить сессии"
[ "$rc" = 0 ] || fail "site deploy завершился с кодом $rc — см. вывод выше"
echo "✓ готово"
