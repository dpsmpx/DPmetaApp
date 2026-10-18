#!/data/data/com.termux/files/usr/bin/bash
# Сборка APK проектов автора одной кнопкой Termux:Widget — только для его
# устройств (D-24): выбор проекта → обновление его клона с GitHub (клона нет —
# git clone в ~/github/<репозиторий>) → сборка рецептом проекта внутри proot
# Alpine, где JDK, Android SDK и NDK.
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
# Gradle в proot читает окружение сборки — файл APK_BUILD_ENV, по умолчанию
# ~/dppass-build/termux-env.sh, на котором уже собирается DPpass: ANDROID_HOME,
# JAVA_HOME, обходы aapt2 и NDK под proot. DPpass собирается, как прежде,
# ~/dppass-build/build.sh с ключом dppass-dev (DPpass:D-35, DPpass:D-49); остальные —
# отладочным ключом из proot, кроме ключа выпуска DPmetaApp (свойства dpai.release.*
# в ~/.gradle/gradle.properties внутри proot). Лог — ~/apk-build/<проект>.log.
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

# Проект: репозиторий, строка меню, каталог APK в клоне.
project() {
  case "$1" in
    dpmetaapp)
      REPO=DPmetaApp; TITLE="DPmetaApp — пульт (dpai/android, release)"
      OUT=dpai/android/build/outputs/apk/release ;;
    dppass)
      REPO=DPmetaApp; TITLE="DPpass — подпись ключом dppass-dev"
      OUT=dppass/build/release-apk ;;
    galdhold)
      REPO=Galdhold; TITLE="Galdhold — игра (debug, код с -O2)"
      OUT=app/build/outputs/apk/debug ;;
    galdhold-polygon)
      REPO=Galdhold; TITLE="Galdhold — полигон движка"
      OUT=polygon/android/build/outputs/apk/debug ;;
    gravitydefied)
      REPO=gravitydefied; TITLE="Gravity Defied — форк (debug)"
      OUT=app/build/outputs/apk/debug ;;
    andors-trail)
      REPO=andors-trail-load; TITLE="Andor's Trail — форк andors-trail-load (debug)"
      OUT=AndorsTrail/app/build/outputs/apk/debug ;;
    *) return 1 ;;
  esac
}

die() { echo "✗ $*"; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "в proot нет $1: proot-distro login $DISTRO -- $2"; }

# Скачать URL в файл: curl, а без него — wget из busybox.
dl() {
  if command -v curl >/dev/null 2>&1; then curl -fsSL -o "$2" "$1"; else wget -q -O "$2" "$1"; fi
}

# Gradle для проекта без gradlew — та версия, что в его CI: дистрибутив с
# services.gradle.org, сверенный с записанной суммой (та, что там же опубликована);
# качается один раз. $1 — ~/apk-build. Печатает путь к bin/gradle.
GRADLE_V=9.6.0
GRADLE_SHA256=bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01
gradle_dist() {
  gd="$1/gradle"
  z="$gd/gradle-$GRADLE_V-bin.zip"
  if [ ! -x "$gd/gradle-$GRADLE_V/bin/gradle" ]; then
    mkdir -p "$gd"
    echo "Скачиваю Gradle $GRADLE_V…" >&2
    dl "https://services.gradle.org/distributions/gradle-$GRADLE_V-bin.zip" "$z" || { echo "✗ не скачал Gradle $GRADLE_V" >&2; return 1; }
    [ "$(sha256sum "$z" | cut -d' ' -f1)" = "$GRADLE_SHA256" ] || { rm -f "$z"; echo "✗ Gradle $GRADLE_V: SHA-256 не сошлась — файл удалён" >&2; return 1; }
    unzip -q -o "$z" -d "$gd"
    rm -f "$z"
  fi
  echo "$gd/gradle-$GRADLE_V/bin/gradle"
}

is_musl() {
  for f in /lib/ld-musl-*; do [ -e "$f" ] && return 0; done
  return 1
}

# JetBrains JDK 21 для демона Gradle под musl. Форк andors-trail требует для демона
# именно его (gradle/gradle-daemon-jvm.properties), а сам Gradle качает сборку под
# glibc — в Alpine она не запускается. Полный JDK (jbrsdk): runtime jbr Gradle не
# принимает. С CDN JetBrains, сверенный с записанной суммой SHA-512 (её файл
# .checksum там же); качается один раз, ~240 МБ. $1 — ~/apk-build. Печатает каталог.
JBR_V=21.0.11 JBR_B=b1163.116
jbr_musl() {
  case "$(uname -m)" in
    aarch64) a=aarch64; sum=b295ac7bff09f964dcea3e63fb0f2e34a1f3f972f3ba0fc0b2b20512dc18faae8c83d194aa293618b3a2487d167dec55e432ab5a2af19565f4979624fe34de5d ;;
    x86_64) a=x64; sum=0020a0ddca442e4078d8e3ffcc0a6d71e032f33e597196dc6d779caa4b5a64576d6f86428edfe5433fd72567f88e39da4fc1bc1d0114f15788b7170be413a35a ;;
    *) echo "✗ JetBrains JDK под musl для $(uname -m) не записан" >&2; return 1 ;;
  esac
  n="jbrsdk-$JBR_V-linux-musl-$a-$JBR_B"
  jd="$1/jdk"
  if [ ! -x "$jd/$n/bin/java" ]; then
    mkdir -p "$jd"
    echo "Скачиваю JetBrains JDK $JBR_V (musl, $a, ~240 МБ) для демона Gradle…" >&2
    dl "https://cache-redirector.jetbrains.com/intellij-jbr/$n.tar.gz" "$jd/$n.tar.gz" || { echo "✗ не скачал $n" >&2; return 1; }
    [ "$(sha512sum "$jd/$n.tar.gz" | cut -d' ' -f1)" = "$sum" ] || { rm -f "$jd/$n.tar.gz"; echo "✗ $n: SHA-512 не сошлась — файл удалён" >&2; return 1; }
    tar xzf "$jd/$n.tar.gz" -C "$jd"
    rm -f "$jd/$n.tar.gz"
  fi
  echo "$jd/$n"
}

