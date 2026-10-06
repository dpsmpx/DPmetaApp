#!/usr/bin/env bash
# Шаги проверки DPpass, которые не одна программа: их зовёт DP Verify командами
# профиля tools/dpverify.toml (D-50). Перенесены из tools/hostcheck/run.sh без
# изменения поведения; дым CLI — проверка smoke.cli DP Verify.
#
#   tools/hostcheck/steps.sh build         сборка и тесты ядра (с Android SDK — без :app:build: APK — шаг Android)
#   tools/hostcheck/steps.sh xray-bridge   мост Xray: библиотека для JVM хоста и тест JNI в этой JVM (D-25)
#
# Код возврата: 0 — шаг прошёл; 1 — нет; 2 — неверный вызов.
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 2

PY="${PYTHON:-python3}"
GRADLE=(./gradlew --console=plain --warning-mode=summary)

sdk_dir() { # тот же признак, что в settings.gradle.kts; печатает путь
  local dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -n "$dir" ] && [ -d "$dir" ]; then echo "$dir"; return 0; fi
  if [ -f local.properties ]; then
    dir="$(sed -n 's/^sdk\.dir=//p' local.properties | head -1)"
    [ -n "$dir" ] && [ -d "$dir" ] && { echo "$dir"; return 0; }
  fi
  return 1
}
android_sdk() { sdk_dir >/dev/null; }

xray_bridge() {
  "$PY" tools/xray/native.py host || return 1
  local lib=build/xray/host/libdppassxray.so
  "${GRADLE[@]}" :tests:integration:xrayJniTest -Pdppass.xray.lib="$lib" || return 1
  "$PY" - <<'PYEOF' || return 1
import glob, xml.etree.ElementTree as ET
n = f = 0
for p in glob.glob("tests/integration/build/test-results/xrayJniTest/*.xml"):
    r = ET.parse(p).getroot()
    n += int(r.get("tests")); f += int(r.get("failures")) + int(r.get("errors"))
if n == 0 or f:
    raise SystemExit(f"✗ XrayJniTest: тестов {n}, отказов {f}")
print(f"✓ XrayJniTest: {n} тестов моста через JNI в JVM хоста")
PYEOF
}

build() {
  local tasks=(build :cli:installDist)
  android_sdk && tasks+=(-x :app:build)
  "${GRADLE[@]}" "${tasks[@]}"
}

case "${1:-}" in
  build) build ;;
  xray-bridge) xray_bridge ;;
  *) echo "steps.sh: шаг build или xray-bridge, а не «${1:-}»"; exit 2 ;;
esac
