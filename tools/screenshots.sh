#!/bin/sh
# Снимки экранов DPmetaApp на JVM (docs/SCREENSHOTS.md): Robolectric с нативной графикой
# рисует настоящие экраны приложения :shell — пульт, окно «AI», IDE с файлом — на
# синтетической экосистеме корпуса и демо-системе, без телефона и без данных автора.
#
#   tools/screenshots.sh            PNG — в docs/screenshots/ (их показывает README)
#   tools/screenshots.sh <каталог>  PNG — в указанный каталог (сверить, не трогая docs/)
#
# Robolectric и образ «Android целиком» (213 МБ) Gradle берёт только по свойству
# dpmeta.screenshots, со сверкой сумм (gradle/verification-metadata.xml); в APK и в
# обычную сборку они не попадают. Нужны JDK 21 и Android SDK — как для сборки APK.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/docs/screenshots}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

# Старые PNG снимков убираются: снимок, которого тест больше не делает, не остаётся в README молча.
find "$OUT" -maxdepth 1 -name '*.png' -type f -delete

cd "$ROOT"
python3 tools/prepare-screenshots.py
./gradlew --console=plain :shell:testDebugUnitTest \
  -Pdpmeta.screenshots -Pdpmeta.screenshots.dir="$OUT" \
  --tests 'com.dpsmpx.dpmeta.shell.ScreenshotsTest'

COUNT="$(find "$OUT" -maxdepth 1 -name '*.png' -type f | wc -l | tr -d ' ')"
if [ "$COUNT" -eq 0 ]; then
  echo "✗ снимков нет в $OUT" >&2
  exit 1
fi
echo "✓ снимков: $COUNT — $OUT"
