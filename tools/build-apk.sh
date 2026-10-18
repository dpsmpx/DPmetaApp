#!/bin/sh
# APK DPmetaApp — оболочка dpai/android, первая форма приложения (D-28), — на
# любом Linux с JDK 21 и Android SDK (D-31): телефон автора (proot, ярлык
# tools/termux/apk-build.sh), компьютер, облачная сессия.
#
#   tools/build-apk.sh [release|debug]     по умолчанию release
#
# release подписан ключом выпуска из свойств dpai.release.* в
# ~/.gradle/gradle.properties (dpai/android/build.gradle.kts), без них —
# отладочным ключом: для своих устройств, не для раздачи (D-24). Android SDK —
# ANDROID_HOME или sdk.dir в dpai/local.properties: без него модуль :android не
# подключается (dpai/settings.gradle.kts). APK DPpass собирается отдельно (D-30).
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:-release}"
case "$MODE" in
  release) TASK=assembleRelease ;;
  debug) TASK=assembleDebug ;;
  *) echo "✗ вариант $MODE: есть release и debug"; exit 2 ;;
esac
if [ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ] && ! grep -q '^sdk\.dir=' "$ROOT/dpai/local.properties" 2>/dev/null; then
  echo "✗ нет Android SDK: ANDROID_HOME или sdk.dir в dpai/local.properties"
  exit 2
fi

cd "$ROOT/dpai"
sh ./gradlew --console=plain ":android:$TASK"
ls "$ROOT/dpai/android/build/outputs/apk/$MODE/"*.apk
