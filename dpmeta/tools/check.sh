#!/usr/bin/env bash
# Самопроверка DPmeta. Ничего не пишет вне build/ и не ходит в сеть.
#
#   tools/check.sh                 всё
#   CLONES=<каталог> tools/check.sh  где лежат клоны экосистемы
#                                  (по умолчанию — родитель DPmeta)
#
# Код возврата: 0 — всё зелёное; 1 — есть красное.
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLONES="${CLONES:-$(dirname "$ROOT")}"
FAILED=0

step() {  # имя, команда…
  local name="$1"; shift
  local out
  if out="$("$@" 2>&1)"; then
    printf '✓ %s\n' "$name"
  else
    printf '✗ %s\n%s\n' "$name" "$out" | sed '2,$s/^/    /'
    FAILED=1
  fi
}

python3 -c 'import sys; sys.exit(sys.version_info < (3, 11))' || {
  echo "✗ нужен Python ≥ 3.11 (tomllib)"; exit 1; }

step "документы: ссылки, свои номера, корень" python3 "$ROOT/tools/check_docs.py"
step "самотесты инструментов" python3 -B -m unittest discover -s "$ROOT/tests"

# Ссылки DPmeta на другие репозитории — только если клоны рядом есть.
others=$(python3 -B - "$ROOT/tools" "$CLONES" <<'EOF'
import sys, pathlib
sys.path.insert(0, sys.argv[1])
import xref
root = pathlib.Path(sys.argv[2])
repos = xref.load_registry(pathlib.Path(sys.argv[1]) / "ecosystem.toml")
print(sum((xref.find_clone(root, r) / ".git").exists() for r in repos if r.name != "DPmeta"))
EOF
)
# Подсистемы DPmetaApp (M-14, M-16): строки реестра несут `dir`, их файлы — в каталогах
# DPmetaApp, в котором лежит DPmeta; xref берёт их по ключу строки управления.
APP="$(dirname "$ROOT")"
SUBS=()
if git -C "$APP" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  SUBS+=(--clone "DPmetaApp=$APP")
else
  # Не внутри DPmetaApp (копия каталога отдельно) — DPmeta по своему ключу (M-14).
  SUBS+=(--clone "DPmeta=$ROOT")
fi

if [ "${others:-0}" -gt 0 ]; then
  step "ссылки DPmeta на другие репозитории ($others клонов)" \
    python3 "$ROOT/tools/xref.py" --root "$CLONES" \
      ${SUBS[@]+"${SUBS[@]}"} --only DPmeta --strict DPmeta --out /dev/null
else
  echo "○ ссылки DPmeta на другие репозитории — пропущено: рядом нет клонов ($CLONES)"
fi

if [ "$FAILED" -eq 0 ]; then echo "✓ DPmeta: всё зелёное"; else echo "✗ DPmeta: есть красное"; fi
exit "$FAILED"
