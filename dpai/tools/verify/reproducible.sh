#!/usr/bin/env bash
# Воспроизводимая сборка (этап 20, docs/PROCESS.md, «Выпуск»): две сборки
# одного коммита с нуля, без кеша сборки, — и сравнение SHA-256 всех jar
# модулей, дистрибутива dpai и (при Android SDK) содержимого APK release
# без блока подписи (tools/verify/apk_digest.py).
#
#   tools/verify/reproducible.sh
#
# Код возврата: 0 — артефакты совпали; 1 — различаются или сборка упала.
set -u
cd "$(dirname "$0")/../.."
OUT=build/reproducible
PY="${PYTHON:-python3}"
rm -rf "$OUT" && mkdir -p "$OUT"

build() { # build <n>: сборка с нуля и список «хеш путь» в $OUT/<n>.sha256
  ./gradlew --console=plain --no-build-cache --no-configuration-cache clean assemble :cli:distZip >"$OUT/build-$1.log" 2>&1 \
    || { echo "сборка $1 упала: $OUT/build-$1.log"; exit 1; }
  find . -path ./build-logic -prune -o \( -path '*/build/libs/*.jar' -o -path '*/build/distributions/*.zip' \) -print \
    | grep -v '^./build/' | sort | xargs sha256sum >"$OUT/$1.sha256"
  # APK — по содержимому без блока подписи: подпись ECDSA от сборки к сборке разная.
  find . -path '*/build/outputs/apk/release/*.apk' | sort | xargs -r "$PY" -I tools/verify/apk_digest.py >>"$OUT/$1.sha256"
}

build 1
build 2
if diff -u "$OUT/1.sha256" "$OUT/2.sha256" >"$OUT/diff.txt"; then
  echo "✓ воспроизводимая сборка: артефактов — $(wc -l <"$OUT/1.sha256"), все совпали"
  cp "$OUT/1.sha256" "$OUT/release.sha256"
  exit 0
fi
echo "✗ артефакты различаются:"
cat "$OUT/diff.txt"
exit 1