# Сборка внутри proot: $1 — проект, $2 — клон, $3 — окружение сборки, $4 — ~/apk-build.
inner() {
  project "$1" || die "неизвестный проект $1"
  [ -f "$3" ] || die "нет окружения сборки $3 (ANDROID_HOME, JAVA_HOME, обходы aapt2 и NDK); другой файл — APK_BUILD_ENV"
  set +u   # файл окружения писался не под set -u
  # Путь — из APK_BUILD_ENV: заранее не известен.
  # shellcheck disable=SC1090
  . "$3"
  set -u
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  [ -n "$sdk" ] && [ -d "$sdk" ] || die "$3 не задал ANDROID_HOME — каталог Android SDK"
  export ANDROID_HOME="$sdk"
  [ -x "${JAVA_HOME:-/нет}/bin/java" ] || need java "apk add openjdk21-jdk"
  cd "$2"
  case "$1" in
    dpmetaapp)
      # compileSdk 37 — в SDK каталог platforms/android-37.0 (или android-37 у старых sdkmanager).
      [ -d "$sdk/platforms/android-37.0" ] || [ -d "$sdk/platforms/android-37" ] \
        || echo "⚠ в SDK нет платформы 37 (compileSdk DPmetaApp) — Gradle попробует поставить её сам"
      cd dpai
      sh ./gradlew --console=plain :android:assembleRelease ;;
    galdhold|galdhold-polygon)
      need bash "apk add bash"
      need git "apk add git"
      need python3 "apk add python3"
      need glslc "apk add shaderc"
      # CMake — версия из app/build.gradle; свой путь к ней (cmake.dir в local.properties) не сверяется.
      cm="$sdk/cmake/3.22.1/bin/cmake"
      grep -q '^cmake\.dir=' local.properties 2>/dev/null || "$cm" --version >/dev/null 2>&1 \
        || die "CMake 3.22.1 из SDK не запускается ($cm): нет — sdkmanager \"cmake;3.22.1\"; есть, но под x86_64 — apk add cmake samurai и ссылки bin/cmake, bin/ninja там на /usr/bin/cmake, /usr/bin/ninja"
      # Для APK нужны три заголовочные зависимости (app/src/main/cpp/CMakeLists.txt);
      # deps.sh качает их по коммитам и сверяет уже скачанное.
      bash tools/deps.sh glm entt tomlplusplus
      if [ "$1" = galdhold ]; then bash ./build_gradle.sh debug; else bash ./build_gradle.sh polygon; fi ;;
    gravitydefied)
      # gradlew в репозитории нет; его CI собирает Gradle 9.6.0 (.github/workflows/android.yml).
      g=$(gradle_dist "$4")
      "$g" --no-daemon --console=plain :app:assembleDebug ;;
    andors-trail)
      cd AndorsTrail
      if is_musl; then
        j=$(jbr_musl "$4")
        sh ./gradlew --console=plain "-Dorg.gradle.java.installations.paths=$j" :app:assembleDebug
      else
        sh ./gradlew --console=plain :app:assembleDebug
      fi ;;
  esac
}

if [ "${1:-}" = --inner ]; then
  shift
  inner "$@"
  exit 0
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
  proot-distro login "$DISTRO" -- sh "$SELF" --inner "$ID" "$DIR" "$ENVF" "$BASE" 2>&1 | tee "$LOG" || status=$?
fi
min=$(( ($(date +%s) - start) / 60 ))
[ -f "$HOME/bin/beep-attention.sh" ] && { sh "$HOME/bin/beep-attention.sh" >/dev/null 2>&1 || true; }

[ "$status" = 0 ] || fail "сборка не прошла (код $status, $min мин); лог — $LOG"
apks=$(find "$DIR/$OUT" -name '*.apk' -newer "$MARK" 2>/dev/null | sort) || apks=
[ -n "$apks" ] || fail "сборка прошла, но нового APK в $DIR/$OUT нет; лог — $LOG"
echo
echo "✓ Готово за $min мин: $DIR/$OUT"
if [ "$ID" = dppass ] && [ -f "$DIR/$OUT/CERTIFICATE.txt" ]; then
  sed -n 's/^\(package\|label\): /  \1: /p' "$DIR/$OUT/CERTIFICATE.txt"
fi
echo "$apks" | while read -r a; do
  echo "  $(basename "$a")  $(du -h "$a" | cut -f1)  sha256 $(sha256sum "$a" | cut -c1-16)…"
done
echo "Установить: termux-open <файл>.apk (этому телефону — arm64-v8a)"
