"""Площадки с ручным путём (Д33): отрисовка и лимиты есть, публикация через API — нет.

VK (право wall — по запросу в поддержку), YouTube (загрузка без аудита —
приватно; метаданные — фаза 7), Discord (ОР-6), GitHub (фаза 8),
TikTok (аудит). Для каждой система готовит пакет публикации, автор
публикует сам и записывает адрес: `publish --record-manual`. Когда
площадка даст доступ — здесь появится реализация publish/edit/delete,
а модель, кампании и сайт не меняются (§69).
"""
from __future__ import annotations

from ..common import Finding
from ..core import markup
from .base import Payload, PlatformAdapter, check_text_limit


class VKAdapter(PlatformAdapter):
    platform = "vk"
    automated = False

    def validate(self, p: Payload) -> list[Finding]:
        out = super().validate(p)
        n = self.limits().get("media_max_items")
        if n and len(p.media) > n:
            out.append(Finding("error", f"медиа {len(p.media)} > {n}"))
        return out


class YouTubeAdapter(PlatformAdapter):
    platform = "youtube"
    automated = False

    def validate(self, p: Payload) -> list[Finding]:
        out = super().validate(p)
        lim = self.limits()
        if p.format == "description":
            if not p.title:
                out.append(Finding("error", "у описания YouTube нет названия (title)"))
            elif len(p.title) > (lim.get("title_max_chars") or 100):
                out.append(Finding("error", f"название {len(p.title)} > {lim.get('title_max_chars')} знаков"))
            if p.title and any(c in p.title for c in "<>"):
                out.append(Finding("error", "в названии YouTube нельзя < и >"))
            size = len(p.text.encode("utf-8"))
            if size > (lim.get("description_max_bytes") or 5000):
                out.append(Finding("error", f"описание {size} байт > {lim.get('description_max_bytes')}"))
        return out


class DiscordAdapter(PlatformAdapter):
    platform = "discord"
    automated = False

    def render_text(self, blocks: list, base: str) -> tuple[str, int]:
        text = markup.to_discord_md(blocks, base)
        return text, len(text)

    def validate(self, p: Payload) -> list[Finding]:
        out = super().validate(p)
        out += check_text_limit(p, self.limits().get("text_max_chars"), "сообщение Discord")
        for m in p.media:
            if m.size > (self.limits().get("file_max_bytes") or 20 << 20):
                out.append(Finding("error", f"{m.ref}: {m.size} байт > лимита файла"))
        return out


class GitHubAdapter(PlatformAdapter):
    platform = "github"
    automated = False

    def render_text(self, blocks: list, base: str) -> tuple[str, int]:
        text = markup.to_discord_md(blocks, base)  # GitHub Markdown — то же подмножество
        return text, len(text)


class TikTokAdapter(PlatformAdapter):
    platform = "tiktok"
    automated = False
