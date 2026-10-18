#!/data/data/com.termux/files/usr/bin/bash
# Сборка APK проектов автора одной кнопкой Termux:Widget — только для его
# устройств (D-24): выбор проекта → обновление его клона с GitHub (клона нет —
# git clone в ~/github/<репозиторий>) → скрипт сборки самого проекта из клона,
# внутри proot Alpine, где JDK, Android SDK и NDK. Как собирать, знает
# репозиторий проекта (D-31): здесь только сторона телефона.
#
#   apk-build             меню
#   apk-build <проект>    без меню: dpmetaapp, dppass, galdhold, galdhold-polygon,
#                         gravitydefied, andors-trail
#
# Установка — в ~/.shortcuts/, не в tasks/: виден ход сборки и итог.
#   cp ~/DPmetaApp/tools/termux/apk-build.sh ~/.shortcuts/apk-build
#   chmod +x ~/.shortcuts/apk-build
#
# Клон ищется в ~/github/<репозиторий>, затем в ~/<репозиторий> (так лежит
# ~/DPmetaApp). Обновление — только вперёд: текущая ветка перематывается к своей
# ветке на GitHub, если в клоне нет незакоммиченного; слияний и перезаписи нет.
#
# Скрипт проекта в proot читает окружение сборки — файл APK_BUILD_ENV, по
# умолчанию ~/dppass-build/termux-env.sh, на котором уже собирается DPpass:
# ANDROID_HOME, JAVA_HOME, обходы aapt2 и NDK под proot. DPpass собирается, как
# прежде, ~/dppass-build/build.sh с ключом dppass-dev (DPpass:D-35, DPpass:D-49).
# Лог — ~/apk-build/<проект>.log; итог — APK, которые появились в клоне за сборку.
#
# Файл — на POSIX sh: та же функция inner работает в busybox sh внутри Alpine.
# pipefail есть и в bash, и в busybox sh.
# shellcheck disable=SC3040
set -eu -o pipefail

OWNER=dpsmpx
GH="${APK_BUILD_GITHUB:-$HOME/github}"
BASE="$HOME/apk-build"
ENVF="${APK_BUILD_ENV:-$HOME/dppass-build/termux-env.sh}"
DISTRO="${APK_BUILD_DISTRO:-alpine}"
DPPASS_B="$HOME/dppass-build"
PROJECTS="dpmetaapp dppass galdhold galdhold-polygon gravitydefied andors-trail"

# Проект: репозиторий, строка меню, скрипт сборки в клоне и команда его запуска
# из корня клона (D-31). У DPpass своя сборка — ниже, отдельно.
project() {
  case "$1" in
    dpmetaapp)
      REPO=DPmetaApp; TITLE="DPmetaApp — пульт (dpai/android, release)"
      SCRIPT=tools/build-apk.sh; CMD="sh tools/build-apk.sh release" ;;
    dppass)
      REPO=DPmetaApp; TITLE="DPpass — подпись ключом dppass-dev"
      SCRIPT=; CMD= ;;
    galdhold)
      REPO=Galdhold; TITLE="Galdhold — игра (debug, код с -O2)"
      SCRIPT=build_gradle.sh; CMD="bash tools/deps.sh glm entt tomlplusplus && bash build_gradle.sh debug" ;;
    galdhold-polygon)
      REPO=Galdhold; TITLE="Galdhold — полигон движка"
      SCRIPT=build_gradle.sh; CMD="bash tools/deps.sh glm entt tomlplusplus && bash build_gradle.sh polygon" ;;
    gravitydefied)
      REPO=gravitydefied; TITLE="Gravity Defied — форк (debug)"
      SCRIPT=build-apk.sh; CMD="sh build-apk.sh" ;;
    andors-trail)
      REPO=andors-trail-load; TITLE="Andor's Trail — форк andors-trail-load (debug)"
      SCRIPT=build-apk.sh; CMD="sh build-apk.sh" ;;
    *) return 1 ;;
  esac
}

die() { echo "✗ $*"; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "в proot нет $1: proot-distro login $DISTRO -- $2"; }

