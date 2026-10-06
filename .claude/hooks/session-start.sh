#!/bin/bash
# Хук начала веб-сессии Claude Code (шаблон process-kit DP Verify, D-50).
#
#   * проектная часть — JDK 21 и Android SDK с NDK: без них шаги Android
#     и мост Xray в tools/hostcheck/run.sh --full падают, пропуск не
#     разрешается (docs/PROCESS.md, «Окружение»);
#   * общая — dpv doctor (чего не хватает) и ветки с невлитой работой.
#
# Только в веб-сессии; повторный запуск ничего не ставит заново.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
    exit 0
fi

PROJ="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
cd "$PROJ"

# ---- Проектная часть ----

# JDK 21 — инструменты сборки (jvmToolchain(21)); в контейнере он уже есть.
java -version 2>&1 | grep -q 'version "21' || echo "session-start: нет JDK 21 — сборка не пройдёт (docs/PROCESS.md, «Окружение»)"

# Android SDK и NDK. Версии — из каталога (compileSdk, NDK); build-tools —
# те, что берёт AGP для compileSdk 36 (check_apk.py берёт самые новые).
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
COMPILE_SDK="$(sed -n 's/^android-compileSdk *= *"\(.*\)"/\1/p' gradle/libs.versions.toml)"
NDK="$(sed -n 's/^android-ndk *= *"\(.*\)"/\1/p' gradle/libs.versions.toml)"
BUILD_TOOLS="36.0.0"
# command-line tools 13114758: SHA-1 — как в dl.google.com/android/repository/repository2-3.xml,
# SHA-256 — записан 6 октября 2026 по тому же архиву.
CLT_ZIP="commandlinetools-linux-13114758_latest.zip"
CLT_SHA256="7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$SDKMANAGER" ]; then
    tmp="$(mktemp -d)"
    curl -sSfL -o "$tmp/clt.zip" "https://dl.google.com/android/repository/$CLT_ZIP"
    echo "$CLT_SHA256  $tmp/clt.zip" | sha256sum -c --quiet -
    python3 -c "import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$tmp/clt.zip" "$tmp/x"
    mkdir -p "$SDK/cmdline-tools"
    rm -rf "$SDK/cmdline-tools/latest"
    mv "$tmp/x/cmdline-tools" "$SDK/cmdline-tools/latest"
    chmod +x "$SDK/cmdline-tools/latest/bin/"*
    rm -rf "$tmp"
fi

want=()
[ -d "$SDK/platforms/android-$COMPILE_SDK" ] || want+=("platforms;android-$COMPILE_SDK")
[ -d "$SDK/build-tools/$BUILD_TOOLS" ] || want+=("build-tools;$BUILD_TOOLS")
[ -d "$SDK/platform-tools" ] || want+=("platform-tools")
[ -f "$SDK/ndk/$NDK/source.properties" ] || want+=("ndk;$NDK")
if [ "${#want[@]}" -gt 0 ]; then
    yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
    "$SDKMANAGER" --sdk_root="$SDK" "${want[@]}" >/dev/null
fi

# Путь к SDK — для сессии и для Gradle (local.properties не в репозитории).
[ -n "${CLAUDE_ENV_FILE:-}" ] && echo "export ANDROID_HOME=\"$SDK\"" >> "$CLAUDE_ENV_FILE"
grep -qs '^sdk\.dir=' local.properties || echo "sdk.dir=$SDK" >> local.properties

# ---- Окружение для DP Verify ----
python3 "$PROJ/tools/dpv/dpv" doctor || true

# ---- Невлитая работа прошлых сессий ----
#
# Сессию обрывает лимит, и сделанное живёт только в запушенных ветках.
# Здесь — ветки с коммитами, которых нет в основной ветке, свежие первыми:
# с них продолжают, а не делают заново. Блок — из хуков VoxelRPG и DPlang.
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
