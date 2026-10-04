"""Поставщики генерации и кэш (Д26, PLATFORM_VISION §43–45).

Система не привязана к одной модели: GenerationProvider — интерфейс,
реализации добавляются по необходимости. Сейчас:
  - template — детерминированная подстановка: вариант-заготовка из канона;
  - session  — текст пишет сессия Claude Code в репозитории (запись о
               происхождении, без вызова API).
Вывод любого поставщика — `trust: unverified`, пока его не проверил
валидатор; одобряет только автор.

Кэш: ключ = sha256 (промпт, входы, контекст, модель, параметры);
повтор без изменения входов берётся из build/cache/generation/.
"""
from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from typing import Protocol

from .repo import Repo


@dataclass
class GenerationRequest:
    prompt: str                      # id@версия промпта или текст
    inputs: dict = field(default_factory=dict)
    context: str = ""                # отпечаток контекста (канон, факты)
    model: str = "none"
    params: dict = field(default_factory=dict)

    def key(self) -> str:
        raw = json.dumps({"prompt": self.prompt, "inputs": self.inputs, "context": self.context,
                          "model": self.model, "params": self.params}, ensure_ascii=False, sort_keys=True)
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()


@dataclass
class GenerationResult:
    text: str
    provider: str
    model: str
    key: str
    cached: bool = False

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.text.encode("utf-8")).hexdigest()


class GenerationProvider(Protocol):
    name: str

    def generate(self, req: GenerationRequest) -> str: ...


class TemplateProvider:
    """Без модели: текст = входной текст (заготовка варианта из канона)."""
    name = "template"

    def generate(self, req: GenerationRequest) -> str:
        return str(req.inputs.get("text", ""))


class Cache:
    def __init__(self, repo: Repo):
        self.dir = repo.path("build", "cache", "generation")

    def get(self, key: str) -> dict | None:
        p = self.dir / f"{key}.json"
        return json.loads(p.read_text(encoding="utf-8")) if p.is_file() else None

    def put(self, key: str, rec: dict) -> None:
        self.dir.mkdir(parents=True, exist_ok=True)
        (self.dir / f"{key}.json").write_text(json.dumps(rec, ensure_ascii=False), encoding="utf-8")


def generate(repo: Repo, provider: GenerationProvider, req: GenerationRequest, use_cache: bool = True) -> GenerationResult:
    key = req.key()
    cache = Cache(repo)
    if use_cache:
        hit = cache.get(key)
        if hit:
            return GenerationResult(hit["text"], hit["provider"], hit["model"], key, cached=True)
    text = provider.generate(req)
    res = GenerationResult(text, provider.name, req.model, key)
    cache.put(key, {"text": text, "provider": provider.name, "model": req.model, "result_sha256": res.sha256})
    return res
