"""Метки времени на манифест выпуска (Д41; исследование §5): RFC 3161 и OpenTimestamps.

RFC 3161: запрос (TimeStampReq, DER) собирается здесь же — sha256 манифеста,
случайный nonce, просьба приложить сертификат; ответ службы (`.tsr`)
пишется рядом с манифестом, если служба выдала метку (статус 0 или 1).
Полная проверка подписи — `openssl ts -verify` с сертификатами службы
(`verify_rfc3161`); без openssl или сертификатов — «не проверено», а не
«верно».

OpenTimestamps: клиент `ots` (`pip install opentimestamps-client`) —
`ots stamp` пишет `.ots` сразу, полное доказательство в блоке Bitcoin —
через несколько часов (`ots upgrade`). Нет клиента — LIMITED.

Нет сети — выгрузка не падает: метка ставится позже,
`python3 -m tools.cs mark stamp <выпуск>`. Секретов в метках нет.
"""
from __future__ import annotations

import hashlib
import secrets
import shutil
import subprocess
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

TSA_DEFAULT = "https://freetsa.org/tsr"
SHA256_OID = bytes.fromhex("0609608648016503040201")  # 2.16.840.1.101.3.4.2.1


# ------------------------------------------------------------------ DER
def _len(n: int) -> bytes:
    if n < 0x80:
        return bytes([n])
    b = n.to_bytes((n.bit_length() + 7) // 8, "big")
    return bytes([0x80 | len(b)]) + b


def _tlv(tag: int, body: bytes) -> bytes:
    return bytes([tag]) + _len(len(body)) + body


def _int(v: int) -> bytes:
    b = v.to_bytes(max(1, (v.bit_length() + 8) // 8), "big", signed=True)
    return _tlv(0x02, b)


def tsq(digest: bytes, nonce: int | None = None) -> bytes:
    """TimeStampReq v1 на sha256-хэш: messageImprint, nonce, certReq TRUE."""
    if len(digest) != 32:
        raise ValueError("RFC 3161: нужен sha256 (32 байта)")
    algo = _tlv(0x30, SHA256_OID + b"\x05\x00")
    imprint = _tlv(0x30, algo + _tlv(0x04, digest))
    nonce = secrets.randbits(63) if nonce is None else nonce
    return _tlv(0x30, _int(1) + imprint + _int(nonce) + b"\x01\x01\xff")


def _read(buf: bytes, i: int) -> tuple[int, int, int]:
    """(тег, начало тела, конец тела) элемента DER с позиции i."""
    tag, n = buf[i], buf[i + 1]
    i += 2
    if n & 0x80:
        k = n & 0x7F
        n = int.from_bytes(buf[i:i + k], "big")
        i += k
    return tag, i, i + n


def tsr_status(tsr: bytes) -> int | None:
    """Статус ответа службы: 0 — выдана, 1 — выдана с изменениями, иначе — отказ; None — не DER."""
    try:
        tag, a, _ = _read(tsr, 0)
        if tag != 0x30:
            return None
        tag, b, _ = _read(tsr, a)  # PKIStatusInfo
        if tag != 0x30:
            return None
        tag, c, d = _read(tsr, b)
        if tag != 0x02:
            return None
        return int.from_bytes(tsr[c:d], "big", signed=True)
    except IndexError:
        return None


# ------------------------------------------------------------------ службы
@dataclass
class Stamp:
    kind: str  # rfc3161 | ots
    state: str  # done — метка есть; pending — сеть или служба недоступны, повторить позже; limited — нет клиента; failed
    note: str = ""
    path: Path | None = None


def rfc3161(manifest: Path, url: str = TSA_DEFAULT, post=urllib.request.urlopen, timeout: float = 20) -> Stamp:
    out = manifest.with_name(manifest.name + ".tsr")
    if out.exists():
        return Stamp("rfc3161", "done", "уже есть", out)
    req = urllib.request.Request(url, data=tsq(hashlib.sha256(manifest.read_bytes()).digest()),
                                 headers={"Content-Type": "application/timestamp-query",
                                          "User-Agent": "dpsmpx-cs-stamp"})
    try:
        with post(req, timeout=timeout) as r:
            body = r.read(1_000_000)
    except (urllib.error.URLError, OSError) as e:
        return Stamp("rfc3161", "pending", f"служба {url} недоступна ({type(e).__name__}) — позже: mark stamp")
    st = tsr_status(body)
    if st not in (0, 1):
        return Stamp("rfc3161", "failed", f"служба {url} не выдала метку (статус {st})")
    out.write_bytes(body)
    return Stamp("rfc3161", "done", url, out)


def _ots() -> str | None:
    return shutil.which("ots")


def ots(manifest: Path, runner=subprocess.run) -> Stamp:
    out = manifest.with_name(manifest.name + ".ots")
    if out.exists():
        return Stamp("ots", "done", "уже есть (полное доказательство — ots upgrade)", out)
    exe = _ots()
    if not exe:
        return Stamp("ots", "limited", "нет клиента: pip install opentimestamps-client — потом mark stamp")
    try:
        r = runner([exe, "stamp", str(manifest)], capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.SubprocessError) as e:
        return Stamp("ots", "pending", f"ots stamp: {type(e).__name__} — позже: mark stamp")
    if r.returncode != 0 or not out.exists():
        return Stamp("ots", "pending", f"ots stamp: код {r.returncode} — позже: mark stamp")
    return Stamp("ots", "done", "ожидает блока Bitcoin — через несколько часов: ots upgrade", out)


def stamp_all(manifest: Path, url: str = TSA_DEFAULT, post=urllib.request.urlopen, runner=subprocess.run) -> list[Stamp]:
    """Обе службы; ни одна ошибка не прерывает выгрузку."""
    return [rfc3161(manifest, url, post), ots(manifest, runner)]


def verify_rfc3161(manifest: Path, ca: Path | None = None, untrusted: Path | None = None,
                   runner=subprocess.run) -> tuple[bool | None, str]:
    """(True — подпись и хэш сошлись, False — не сошлись, None — не проверено) и пояснение."""
    tsr = manifest.with_name(manifest.name + ".tsr")
    if not tsr.is_file():
        return None, "метки RFC 3161 нет"
    st = tsr_status(tsr.read_bytes())
    if st not in (0, 1):
        return False, f"ответ службы — не метка (статус {st})"
    exe = shutil.which("openssl")
    if not exe or not ca or not ca.is_file():
        return None, "метка выдана; подпись не проверена — нужны openssl и сертификат службы (CAfile)"
    argv = [exe, "ts", "-verify", "-data", str(manifest), "-in", str(tsr), "-CAfile", str(ca)]
    if untrusted and untrusted.is_file():
        argv += ["-untrusted", str(untrusted)]
    r = runner(argv, capture_output=True, text=True, timeout=60)
    ok = r.returncode == 0 and "Verification: OK" in (r.stdout + r.stderr)
    return ok, ("подпись службы и хэш манифеста сошлись" if ok else (r.stdout + r.stderr).strip()[-300:])


def verify_ots(manifest: Path, runner=subprocess.run) -> tuple[bool | None, str]:
    p = manifest.with_name(manifest.name + ".ots")
    if not p.is_file():
        return None, "метки OpenTimestamps нет"
    exe = _ots()
    if not exe:
        return None, "метка есть; не проверена — нет клиента ots"
    r = runner([exe, "verify", str(p)], capture_output=True, text=True, timeout=120)
    text = (r.stdout + r.stderr).strip()
    if r.returncode == 0 and "Success!" in text:
        return True, text.splitlines()[-1][:300]
    if "Pending" in text or "pending" in text:
        return None, "ожидает блока Bitcoin — ots upgrade"
    return None, text[-300:] or f"ots verify: код {r.returncode}"
