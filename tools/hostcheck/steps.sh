#!/usr/bin/env bash
# Шаги проверки DPpass, которые не одна программа: их зовёт DP Verify командами
# профиля tools/dpverify.toml (D-39). Перенесены из tools/hostcheck/run.sh без
# изменения поведения.
#
#   tools/hostcheck/steps.sh build         сборка и тесты ядра (с Android SDK — без :app:build: APK — шаг Android)
#   tools/hostcheck/steps.sh cli-smoke     15 сценариев CLI: коды выхода, вывод, секреты не печатаются
#   tools/hostcheck/steps.sh xray-bridge   мост Xray: библиотека для JVM хоста и тест JNI в этой JVM (D-25)
#
# Код возврата: 0 — шаг прошёл; 1 — нет; 2 — неверный вызов.
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 2

PY="${PYTHON:-python3}"
GRADLE=(./gradlew --console=plain --warning-mode=summary)
CLI="cli/build/install/dppass-cli/bin/dppass-cli"

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

cli_smoke() {
  local fails=0
  expect() { # expect <код> <должно быть|-> <не должно быть|-> <аргументы…>
    local want="$1" must="$2" mustnot="$3"; shift 3
    local out code
    out="$("$CLI" "$@" 2>&1)"; code=$?
    if [ "$code" != "$want" ]; then
      echo "✗ dppass-cli $*: код $code, ожидался $want"; echo "$out" | tail -20; fails=1; return
    fi
    if [ "$must" != - ] && ! grep -qF -- "$must" <<<"$out"; then
      echo "✗ dppass-cli $*: нет «$must» в выводе"; echo "$out" | tail -20; fails=1; return
    fi
    if [ "$mustnot" != - ] && grep -qF -- "$mustnot" <<<"$out"; then
      echo "✗ dppass-cli $*: в выводе есть «$mustnot»"; fails=1; return
    fi
  }
  local d=tests/data
  local uuid=00000000-0000-4000-8000-000000000001
  expect 0 "ACTIVATED" - check $d/configs/valid-multi.json
  expect 1 "CAPABILITY_TRANSPORT_UNSUPPORTED" - check $d/configs/limited-backend-xhttp.json --backend mock-limited
  expect 1 "INSECURE_TLS_REJECTED" - check $d/configs/invalid-allow-insecure.json
  expect 0 "DIRECT (ru-direct)" "примечание" route $d/configs/valid-multi.json --domain www.example.ru
  expect 0 "geoip/geosite не проверены" - route $d/configs/valid-multi.json --ip 198.51.100.7
  expect 0 "restriction=ALLOWLIST_LIKE" - classify --condition ALLOWLIST_LIKE
  expect 0 "1. xhttp-cdn [HTTP_CDN]" - plan $d/configs/valid-multi.json --condition TLS_DEGRADED
  expect 0 "CONNECTED via domestic-relay" - connect $d/configs/valid-multi.json --condition ALLOWLIST_LIKE
  expect 1 "NETWORK_OFFLINE" - connect $d/configs/valid-multi.json --condition OFFLINE
  expect 0 "READY revision=1 profiles=3" "evil.example.com" subscription $d/subscriptions/links-with-directives.txt \
    --headers $d/subscriptions/links-with-directives.headers.json
  expect 0 "SERVER_NAME_INVALID at profiles[1].security.server_name" "test-password" \
    subscription $d/subscriptions/links-invalid-names.txt
  expect 0 '"id": "<redacted>"' "$uuid" export $d/configs/valid-minimal.json
  expect 0 "active_profile: reality-1" "$uuid" report $d/configs/valid-minimal.json
  expect 0 "route decisions:" - bench --rules 200
  expect 2 - - no-such-command
  [ "$fails" = 0 ] && echo "✓ cli: 15 сценариев — коды выхода и вывод как ожидалось, секреты не печатаются"
  return "$fails"
}

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
  cli-smoke) cli_smoke ;;
  xray-bridge) xray_bridge ;;
  *) echo "steps.sh: шаг build, cli-smoke или xray-bridge, а не «${1:-}»"; exit 2 ;;
esac
