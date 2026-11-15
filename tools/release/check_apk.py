#!/usr/bin/env python3
"""Проверка APK перед выкладкой (DPmetaApp:D-158, docs/RELEASE.md; ТЗ, §12.4).

Читает APK средствами Android SDK или Termux и говорит, годится ли он для раздачи:

- подпись проходит `apksigner verify`, схема v2 или новее;
- сертификат — не отладочный (`CN=Android Debug`) и, если задан `--cert`, его SHA-256
  совпадает с отпечатком ключа выпуска автора;
- пакет, версия и SDK — из `aapt2 dump badging` (если aapt2 есть);
- APK не `debuggable`;
- подписант один.

Без `--cert` или без aapt2 итог — «не проверено» (код 2), а не «годится»: отпечаток и
`debuggable` D-158 требует сверить (проверка PR #190).

Ничего не меняет и никуда не отправляет. Код выхода: 0 — годится, 1 — нет, 2 — нет
инструмента или файла или проверено не всё.

    python3 tools/release/check_apk.py app.apk --cert 1a2b…
"""
import argparse
import hashlib
import re
import shutil
import subprocess
import sys

DEBUG_DN = "CN=Android Debug"


def parse_signer(text):
    """Вывод `apksigner verify --print-certs -v`: схемы, DN и SHA-256 первого подписанта."""
    schemes = {m.group(1) for m in re.finditer(r"Verified using (v[0-9.]+) scheme[^:]*: true", text)}
    # Старые build-tools: «Signer #1 certificate …»; новые: «V2 Signer: certificate …».
    dn = re.search(r"(?:Signer #1|V[0-9.]+ Signer): certificate DN: (.+)", text) or re.search(r"Signer #1 certificate DN: (.+)", text)
    sha = re.search(r"(?:Signer #1|V[0-9.]+ Signer): certificate SHA-256 digest: ([0-9a-f]{64})", text) \
        or re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})", text)
    signers = re.search(r"Number of signers: (\d+)", text)
    return {
        "signers": int(signers.group(1)) if signers else None,
        "verified": text.strip().startswith("Verifies"),
        "schemes": sorted(schemes),
        "dn": dn.group(1).strip() if dn else None,
        "sha256": sha.group(1) if sha else None,
    }


def parse_badging(text):
    """Вывод `aapt2 dump badging`: пакет, версия, SDK, debuggable."""
    pkg = re.search(r"package: name='([^']+)' versionCode='([^']*)' versionName='([^']*)'", text)
    sdk = re.search(r"(?:minSdkVersion|sdkVersion):'(\d+)'", text)
    target = re.search(r"targetSdkVersion:'(\d+)'", text)
    return {
        "package": pkg.group(1) if pkg else None,
        "versionCode": pkg.group(2) if pkg else None,
        "versionName": pkg.group(3) if pkg else None,
        "minSdk": sdk.group(1) if sdk else None,
        "targetSdk": target.group(1) if target else None,
        "debuggable": "application-debuggable" in text,
    }


def problems(signer, badging, cert):
    """Что мешает раздаче: пустой список — годится."""
    out = []
    if not signer["verified"]:
        out.append("подпись не проходит apksigner verify")
    if not any(s in ("v2", "v3", "v3.1", "v4") for s in signer["schemes"]):
        out.append("нет подписи схемы v2 или новее")
    if signer["dn"] and DEBUG_DN in signer["dn"]:
        out.append("подписан отладочным ключом — для раздачи нужен ключ выпуска")
    if cert and signer["sha256"] != cert.lower():
        out.append("отпечаток сертификата не совпадает с ключом выпуска")
    if badging and badging["debuggable"]:
        out.append("APK собран как debuggable")
    if signer.get("signers") and signer["signers"] > 1:
        out.append(f"подписантов {signer['signers']} — ожидается один ключ выпуска")
    return out


def unchecked(badging, cert):
    """Что не проверено: пустой список — проверено всё, что требует D-158."""
    out = []
    if not cert:
        out.append("отпечаток не сверен: нужен --cert <SHA-256 сертификата ключа выпуска>")
    if badging is None:
        out.append("debuggable, пакет и версия не проверены: нужен aapt2 (Termux — pkg install aapt2)")
    return out


def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=120).stdout


def main(argv=None):
    ap = argparse.ArgumentParser(description="Проверка APK перед выкладкой (D-158)")
    ap.add_argument("apk")
    ap.add_argument("--cert", help="SHA-256 сертификата ключа выпуска (64 шестнадцатеричных знака)")
    a = ap.parse_args(argv)
    apksigner = shutil.which("apksigner")
    if not apksigner:
        print("нет apksigner: Termux — pkg install apksigner; ПК — build-tools Android SDK в PATH")
        return 2
    try:
        with open(a.apk, "rb") as f:
            digest = hashlib.sha256(f.read()).hexdigest()
    except OSError as e:
        print(f"APK не читается: {e.strerror}")
        return 2
    signer = parse_signer(run([apksigner, "verify", "--print-certs", "-v", a.apk]))
    aapt2 = shutil.which("aapt2")
    badging = parse_badging(run([aapt2, "dump", "badging", a.apk])) if aapt2 else None
    print(f"APK SHA-256: {digest}")
    print(f"Подпись: {', '.join(signer['schemes']) or '—'}; {signer['dn'] or '—'}")
    print(f"Сертификат SHA-256: {signer['sha256'] or '—'}")
    if badging:
        print(f"Пакет: {badging['package']} {badging['versionName']} ({badging['versionCode']}), SDK {badging['minSdk']}…{badging['targetSdk']}")
    else:
        print("Пакет и версия — не проверены: нет aapt2 (Termux — pkg install aapt2)")
    found = problems(signer, badging, a.cert)
    for p in found:
        print(f"✗ {p}")
    if found:
        return 1
    missing = unchecked(badging, a.cert)
    for m in missing:
        print(f"? {m}")
    if missing:
        print("— не проверено до конца: для выкладки не годится, пока не проверено всё")
        return 2
    print("✓ годится для выкладки")
    return 0


if __name__ == "__main__":
    sys.exit(main())
