"""Адаптеры площадок (Д23). Реестр: площадка → класс; имитации — mock.py.

Ядро получает адаптер только через get(); добавить площадку — класс
здесь и запись в data/platforms/capabilities.yaml.
"""
from __future__ import annotations

from .base import PlatformAdapter
from .manual import DiscordAdapter, GitHubAdapter, TikTokAdapter, VKAdapter, YouTubeAdapter
from .telegram import TelegramAdapter
from .website import WebsiteAdapter

REGISTRY: dict[str, type[PlatformAdapter]] = {
    "website": WebsiteAdapter,
    "telegram": TelegramAdapter,
    "vk": VKAdapter,
    "youtube": YouTubeAdapter,
    "discord": DiscordAdapter,
    "github": GitHubAdapter,
    "tiktok": TikTokAdapter,
}


def adapter_class(platform: str) -> type[PlatformAdapter]:
    if platform not in REGISTRY:
        raise KeyError(f"нет адаптера площадки {platform}; есть: {', '.join(REGISTRY)}")
    return REGISTRY[platform]
