#!/usr/bin/env bash
# Проверки DP_AI_Orcestrator одной командой (docs/PROCESS.md, «Проверки»; D-15, D-16).
#
#   tools/verify/run.sh static       секунды: самотесты, формат, ссылки, модули, API, секреты, контракты, зависимости, README
#   tools/verify/run.sh affected     static + сборка и тесты затронутых модулей (tools/verify/affected.py)
#   tools/verify/run.sh full         всё: static + ./gradlew build + dpai; пропуск недоступного шага — ошибка
#   tools/verify/run.sh plan LEVEL   что будет запущено и почему, без запуска
#   tools/verify/run.sh report       итог последнего прогона (markdown) — в описание PR
#   tools/verify/run.sh doctor       окружение проверок: JDK, Python, git
#
# Код возврата: 0 — всё прошло; 1 — хотя бы один шаг упал; 2 — неверный вызов.
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 2

PY="${PYTHON:-python3}"
OUT="build/verify"
STATIC_STEPS=(
  "самотесты проверок:selftest.py"
  "формат:check_format.py"
  "ссылки:check_docs.py"
  "модули:check_modules.py"
  "запрещённые API:check_forbidden_apis.py"
  "секреты:check_secrets.py"
  "контракты:check_contracts.py"
  "экраны с секретами:check_secure_screens.py"
  "зависимости:check_dependencies.py"
  "README:readme_progress.py --check"
)
CLI="cli/build/install/dpai/bin/dpai"

usage() { sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; }

LEVEL="${1:-}"
case "$LEVEL" in
  static|affected|full) ;;
  plan)
    case "${2:-}" in static|affected|full) ;; *) usage; exit 2 ;; esac ;;
  report)
    if [ -f "$OUT/report.md" ]; then cat "$OUT/report.md"; exit 0; fi
    echo "отчёта нет: сначала tools/verify/run.sh static|affected|full"; exit 1 ;;
  doctor) ;;
  -h|--help|"") usage; [ -n "$LEVEL" ] && exit 0; exit 2 ;;
  *) echo "неизвестный уровень: $LEVEL"; usage; exit 2 ;;
esac

# ---- doctor ----
if [ "$LEVEL" = doctor ]; then
  bad=0
  if command -v java >/dev/null 2>&1; then
    v="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
    if [ "${v:-0}" -ge 21 ]; then echo "✓ JDK $v"; else echo "✗ JDK ${v:-?}: нужен 21 или новее"; bad=1; fi
  else echo "✗ нет java: нужен JDK 21"; bad=1; fi
  if "$PY" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)' 2>/dev/null; then
    echo "✓ $("$PY" --version 2>&1)"
  else echo "✗ нужен Python 3.11 или новее"; bad=1; fi
  if command -v git >/dev/null 2>&1; then echo "✓ $(git --version)"; else echo "✗ нет git"; bad=1; fi
  if git rev-parse --verify -q origin/main >/dev/null; then echo "✓ origin/main есть: affected считает от него"
  else echo "○ нет origin/main: affected выберет все модули (git fetch origin main)"; fi
  exit $bad
fi

# ---- plan ----
if [ "$LEVEL" = plan ]; then
  target="$2"
  echo "план: $target"
  for s in "${STATIC_STEPS[@]}"; do echo "  static: ${s%%:*} — tools/verify/${s#*:}"; done
  case "$target" in
    affected) "$PY" -B tools/verify/affected.py --plan | sed 's/^/  /' ;;
    full) echo "  full: ./gradlew build :cli:installDist — все модули, тесты, предупреждения как ошибки"
          echo "  full: dpai — собранная команда запускается, коды выхода"
          echo "  full: API Android — tools/verify/check_android_api.py (при Android SDK)" ;;
  esac
  exit 0
fi

# ---- прогон ----
rm -rf "$OUT"
mkdir -p "$OUT"
RESULTS="$OUT/results.tsv"
: > "$RESULTS"
FAILED=0
STARTED="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

record() { # record <статус> <имя> <журнал> <повторы>
  printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" >> "$RESULTS"
}

step() { # step <имя> <команда…>: вывод — в журнал, при отказе — на экран
  local name="$1"; shift
  local log="$OUT/$(echo "$name" | tr ' /:' '___').log"
  printf '… %s\n' "$name"
  if "$@" >"$log" 2>&1; then
    record pass "$name" "$log" 0
    grep -E '^✓' "$log" | sed 's/^/    /' || true
  else
    record fail "$name" "$log" 0
    FAILED=1
    sed 's/^/    /' "$log" | tail -60
  fi
}

