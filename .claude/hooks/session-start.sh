#!/bin/bash
# ============================================================
# Хук начала сессии Claude Code (CLAUDE.md, «Не терять работу
# между сессиями»; docs/PROCESS.md, раздел 1):
#
#   * чем богато окружение: python3 и git — для tools/check/run.sh;
#     JDK и Android SDK — для сборки с этапа 1 (без SDK собирается
#     только ядро, D-03);
#   * в веб-сессии — Android SDK для приложения (ответ автора 25 октября
#     2026): платформы, build-tools, NDK и CMake из сборки dpai/android,
#     путь — в ANDROID_HOME и local.properties; зеркало Maven Central у
#     Google для Gradle (прокси веб-сессии отвечает 404 и 429);
#   * ветки с работой, не влитой в main, — свежие первыми: с них
#     продолжают, а не делают заново; ветки сборок (`*_debug`, прежние
#     `apk-*` и `linux-debug`, D-33) — не работа, их нет;
#   * зависимости проверок SocialMediaImage (D-43) — в веб-сессии, если
#     их нет.
#
# Кроме SDK, зеркала и этих зависимостей ничего не ставит и в репозитории
# не пишет (кроме local.properties, он в .gitignore); любая ошибка — мимо:
# хук не должен мешать начать сессию.
# ============================================================
PROJ="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"

# ---- Android SDK и зеркало Maven Central — только в веб-сессии ----
# Повторный запуск ничего не ставит заново. Пакеты SDK сверяет sdkmanager,
# архив command-line tools — SHA-256 ниже (тот же, что в хуке DPpass).
if [ "${CLAUDE_CODE_REMOTE:-}" = "true" ]; then
    {
        SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
        GRADLE_APP="$PROJ/dpai/android/build.gradle.kts"
        COMPILE_SDK="$(sed -n 's/^ *compileSdk *= *\([0-9]*\).*/\1/p' "$GRADLE_APP" | head -1)"
        NDK="$(sed -n 's/^ *ndkVersion *= *"\(.*\)".*/\1/p' "$GRADLE_APP" | head -1)"
        CMAKE="$(sed -n 's/^ *version *= *"\(3\.[0-9.]*\)".*/\1/p' "$GRADLE_APP" | head -1)"
        # build-tools — версии compileSdk (AGP берёт их); android-30 — minSdk для check_android_api.py (D-28).
        CLT_ZIP="commandlinetools-linux-13114758_latest.zip"
        CLT_SHA256="7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee"
        SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
        if [ ! -x "$SDKMANAGER" ]; then
            tmp="$(mktemp -d)"
            curl -sSfL -o "$tmp/clt.zip" "https://dl.google.com/android/repository/$CLT_ZIP" &&
                echo "$CLT_SHA256  $tmp/clt.zip" | sha256sum -c --quiet - &&
                python3 -I -c "import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$tmp/clt.zip" "$tmp/x" &&
                mkdir -p "$SDK/cmdline-tools" && rm -rf "$SDK/cmdline-tools/latest" &&
                mv "$tmp/x/cmdline-tools" "$SDK/cmdline-tools/latest" && chmod +x "$SDK/cmdline-tools/latest/bin/"*
            rm -rf "$tmp"
        fi
        want=()
        [ -d "$SDK/platforms/android-$COMPILE_SDK.0" ] || [ -d "$SDK/platforms/android-$COMPILE_SDK" ] || want+=("platforms;android-$COMPILE_SDK.0")
        [ -d "$SDK/platforms/android-30" ] || want+=("platforms;android-30")
        [ -d "$SDK/build-tools/$COMPILE_SDK.0.0" ] || want+=("build-tools;$COMPILE_SDK.0.0")
        [ -d "$SDK/platform-tools" ] || want+=("platform-tools")
        [ -z "$NDK" ] || [ -f "$SDK/ndk/$NDK/source.properties" ] || want+=("ndk;$NDK")
        [ -z "$CMAKE" ] || [ -d "$SDK/cmake/$CMAKE" ] || want+=("cmake;$CMAKE")
        if [ -x "$SDKMANAGER" ] && [ "${#want[@]}" -gt 0 ]; then
            echo "Android SDK: ставлю ${want[*]} в $SDK (несколько минут)"
            yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
            "$SDKMANAGER" --sdk_root="$SDK" "${want[@]}" >/dev/null || echo "  ! sdkmanager не поставил: ${want[*]}"
        fi
        if [ -x "$SDKMANAGER" ]; then
            [ -n "${CLAUDE_ENV_FILE:-}" ] && echo "export ANDROID_HOME=\"$SDK\"" >> "$CLAUDE_ENV_FILE"
            export ANDROID_HOME="$SDK"
            for f in "$PROJ/local.properties" "$PROJ/dpai/local.properties"; do
                grep -qs '^sdk\.dir=' "$f" || echo "sdk.dir=$SDK" >> "$f"
            done
        else
            echo "  ! Android SDK не поставлен: нет command-line tools (сеть или сумма)"
        fi
        # Зеркало Maven Central у Google — для сборок этого репозитория: прокси веб-сессии отвечает
        # 404 и 429 на холодную сборку (так же DPpass:D-50). Каталоги вне репозитория не затрагиваются.
        INIT_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/init.d"
        mkdir -p "$INIT_DIR"
        cat > "$INIT_DIR/dpmetaapp-maven-mirror.gradle" <<GRADLE
// Пишет .claude/hooks/session-start.sh DPmetaApp. Руками не править.
def mirror = "https://maven-central.storage-download.googleapis.com/maven2/"
def root = new File("$PROJ").canonicalPath
beforeSettings { settings ->
    def dir = settings.settingsDir.canonicalPath
    if (dir != root && !dir.startsWith(root + File.separator)) return
    settings.pluginManagement.repositories {
        maven { url mirror }
        google()
        gradlePluginPortal()
    }
    settings.dependencyResolutionManagement.repositories {
        maven { url mirror }
    }
}
GRADLE
    } || true
