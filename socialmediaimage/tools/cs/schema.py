"""Проверка документов по JSON Schema с приведением дат YAML к строкам."""
from __future__ import annotations

import datetime as _dt

import jsonschema

from .common import ROOT, load_json

_cache: dict[str, dict] = {}


def normalize(value):
    """YAML превращает 2026-10-04 в date; схема ждёт строку ISO."""
    if isinstance(value, (_dt.date, _dt.datetime)):
        return value.isoformat()
    if isinstance(value, dict):
        return {k: normalize(v) for k, v in value.items()}
    if isinstance(value, list):
        return [normalize(v) for v in value]
    return value


def schema(name: str) -> dict:
    if name not in _cache:
        _cache[name] = load_json(ROOT / "schemas" / f"{name}.schema.json")
    return _cache[name]


def errors(doc, name: str) -> list[str]:
    validator = jsonschema.Draft202012Validator(schema(name), format_checker=jsonschema.FormatChecker())
    out = []
    for e in sorted(validator.iter_errors(normalize(doc)), key=lambda e: list(e.absolute_path)):
        where = "/".join(str(p) for p in e.absolute_path) or "(корень)"
        out.append(f"{where}: {e.message}")
    return out