# Шаг Gradle с повтором только при временном отказе загрузки — 429, 5xx, обрыв
# соединения — без постоянного отказа и признаков сбоя кода (gradle_log.py;
# docs/PROCESS.md, «Правила»: 429 от Maven Central через прокси — сбой
# инструмента, а не кода).
gradle_step() { # gradle_step <имя> <аргументы gradlew…>
  local name="$1"; shift
  local log="$OUT/$(echo "$name" | tr ' /:' '___').log"
  local attempt=0
  printf '… %s\n' "$name"
  while :; do
    if ./gradlew --console=plain --warning-mode=summary "$@" >"$log" 2>&1; then
      record pass "$name" "$log" "$attempt"
      [ "$attempt" -gt 0 ] && echo "    ✓ прошло после повторов загрузки: $attempt"
      return 0
    fi
    # Путь — абсолютный: относительный gradle_log.py читает от DPAI_VERIFY_ROOT (самотесты).
    if [ "$attempt" -lt 5 ] && "$PY" -B tools/verify/gradle_log.py "$ROOT/$log"; then
      attempt=$((attempt + 1))
      echo "    ○ временный отказ загрузки зависимостей (повтор $attempt из 5, пауза $((5 << attempt)) с)"
      sleep $((5 << attempt))
      continue
    fi
    record fail "$name" "$log" "$attempt"
    FAILED=1
    sed 's/^/    /' "$log" | tail -60
    return 1
  done
}

smoke_cli() { # собранная команда dpai: коды выхода и вывод
  local out code
  out="$("$CLI" version 2>&1)"; code=$?
  [ "$code" -eq 0 ] || { echo "dpai version: код $code, ожидался 0"; return 1; }
  echo "$out" | grep -qE '^dpai [0-9]' || { echo "dpai version: неожиданный вывод: $out"; return 1; }
  "$CLI" >/dev/null 2>&1; code=$?
  [ "$code" -eq 2 ] || { echo "dpai без команды: код $code, ожидался 2"; return 1; }
  "$CLI" no-such-command >/dev/null 2>&1; code=$?
  [ "$code" -eq 2 ] || { echo "dpai неизвестная команда: код $code, ожидался 2"; return 1; }
  "$CLI" help >/dev/null 2>&1; code=$?
  [ "$code" -eq 0 ] || { echo "dpai help: код $code, ожидался 0"; return 1; }
  echo "✓ dpai: version, help, неизвестная команда, без команды — коды и вывод как ожидалось"
}

for s in "${STATIC_STEPS[@]}"; do
  name="${s%%:*}"; cmd="${s#*:}"
  # shellcheck disable=SC2086
  step "$name" "$PY" -B tools/verify/$cmd
done

case "$LEVEL" in
  affected)
    tasks="$("$PY" -B tools/verify/affected.py)"
    if [ -n "$tasks" ]; then
      # shellcheck disable=SC2086
      gradle_step "сборка и тесты (affected)" $tasks
    else
      echo "… сборка не нужна: изменены только документы и проверки"
      record skip "сборка и тесты (affected)" "-" 0
    fi ;;
  full)
    if gradle_step "сборка и тесты (full)" build :cli:installDist; then
      step "dpai" smoke_cli
      # API JDK в модулях приложения против Android minSdk (D-34) — только
      # там, где модуль android собирается (есть Android SDK, D-34).
      if [ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ] || grep -qs '^sdk.dir=' local.properties; then
        step "API Android" "$PY" -B tools/verify/check_android_api.py
      else
        echo "… API Android: нет Android SDK — модуль android не собирается, сверка не нужна"
        record skip "API Android" "-" 0
      fi
    else
      record fail "dpai" "-" 0
    fi ;;
esac

# ---- отчёт ----
"$PY" -B - "$LEVEL" "$STARTED" "$RESULTS" "$OUT" <<'EOF'
import json, subprocess, sys
level, started, results, out = sys.argv[1:5]
def git(*a):
    try:
        return subprocess.run(["git", *a], capture_output=True, text=True, check=True).stdout.strip()
    except Exception:
        return ""
steps = []
for line in open(results, encoding="utf-8"):
    status, name, log, retries = line.rstrip("\n").split("\t")
    steps.append({"name": name, "status": status, "log": log, "downloadRetries": int(retries)})
result = "fail" if any(s["status"] == "fail" for s in steps) else "pass"
report = {"format": "dpai.verify-report", "version": 1, "level": level, "startedAt": started,
          "commit": git("rev-parse", "HEAD"), "dirty": bool(git("status", "--porcelain")),
          "result": result, "steps": steps}
with open(f"{out}/report.json", "w", encoding="utf-8") as fh:
    json.dump(report, fh, ensure_ascii=False, indent=2)
    fh.write("\n")
mark = {"pass": "✓", "fail": "✗", "skip": "○"}
lines = [f"verify ({level}): **{'PASS' if result == 'pass' else 'FAIL'}** на коммите "
         f"`{report['commit'][:12]}`" + (" (с незакоммиченными изменениями)" if report["dirty"] else ""), ""]
for s in steps:
    extra = f" — повторов загрузки: {s['downloadRetries']}" if s["downloadRetries"] else ""
    lines.append(f"- {mark[s['status']]} {s['name']}{extra}")
with open(f"{out}/report.md", "w", encoding="utf-8") as fh:
    fh.write("\n".join(lines) + "\n")
print()
print("\n".join(lines))
EOF

exit $FAILED
