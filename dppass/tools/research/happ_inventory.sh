#!/usr/bin/env bash
# Опись публичных источников Happ — повторяемая часть аудита
# docs/research/HAPP_SOURCE_AUDIT.md (§1 «Метод», §2 «Таблица источников»).
#
#   tools/research/happ_inventory.sh [КАТАЛОГ]    по умолчанию third_party/happ
#
# Для каждого репозитория из списка ниже: проверяет, что он есть
# (git ls-remote), клонирует его в КАТАЛОГ без содержимого файлов
# (--filter=blob:none --no-checkout: нужны только имена и история) и
# печатает состав — последний коммит, файлы, файл лицензии, файлы
# исходного кода по расширениям. У happ-android — вся история: какие
# файлы в нём были когда-либо. У форков — последние коммиты с авторами,
# чтобы отделить собственные правки от upstream.
#
# Чего скрипт не делает (docs/direction/RESEARCH_SCOPE.md, §4): не
# скачивает сборки из Releases и магазинов, не декомпилирует, ничего не
# копирует в DPpass. КАТАЛОГ — вне репозитория (third_party/ в
# .gitignore). Вывод — сырьё: выводы записываются в исследование своими
# словами и с датой (docs/PROVENANCE.md, §6).
#
# Список репозиториев организации скрипт не получает сам (для этого
# нужен API GitHub): сверить его со страницей организации — часть
# повторного аудита.
#
# Код возврата: 0 — опись сделана; 1 — хотя бы один репозиторий
# недоступен (тоже результат: удалён, переименован или закрыт — повод
# обновить исследование); 2 — неверный вызов или нет git.
set -u

case "${1:-}" in
  -h|--help) sed -n '2,27p' "$0"; exit 0 ;;
esac
command -v git >/dev/null 2>&1 || { echo "нужен git"; exit 2; }

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEST="${1:-$ROOT/third_party/happ}"
mkdir -p "$DEST" || exit 2
DEST="$(cd "$DEST" && pwd)"
case "$DEST/" in
  "$ROOT/third_party/"*) ;;
  "$ROOT/"*) echo "КАТАЛОГ внутри репозитория, но не в third_party/: $DEST"; exit 2 ;;
esac

# Без запроса учётных данных: закрытый или удалённый репозиторий — отказ, а не вопрос.
export GIT_TERMINAL_PROMPT=0

# репозиторий  вид: own — собственный, own-history — собственный (вся история), fork — форк
REPOS=(
  "Happ-proxy/happ-android own-history"
  "Happ-proxy/happ-desktop own"
  "Happ-proxy/happ-ios own"
  "Happ-proxy/routing_generator own"
  "Happ-proxy/app_domain own"
  "Happ-proxy/happ_update_test own"
  "Happ-proxy/.github own"
  "Happ-proxy/Web-Dashboard own"
  "Happ-proxy/Xray-core fork"
  "Happ-proxy/Xray-docs-next fork"
  "Happ-proxy/3x-ui fork"
  "Happ-proxy/Marzban fork"
  "HappDev/happ_su own"
)

SOURCE_EXT='\.(kt|kts|java|swift|m|mm|dart|go|c|cc|cpp|h|hpp|rs|ts|tsx|js|jsx|php|py|cs|qml)$'
LICENSE_NAME='(^|/)(LICEN[CS]E|COPYING|NOTICE)([.-][^/]*)?$'

FAILED=0
echo "Опись источников Happ, $(date -u +%Y-%m-%d) (UTC); клоны — $DEST"

for entry in "${REPOS[@]}"; do
  repo="${entry% *}"; kind="${entry#* }"
  url="https://github.com/$repo.git"
  dir="$DEST/${repo//\//__}"
  echo
  echo "== $repo ($kind)"

  refs="$(git ls-remote "$url" 2>/dev/null)"; code=$?
  if [ "$code" != 0 ]; then
    echo "   ✗ недоступен (git ls-remote: код $code) — удалён, переименован или закрыт"
    FAILED=1
    continue
  fi
  if [ -z "$refs" ]; then
    echo "   пустой: ни одной ветки и тега"
    continue
  fi

  # Каждый прогон — с чистого клона: опись должна отражать источник, а не кеш.
  if [ -d "$dir/.git" ]; then rm -rf "$dir"; fi
  depth=()
  case "$kind" in
    own-history) ;;
    fork) depth=(--depth 5) ;;
    *) depth=(--depth 1) ;;
  esac
  if ! git clone --quiet --no-checkout --filter=blob:none "${depth[@]}" "$url" "$dir" 2>/dev/null; then
    echo "   ✗ не клонируется"
    FAILED=1
    continue
  fi

  g() { git -C "$dir" "$@"; }
  files="$(g ls-tree -r --name-only HEAD)"
  nfiles="$(printf '%s\n' "$files" | grep -c .)"
  license="$(printf '%s\n' "$files" | grep -E "$LICENSE_NAME" | tr '\n' ' ')"
  sources="$(printf '%s\n' "$files" | grep -E "$SOURCE_EXT" | sed -E 's/.*\.//' | sort | uniq -c | sort -rn | awk '{printf "%s %s, ", $2, $1}' | sed 's/, $//')"

  echo "   последний коммит: $(g log -1 --format='%h %cs «%s»')"
  echo "   ветка по умолчанию: $(g symbolic-ref --short HEAD 2>/dev/null || echo '?'); тегов: $(printf '%s\n' "$refs" | grep -c 'refs/tags/[^^]*$')"
  echo "   файлов: $nfiles; файл лицензии: ${license:-нет}"
  echo "   исходный код: ${sources:-нет}"
  if [ "$nfiles" -le 30 ]; then
    printf '%s\n' "$files" | sed 's/^/     /'
  else
    echo "   верхний уровень: $(g ls-tree --name-only HEAD | tr '\n' ' ')"
  fi

  case "$kind" in
    own-history)
      # Теги могут вести в историю, которой нет в ветке по умолчанию
      # (у happ-android так и есть): считаем и то, и другое.
      echo "   коммитов в ветке по умолчанию: $(g rev-list --count HEAD), первый: $(g log --reverse --format='%cs' HEAD | head -1)"
      echo "   коммитов по всем веткам и тегам: $(g rev-list --count --all), первый: $(g log --reverse --all --format='%cs' | head -1)"
      ever="$(g log --all --name-only --format= | grep . | sort -u)"
      echo "   файлы за всю историю, с тегами ($(printf '%s\n' "$ever" | grep -c .)), исходный код среди них: $(printf '%s\n' "$ever" | grep -cE "$SOURCE_EXT")"
      printf '%s\n' "$ever" | sed 's/^/     /'
      ;;
    fork)
      echo "   последние коммиты (автор отделяет правки форка от upstream):"
      g log -5 --format='     %h %cs %an — %s'
      ;;
  esac
done

echo
if [ "$FAILED" != 0 ]; then
  echo "✗ часть источников недоступна — сверить с docs/research/HAPP_SOURCE_AUDIT.md и обновить исследование с новой датой"
  exit 1
fi
echo "✓ опись сделана; сравнить с docs/research/HAPP_SOURCE_AUDIT.md, §2"
exit 0