# Внутри proot: $1 — окружение сборки, $2 — клон, $3 — команда скрипта проекта.
inner() {
  [ -f "$1" ] || die "нет окружения сборки $1 (ANDROID_HOME, JAVA_HOME, обходы aapt2 и NDK); другой файл — APK_BUILD_ENV"
  set +u   # файл окружения писался не под set -u
  # Путь — из APK_BUILD_ENV: заранее не известен.
  # shellcheck disable=SC1090
  . "$1"
  set -u
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  [ -n "$sdk" ] && [ -d "$sdk" ] || die "$1 не задал ANDROID_HOME — каталог Android SDK"
  export ANDROID_HOME="$sdk"
  [ -x "${JAVA_HOME:-/нет}/bin/java" ] || need java "apk add openjdk21-jdk"
  need "${3%% *}" "apk add ${3%% *}"
  cd "$2"
  exec sh -c "$3"
}

if [ "${1:-}" = --inner ]; then
  shift
  inner "$@"
fi

# ---- Дальше — сторона Termux ----

done_() {
  termux-wake-unlock 2>/dev/null || true
  stale_note
  [ -t 0 ] && { printf "\nEnter — закрыть "; read -r _; }
  return 0
}
trap done_ EXIT
fail() { echo "✗ $*"; exit 1; }
warn() { echo "⚠ $*"; }

# Ярлык — копия файла из DPmetaApp: напомнить, если там версия другая.
stale_note() {
  [ -n "${SELF:-}" ] && command -v cmp >/dev/null 2>&1 || return 0
  m=$(find_clone DPmetaApp) || return 0
  f="$m/tools/termux/apk-build.sh"
  [ -f "$f" ] && ! cmp -s "$f" "$SELF" || return 0
  warn "в DPmetaApp другая версия ярлыка — обновить: cp $f $SELF"
}

# Клон: ~/github/<репозиторий>, затем прежнее место ~/<репозиторий>.
find_clone() {
  for d in "$GH/$1" "$HOME/$1"; do
    [ -e "$d/.git" ] && { echo "$d"; return 0; }
  done
  return 1
}

# Только вперёд: текущая ветка — к своей ветке на GitHub, без своих правок,
# слияний и перезаписи. Иначе собирается то, что есть.
update_clone() {
  br=$(git branch --show-current)
  up=$(git rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null) || up=
  if [ -z "$br" ]; then
    warn "HEAD отделён от ветки — обновление пропущено, собираю текущее"
  elif [ -z "$up" ]; then
    warn "у ветки $br нет ветки на GitHub — обновление пропущено, собираю текущее"
  elif ! git fetch -q "${up%%/*}" "${up#*/}"; then
    warn "GitHub недоступен — собираю текущее"
  elif [ -n "$(git status --porcelain)" ]; then
    warn "есть незакоммиченное — обновление пропущено, собираю текущее"
  elif ! git merge -q --ff-only "$up"; then
    warn "$br разошлась с $up — не обновлено, собираю текущее"
  fi
}

[ -z "${CLAUDECODE:-}${CLAUDE_CODE_REMOTE:-}" ] || fail "ярлык — только для автора: собирает APK для его устройств и читает пароль ключа подписи"
command -v git >/dev/null || fail "нет git: pkg install git"
command -v proot-distro >/dev/null || fail "нет proot-distro: pkg install proot-distro"
SELF=$(realpath "$0")

ID="${1:-}"
if [ -z "$ID" ]; then
  [ -t 0 ] || fail "меню — только в терминале: ярлык кладётся в ~/.shortcuts/, не в tasks/"
  echo "Что собрать?"
  n=0
  for p in $PROJECTS; do
    n=$((n + 1))
    project "$p"
    d=$(find_clone "$REPO") || d="нет — клонирую в $GH/$REPO"
    printf "  %d) %s\n     %s\n" "$n" "$TITLE" "$d"
  done
  printf "Номер (Enter — выход): "
  read -r k
  [ -n "$k" ] || exit 0
  n=0
  for p in $PROJECTS; do
    n=$((n + 1))
    if [ "$k" = "$n" ]; then ID=$p; fi
  done
  [ -n "$ID" ] || fail "нет пункта $k"
fi
project "$ID" || fail "неизвестный проект $ID (есть: $PROJECTS)"

