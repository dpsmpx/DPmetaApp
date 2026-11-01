"""Ключ метки (Д41): 32 случайных байта у автора вне Git; из него — шаблоны меток.

Путь — `~/.config/dpsmpx/mark.key` или из переменной `DPSMPX_MARK_KEY`.
Без ключа метку не поставить и не прочитать; реестр и метки времени
работают и без него. Ключ в отчёты не печатается.
"""
from __future__ import annotations

import hashlib
import hmac
import os
import secrets
from pathlib import Path

KEY_ENV = "DPSMPX_MARK_KEY"
KEY_DEFAULT = "~/.config/dpsmpx/mark.key"


def key_path() -> Path:
    return Path(os.environ.get(KEY_ENV) or KEY_DEFAULT).expanduser()


def load(path: Path | None = None) -> bytes | None:
    """Ключ или None (нет файла — облачная сессия или ключ ещё не создан)."""
    p = path or key_path()
    if not p.is_file():
        return None
    raw = p.read_text(encoding="ascii", errors="strict").strip()
    key = bytes.fromhex(raw)
    if len(key) != 32:
        raise ValueError(f"{p}: ключ метки — 64 шестнадцатеричных знака (32 байта)")
    return key


def init(path: Path | None = None) -> Path:
    """Создать ключ (один раз). Уже есть — не перезаписывать: старые метки читаются только им."""
    p = path or key_path()
    if p.exists():
        raise FileExistsError(f"{p} уже есть — ключ не перезаписывается (им читаются выпущенные метки)")
    p.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(p, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="ascii") as f:
        f.write(secrets.token_hex(32) + "\n")
    return p


def fingerprint(key: bytes) -> str:
    """Короткий отпечаток ключа для реестра: какой ключ ставил метку (сам ключ из него не восстановить)."""
    return hashlib.sha256(b"dpsmpx-mark-key-id" + key).hexdigest()[:16]


def stream(key: bytes, label: str, n: int) -> bytes:
    """Детерминированный поток n байт из ключа и метки назначения (HMAC-SHA256 в режиме счётчика)."""
    out, i = bytearray(), 0
    while len(out) < n:
        out += hmac.new(key, f"{label}:{i}".encode(), hashlib.sha256).digest()
        i += 1
    return bytes(out[:n])
