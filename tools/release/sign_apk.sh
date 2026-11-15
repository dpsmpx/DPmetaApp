#!/bin/sh
# Подпись готового APK ключом выпуска автора (DPmetaApp:D-158, docs/RELEASE.md): Termux или ПК.
# Ключ и пароль — только у автора: пароль спрашивается без эха и передаётся apksigner через
# окружение, а не аргументом. Облачная сессия эту подпись не выполняет (ТЗ, §12.4).
#   sh tools/release/sign_apk.sh <вход.apk> <ключ.p12> [имя ключа, по умолчанию release]
# Нужны zipalign и apksigner: Termux — pkg install zipalign apksigner; ПК — build-tools Android SDK.
set -eu

in="${1:-}"; ks="${2:-}"; alias="${3:-release}"
[ -f "$in" ] && [ -f "$ks" ] || { echo "использование: sign_apk.sh <вход.apk> <ключ.p12> [имя ключа]"; exit 2; }
for t in zipalign apksigner; do command -v "$t" >/dev/null 2>&1 || { echo "нет $t: Termux — pkg install $t; ПК — build-tools Android SDK в PATH"; exit 2; }; done

out="${in%.apk}-signed.apk"
tmp="${in%.apk}-aligned.apk"
zipalign -f -p 4 "$in" "$tmp"
printf 'Пароль ключа (не отображается): '
stty -echo 2>/dev/null || true
read -r DPMETA_KS_PASS
stty echo 2>/dev/null || true
echo
export DPMETA_KS_PASS
apksigner sign --ks "$ks" --ks-key-alias "$alias" --ks-pass env:DPMETA_KS_PASS --key-pass env:DPMETA_KS_PASS --out "$out" "$tmp"
unset DPMETA_KS_PASS
rm -f "$tmp"
apksigner verify --print-certs "$out" | grep -E "SHA-256 digest|DN:" || true
if command -v sha256sum >/dev/null 2>&1; then sha256sum "$out"; fi
echo "✓ подписан: $out — проверьте: python3 tools/release/check_apk.py $out --cert <SHA-256 сертификата>"
