#!/bin/sh
# APK DPmetaApp — приложение корня :shell (D-35, D-53): пульт первым экраном, оболочка
# «AI» вкладкой, — на любом Linux с JDK 21 и Android SDK (D-31): телефон автора
# (proot, ярлык tools/termux/apk-build.sh), компьютер, облачная сессия. Отдельная
# сборка «AI» — `cd dpai && ./gradlew :android-app:assemble…` (D-35).
#
#   tools/build-apk.sh [release|debug] [ai]     по умолчанию release; ai — отдельная сборка
#                                               «AI» dpai/android-app (D-35), тот же ключ
#
# release — как у DPpass (DP_AI_Orcestrator:D-97, ответ автора 2 ноября 2026):
# подписан ключом выпуска из свойств dpai.release.* в ~/.gradle/gradle.properties
# (shell/build.gradle.kts — тот же ключ, что у сборки «AI»: APK ставится поверх). Без них Gradle подписал бы отладочным ключом —
# такой APK не встанет поверх подписанного, поэтому сценарий его не выдаёт:
# отказ, если только не задано DPAI_RELEASE_DEBUG_KEY=1 (замеры у себя, D-24).
# После сборки — apksigner verify: подпись v2/v3, отпечаток SHA-256 сертификата;
# свойство dpai.release.certSha256 (по желанию) — сверка с ним. Рядом с APK —
# SHA256SUMS. Пароли не попадают ни в командную строку, ни в вывод.
#
# Android SDK — ANDROID_HOME или sdk.dir в local.properties корня или dpai/: без него
# модули :app и :shell не подключаются (settings.gradle.kts). APK DPpass собирается
# отдельно (D-30).
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:-release}"
WHAT="${2:-app}"
case "$WHAT" in
  app) DIR="$ROOT"; MODULE=":shell"; OUT_DIR="$ROOT/shell" ;;
  ai) DIR="$ROOT/dpai"; MODULE=":android-app"; OUT_DIR="$ROOT/dpai/android-app" ;;
  *) echo "✗ сборка $WHAT: есть app (DPmetaApp) и ai (отдельная «AI»)"; exit 2 ;;
esac
case "$MODE" in
  release) TASK=assembleRelease ;;
  debug) TASK=assembleDebug ;;
  *) echo "✗ вариант $MODE: есть release и debug"; exit 2 ;;
esac
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  SDK="$(cat "$ROOT/local.properties" "$ROOT/dpai/local.properties" 2>/dev/null | sed -n 's/^sdk\.dir=//p' | head -n 1)"
fi
if [ -z "$SDK" ]; then
  echo "✗ нет Android SDK: ANDROID_HOME или sdk.dir в local.properties корня или dpai/"
  exit 2
fi

# Один SDK — и корню, и включённой сборке dpai/: каждая ищет его в своём local.properties.
export ANDROID_HOME="$SDK"
cd "$DIR"
sh ./gradlew --console=plain "$MODULE:$TASK"
OUT="$OUT_DIR/build/outputs/apk/$MODE"
ls "$OUT/"*.apk

if [ "$MODE" = release ]; then
  SIGNER="$(ls -d "$SDK"/build-tools/*/apksigner 2>/dev/null | tail -n 1)"
  [ -n "$SIGNER" ] || { echo "✗ нет apksigner в $SDK/build-tools: подпись release не проверить"; exit 1; }
  PROPS="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
  WANT="$(sed -n 's/^dpai\.release\.certSha256=//p' "$PROPS" 2>/dev/null | tr -d ' :' | tr 'A-F' 'a-f' | head -n 1)"
  for apk in "$OUT/"*.apk; do
    CERTS="$("$SIGNER" verify --verbose --print-certs "$apk")" || { echo "✗ $apk: подпись не прошла apksigner verify"; exit 1; }
    echo "$CERTS" | grep -q 'Verified using v2 scheme (APK Signature Scheme v2): true' ||
      echo "$CERTS" | grep -q 'Verified using v3 scheme (APK Signature Scheme v3): true' ||
      { echo "✗ $apk: нет подписи v2/v3"; exit 1; }
    # «Signer #1 certificate SHA-256 digest: …» (старые apksigner) или «V2 Signer: certificate SHA-256 digest: …».
    DIGEST="$(echo "$CERTS" | sed -n 's/^.*certificate SHA-256 digest: //p' | head -n 1)"
    if echo "$CERTS" | grep -q 'certificate DN: .*CN=Android Debug'; then
      if [ "${DPAI_RELEASE_DEBUG_KEY:-}" = 1 ]; then
        echo "! $apk подписан отладочным ключом (DPAI_RELEASE_DEBUG_KEY=1): только для замеров у себя"
      else
        echo "✗ $apk подписан отладочным ключом: нет свойств dpai.release.* в $PROPS"
        echo "  (storeFile, storePassword, keyAlias, keyPassword); для замеров у себя — DPAI_RELEASE_DEBUG_KEY=1"
        rm -f "$apk"
        exit 1
      fi
    elif [ -n "$WANT" ] && [ "$DIGEST" != "$WANT" ]; then
      echo "✗ $apk: сертификат $DIGEST, а dpai.release.certSha256 — $WANT"
      rm -f "$apk"
      exit 1
    fi
    echo "✓ $(basename "$apk"): подпись v2/v3, сертификат SHA-256 $DIGEST${WANT:+ — совпал с dpai.release.certSha256}"
  done
fi
(cd "$OUT" && sha256sum -- *.apk > SHA256SUMS && cat SHA256SUMS)
