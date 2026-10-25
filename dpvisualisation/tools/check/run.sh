#!/bin/sh
# Проверки DPvisualisation (D-09, CLAUDE.md, «Проверки»):
#   tools/check/run.sh                 static — после любой правки, секунды
#   tools/check/run.sh full            всё — перед вливанием; итог — в описание PR
#   tools/check/run.sh --write-status  блок состояния README.md из ROADMAP
# Коды: 0 — прошло, 1 — находки, 2 — неверный вызов, 3 — сбой окружения.
cd "$(dirname "$0")/../.." || exit 3
command -v python3 >/dev/null 2>&1 || { echo "! нет python3 (нужен 3.11+)"; exit 3; }
PYTHONDONTWRITEBYTECODE=1 exec python3 -m tools.check "$@"
