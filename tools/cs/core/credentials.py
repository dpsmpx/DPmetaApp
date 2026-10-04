"""Учётные данные адресатов (Д12, Д25, docs/operations/CREDENTIALS.md).

В репозитории — только ссылка на учётные данные (`credential_ref`,
имя переменной окружения), не значение. Граница — площадка × окружение:
у staging и production разные имена. Значение никогда не печатается и
не попадает в журнал; проверка здоровья видит только «есть / нет / срок».

Срок действия и дата смены — по желанию в локальном файле автора
`local/credentials.yaml` (каталог `local/` закрыт .gitignore):

    CS_PROD_TELEGRAM_BOT_TOKEN: {rotated: 2026-10-05, expires: null}
"""
from __future__ import annotations

import datetime as _dt
import os
import re
from pathlib import Path

import yaml

REF = re.compile(r"^CS_(DEV|STAGING|PROD)_[A-Z0-9_]+$")
ENV_OF_PREFIX = {"DEV": "development", "STAGING": "staging", "PROD": "production"}


class Secret(str):
    """Строка-секрет: repr и str в логах не раскрывают значение."""

    def __repr__(self) -> str:  # pragma: no cover - защитное
        return "Secret(***)"


class EnvCredentialStore:
    def __init__(self, environ: dict | None = None, meta_file: Path | None = None):
        self.environ = os.environ if environ is None else environ
        self.meta_file = meta_file

    def get(self, ref: str | None) -> Secret | None:
        if not ref:
            return None
        val = self.environ.get(ref)
        return Secret(val) if val else None

    def meta(self, ref: str) -> dict:
        if not self.meta_file or not self.meta_file.is_file():
            return {}
        data = yaml.safe_load(self.meta_file.read_text(encoding="utf-8")) or {}
        return data.get(ref) or {}

    def health(self, ref: str | None, today: _dt.date | None = None) -> str:
        if not ref:
            return "не нужен"
        if not self.get(ref):
            return "нет"
        exp = self.meta(ref).get("expires")
        if exp:
            d = exp if isinstance(exp, _dt.date) else _dt.date.fromisoformat(str(exp))
            if d < (today or _dt.date.today()):
                return f"истёк {d}"
            return f"есть, до {d}"
        return "есть"


def ref_environment(ref: str) -> str | None:
    m = REF.match(ref or "")
    return ENV_OF_PREFIX[m.group(1)] if m else None
