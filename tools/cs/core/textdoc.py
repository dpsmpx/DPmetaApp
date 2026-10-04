"""Текстовый документ с заголовком YAML: канонические версии и варианты площадок.

    ---
    schema: variant/v1
    platform: telegram
    ---
    текст

Заголовок — данные (проверяются схемой), тело — текст в разметке канона
(markup.py). Запись сохраняет порядок полей заголовка.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

import yaml


@dataclass
class TextDoc:
    meta: dict = field(default_factory=dict)
    body: str = ""

    def dumps(self) -> str:
        head = yaml.safe_dump(self.meta, allow_unicode=True, sort_keys=False, width=100)
        return f"---\n{head}---\n{self.body.strip()}\n"


def loads(text: str) -> TextDoc:
    if not text.startswith("---\n"):
        return TextDoc({}, text)
    end = text.find("\n---\n", 4)
    if end < 0:
        raise ValueError("заголовок YAML не закрыт строкой ---")
    meta = yaml.safe_load(text[4:end]) or {}
    if not isinstance(meta, dict):
        raise ValueError("заголовок YAML — не словарь")
    return TextDoc(meta, text[end + 5:].strip("\n"))


def load(path: Path) -> TextDoc:
    return loads(path.read_text(encoding="utf-8"))


def save(doc: TextDoc, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(doc.dumps(), encoding="utf-8")
