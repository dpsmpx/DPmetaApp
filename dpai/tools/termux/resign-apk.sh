#!/bin/sh
# Переподписать APK сборки облака ключом выпуска автора — на телефоне, в
# Termux или в proot Alpine, где есть apksigner (docs/DEVICE_CHECK.md, §1.2;
# docs/PROCESS.md, §6). Ключ и пароли не покидают телефон: путь, псевдоним и
# пароли — те же свойства dpai.release.*, что берёт Gradle, из
# ~/.gradle/gradle.properties; пароли идут в apksigner через окружение
# (env:…), не через командную строку и не в вывод.
#
#   sh resign-apk.sh dpai-<версия>-release.apk dpai-<версия>-debug.apk
#
# Рядом с каждым APK — <имя>-author.apk. Сертификат подписи (SHA-256, SHA-1)
# печатается; если в свойствах есть dpai.release.certSha256 (полный хеш или
# его начало), он сверяется: не тот ключ — отказ, файл удаляется.
#
# Переменные: DPAI_GRADLE_PROPERTIES — другой файл свойств; APKSIGNER — путь
# к apksigner, если его нет в PATH и в $ANDROID_HOME/build-tools.
set -eu

PROPS=${DPAI_GRADLE_PROPERTIES:-$HOME/.gradle/gradle.properties}
if [ ! -f "$PROPS" ]; then
    echo "✗ нет $PROPS: в нём — dpai.release.storeFile, storePassword, keyAlias, keyPassword" >&2
    exit 2
fi
prop() { sed -n "s/^dpai\.release\.$1[[:space:]]*=[[:space:]]*//p" "$PROPS" | head -n 1 | tr -d '\r'; }
STORE=$(prop storeFile)
ALIAS=$(prop keyAlias)
EXPECT=$(prop certSha256 | tr -d ': ' | tr 'A-F' 'a-f')
DPAI_KS_PASS=$(prop storePassword)
DPAI_KEY_PASS=$(prop keyPassword)
if [ -z "$STORE" ] || [ -z "$ALIAS" ] || [ -z "$DPAI_KS_PASS" ] || [ -z "$DPAI_KEY_PASS" ]; then
    echo "✗ в $PROPS нет одного из dpai.release.storeFile, storePassword, keyAlias, keyPassword" >&2
    exit 2
fi
if [ ! -f "$STORE" ]; then
    echo "✗ нет хранилища ключа $STORE (dpai.release.storeFile)" >&2
    exit 2
fi
export DPAI_KS_PASS DPAI_KEY_PASS

signer() {
    if [ -n "${APKSIGNER:-}" ]; then echo "$APKSIGNER"; return 0; fi
    if command -v apksigner >/dev/null 2>&1; then command -v apksigner; return 0; fi
    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/android-sdk" "$HOME/Android/Sdk"; do
        [ -n "$sdk" ] || continue
        last=$(ls -d "$sdk"/build-tools/*/apksigner 2>/dev/null | tail -n 1)
        if [ -n "$last" ]; then echo "$last"; return 0; fi
    done
    return 1
}
SIGNER=$(signer) || {
    echo "✗ нет apksigner: в Termux — pkg install apksigner; или APKSIGNER=<путь>" >&2
    exit 2
}

# PKCS#12 (.p12, .pfx) — явно: apksigner по умолчанию ждёт тип хранилища JVM.
case "$STORE" in
    *.p12 | *.pfx | *.P12 | *.PFX) KS_TYPE="--ks-type PKCS12" ;;
    *) KS_TYPE="" ;;
esac

[ "$#" -gt 0 ] || { echo "sh resign-apk.sh <файл.apk>…" >&2; exit 2; }
for apk in "$@"; do
    [ -f "$apk" ] || { echo "✗ нет файла $apk" >&2; exit 2; }
    out="${apk%.apk}-author.apk"
    # shellcheck disable=SC2086 # KS_TYPE — два слова или пусто.
    # Схемы v2 и v3, без v1: minSdk 30, а JAR-подпись добавила бы в APK файлы
    # META-INF — содержимое должно остаться ровно тем, что собрано в облаке.
    "$SIGNER" sign --ks "$STORE" $KS_TYPE --ks-key-alias "$ALIAS" \
        --ks-pass env:DPAI_KS_PASS --key-pass env:DPAI_KEY_PASS \
        --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
        --out "$out" "$apk"
    certs=$("$SIGNER" verify --print-certs "$out")
    sha256=$(printf '%s\n' "$certs" | sed -n 's/.*certificate SHA-256 digest: //p' | head -n 1)
    sha1=$(printf '%s\n' "$certs" | sed -n 's/.*certificate SHA-1 digest: //p' | head -n 1)
    if [ -n "$EXPECT" ]; then
        case "$sha256" in
            "$EXPECT"*) ;;
            *)
                rm -f "$out" "$out.idsig"
                echo "✗ $apk: подписан сертификатом $sha256, а ждали $EXPECT (dpai.release.certSha256) — файл удалён" >&2
                exit 1
                ;;
        esac
    fi
    rm -f "$out.idsig"
    echo "✓ $out"
    echo "  сертификат SHA-256: $sha256"
    echo "  сертификат SHA-1:   $sha1"
    echo "  файл SHA-256:       $(sha256sum "$out" | cut -d ' ' -f 1)"
done