fi

{
    echo "== Начало сессии: DPmetaApp =="
    echo "Правила — CLAUDE.md; этап и шаги — ROADMAP/ROADMAP.md; хвосты — TODO.md."
    echo "Подсистема «AI» и ядро — dpai/ (D-28): правила dpai/CLAUDE.md, хвосты dpai/TODO.md,"
    echo "  проверки — cd dpai && tools/verify/run.sh static|affected|full."
    echo "Подсистема DPpass — dppass/ (D-30): правила dppass/CLAUDE.md, хвосты dppass/TODO.md,"
    echo "  проверки — cd dppass && tools/hostcheck/run.sh --quick|--full, итог для PR — run.sh report."
    echo "Подсистема SocialMediaImage — socialmediaimage/ (D-43): правила socialmediaimage/CLAUDE.md, начало —"
    echo "  его docs/session/HANDOFF.md; проверки — cd socialmediaimage && tools/check.sh --quick|--affected|(полный)."
    echo "Подсистема DPvisualisation — dpvisualisation/ (D-43): правила dpvisualisation/CLAUDE.md;"
    echo "  проверки — cd dpvisualisation && tools/check/run.sh [full] (Python 3.11+; full — Node.js и Chromium)."
    # Зависимости проверок SocialMediaImage (PyYAML, jsonschema и др.) — в веб-сессии, если их нет.
    if ! python3 -c "import yaml, jsonschema" 2>/dev/null; then
        if [ "${CLAUDE_CODE_REMOTE:-}" = "true" ]; then
            pip install -q -r "$PROJ/socialmediaimage/tools/requirements.txt" >/dev/null 2>&1 \
                || echo "  ! зависимости SocialMediaImage не поставились: pip install -r socialmediaimage/tools/requirements.txt"
        else
            echo "  ! нужны PyYAML и jsonschema: pip install -r socialmediaimage/tools/requirements.txt"
        fi
    fi
    for tool in python3 git java; do
        if command -v "$tool" >/dev/null 2>&1; then
            echo "  $tool: есть"
            continue
        fi
        case "$tool" in
            java) why="сборка ядра и подсистемы dpai/ недоступна" ;;
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
                 refs/remotes/origin 2>/dev/null | grep -v -e '^origin$' -e '^origin/HEAD$' -e '^origin/main$' \
                 -e '^origin/.*_debug$' -e '^origin/apk-' -e '^origin/linux-debug$' | head -8)
    [ "$found" -eq 1 ] || echo "  нет"
    echo "  Перед работой сверить с ними задачу: git log origin/main..origin/<ветка>."
    echo "Открытые PR — через GitHub (MCP); незаконченное — в их описании и TODO.md."
    echo "Текущая ветка: $(git -C "$PROJ" branch --show-current 2>/dev/null)"
} || true
exit 0
