#!/usr/bin/env bash
# hostcheck — все проверки DPpass одной командой (docs/PROCESS.md, «Проверки»).
#
#   tools/hostcheck/run.sh            всё, что можно на этой машине; пропуск — громко, но не ошибка
#   tools/hostcheck/run.sh --quick    только быстрые проверки (без Gradle)
#   tools/hostcheck/run.sh --full     перед слиянием (CI нет, D-23): каждый пропуск (нет Android SDK) — ошибка
#
# Код возврата: 0 — всё прошло (в обычном режиме — кроме явно названных
# пропусков); 1 — хотя бы один шаг упал; 2 — неверный вызов.
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 2

MODE=default
case "${1:-}" in
  "") ;;
  --quick) MODE=quick ;;
  --full) MODE=full ;;
  -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
  *) echo "неизвестный параметр: $1 (есть --quick, --full)"; exit 2 ;;
esac

PY="${PYTHON:-python3}"
GRADLE=(./gradlew --console=plain --warning-mode=summary)
CLI="cli/build/install/dppass-cli/bin/dppass-cli"
LOG_DIR="build/hostcheck"
mkdir -p "$LOG_DIR"

declare -a RESULTS=()
FAILED=0
SKIPPED=0

step() { # step <имя> <команда…>: вывод — в журнал, при отказе — на экран
  local name="$1"; shift
  local log="$LOG_DIR/$(echo "$name" | tr ' /' '__').log"
  printf '… %s\n' "$name"
  if "$@" >"$log" 2>&1; then
    RESULTS+=("✓ $name")
    grep -E '^✓' "$log" | sed 's/^/    /' || true
  else
    RESULTS+=("✗ $name (журнал: $log)")
    FAILED=1
    sed 's/^/    /' "$log" | tail -60
  fi
}

skip() { # skip <имя> <причина>
  if [ "$MODE" = full ]; then
    RESULTS+=("✗ $1 — пропуск недопустим в полном режиме: $2")
    FAILED=1
  else
    RESULTS+=("○ $1 — ПРОПУЩЕНО: $2")
    SKIPPED=1
  fi
}

android_sdk() { # тот же признак, что в settings.gradle.kts
  local dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -n "$dir" ] && [ -d "$dir" ]; then return 0; fi
  if [ -f local.properties ]; then
    dir="$(sed -n 's/^sdk\.dir=//p' local.properties | head -1)"
    [ -n "$dir" ] && [ -d "$dir" ] && return 0
  fi
  return 1
}

# ---- 1. Быстрые проверки (Python, без сборки) ----
step "модули" "$PY" tools/hostcheck/check_modules.py
step "запрещённые API" "$PY" tools/security/check_forbidden_apis.py
step "секреты" "$PY" tools/security/check_secrets.py
step "корпус" "$PY" tools/hostcheck/check_fixtures.py
step "ссылки" "$PY" tools/hostcheck/check_docs.py
step "зависимости" "$PY" tools/provenance/check_dependencies.py
step "манифест" "$PY" tools/hostcheck/check_manifest.py

# ---- 2. Сборка и тесты ядра ----
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
  expect 0 "DIRECT (ru-direct)" - route $d/configs/valid-multi.json --domain www.example.ru
  expect 0 "restriction=ALLOWLIST_LIKE" - classify --condition ALLOWLIST_LIKE
  expect 0 "1. xhttp-cdn [HTTP_CDN]" - plan $d/configs/valid-multi.json --condition TLS_DEGRADED
  expect 0 "CONNECTED via domestic-relay" - connect $d/configs/valid-multi.json --condition ALLOWLIST_LIKE
  expect 1 "NETWORK_OFFLINE" - connect $d/configs/valid-multi.json --condition OFFLINE
  expect 0 "READY revision=1 profiles=3" "evil.example.com" subscription $d/subscriptions/links-with-directives.txt \
    --headers $d/subscriptions/links-with-directives.headers.json
  expect 0 '"id": "<redacted>"' "$uuid" export $d/configs/valid-minimal.json
  expect 0 "active_profile: reality-1" "$uuid" report $d/configs/valid-minimal.json
  expect 0 "route decisions:" - bench --rules 200
  expect 2 - - no-such-command
  [ "$fails" = 0 ] && echo "✓ cli: 13 сценариев — коды выхода и вывод как ожидалось, секреты не печатаются"
  return "$fails"
}

if [ "$MODE" != quick ]; then
  step "сборка и тесты ядра" "${GRADLE[@]}" build :cli:installDist
  if [ -x "$CLI" ]; then
    step "cli smoke" cli_smoke
  else
    RESULTS+=("✗ cli smoke — нет $CLI (сборка не прошла)")
    FAILED=1
  fi

  # ---- 3. Android ----
  if android_sdk; then
    step "Android: сборка и Lint" "${GRADLE[@]}" :app:assembleDebug :app:lint
    step "Android: API ядра ≤ minSdk" "$PY" tools/hostcheck/check_android_api.py
  else
    skip "Android: сборка и Lint" "нет Android SDK (ANDROID_HOME или sdk.dir в local.properties)"
    skip "Android: API ядра ≤ minSdk" "нет Android SDK"
  fi
else
  RESULTS+=("○ сборка, тесты, cli, Android — не запускались (--quick)")
fi

echo
echo "hostcheck ($MODE):"
for r in "${RESULTS[@]}"; do echo "  $r"; done
if [ "$FAILED" != 0 ]; then
  echo "✗ hostcheck не пройден"
  exit 1
fi
if [ "$SKIPPED" != 0 ] || [ "$MODE" = quick ]; then
  echo "✓ hostcheck пройден частично — пропущенное выше не проверено"
else
  echo "✓ hostcheck пройден полностью"
fi
exit 0