if [ "$ID" = dppass ]; then
  [ -f "$DPPASS_B/build.sh" ] && [ -f "$DPPASS_B/termux-env.sh" ] || fail "нет $DPPASS_B/build.sh или termux-env.sh"
  [ -f "$HOME/dppass-keys/dppass-dev.p12" ] || fail "нет ключа ~/dppass-keys/dppass-dev.p12"
else
  [ -f "$ENVF" ] || fail "нет окружения сборки $ENVF (ANDROID_HOME, JAVA_HOME, обходы aapt2 и NDK под proot); другой файл — APK_BUILD_ENV"
fi

if DIR=$(find_clone "$REPO"); then
  cd "$DIR"
  update_clone
else
  mkdir -p "$GH"
  echo "Клона $REPO нет — клонирую в $GH/$REPO"
  git clone "https://github.com/$OWNER/$REPO.git" "$GH/$REPO" \
    || fail "не клонировал $OWNER/$REPO: нет сети или доступа к закрытому репозиторию (gh auth login, затем gh auth setup-git)"
  DIR="$GH/$REPO"
  cd "$DIR"
fi
echo "$TITLE: $DIR, ветка $(git branch --show-current), коммит $(git rev-parse --short=7 HEAD)"

if [ -n "$SCRIPT" ] && [ ! -f "$DIR/$SCRIPT" ]; then
  fail "в клоне нет $SCRIPT — скрипта сборки $REPO (D-31): его PR в $OWNER/$REPO ещё не влит, или клон на другой ветке"
fi
if [ "$ID" = dppass ]; then
  # build.sh собирает ~/DPmetaApp (прежний ярлык DPpass) — другой клон он не увидит.
  # «~» — в тексте сообщения, раскрывать его не нужно.
  # shellcheck disable=SC2088
  [ "$DIR" = "$HOME/DPmetaApp" ] || fail "~/dppass-build/build.sh собирает ~/DPmetaApp, а клон — $DIR"
  [ -z "$(git status --porcelain -- dppass)" ] || warn "в dppass/ есть незакоммиченное — имена APK будут с -dirty"
fi

mkdir -p "$BASE"
LOG="$BASE/$ID.log"
MARK="$BASE/$ID.start"
: > "$MARK"
termux-wake-lock   # сборка идёт минуты, а с первой загрузкой Gradle — дольше: телефон не должен засыпать
start=$(date +%s)
status=0
if [ "$ID" = dppass ]; then
  proot-distro login "$DISTRO" -- sh "$DPPASS_B/build.sh" 2>&1 | tee "$LOG" || status=$?
else
  proot-distro login "$DISTRO" -- sh "$SELF" --inner "$ENVF" "$DIR" "$CMD" 2>&1 | tee "$LOG" || status=$?
fi
min=$(( ($(date +%s) - start) / 60 ))
[ -f "$HOME/bin/beep-attention.sh" ] && { sh "$HOME/bin/beep-attention.sh" >/dev/null 2>&1 || true; }

[ "$status" = 0 ] || fail "сборка не прошла (код $status, $min мин); лог — $LOG"
# Итог — APK в каталогах build/ клона; промежуточные AGP не в счёт. Gradle не
# пересобирает то, что не менялось (UP-TO-DATE), — такие APK помечены «прежний».
apks=$(find "$DIR" -path "$DIR/.git" -prune -o -name '*.apk' -path '*/build/*' ! -path '*/intermediates/*' -print 2>/dev/null | sort) || apks=
[ -n "$apks" ] || fail "сборка прошла, но APK в $DIR нет; лог — $LOG"
echo
echo "✓ Готово за $min мин"
c="$DIR/dppass/build/release-apk/CERTIFICATE.txt"
if [ "$ID" = dppass ] && [ -f "$c" ]; then
  sed -n 's/^\(package\|label\): /  \1: /p' "$c"
fi
echo "$apks" | while read -r a; do
  if [ "$a" -nt "$MARK" ]; then w="новый"; else w="прежний — не менялся с прошлой сборки"; fi
  echo "  $a"
  echo "    $w, $(du -h "$a" | cut -f1), sha256 $(sha256sum "$a" | cut -c1-16)…"
done
echo "Установить: termux-open <файл>.apk (этому телефону — arm64-v8a)"
