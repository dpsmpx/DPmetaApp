"""Подписи интерфейса сайта (Д21: RU основной, EN — главная, «обо мне», проекты; статьи — на языке написания)."""
from __future__ import annotations

LABELS = {
    "ru": {
        "home": "Главная", "projects": "Проекты", "news": "Записи", "articles": "Статьи", "about": "Обо мне",
        "progress": "Прогресс",
        "toc": "Разделы страницы",
        "main_project": "Главный проект", "other_projects": "Другие проекты",
        "latest": "Последние записи", "all_posts": "Все записи", "follow": "Где следить",
        "contact": "Связаться", "what": "Что это", "technology": "Технологии", "platforms": "Платформы",
        "status": "Состояние", "scale": "Масштаб", "code": "Код", "links": "Ссылки", "plan": "План",
        "related": "Записи о проекте", "formerly": "прежде", "tags": "Метки",
        "draft": "Черновик: не одобрено автором — на рабочем сайте не появится.",
        "stale": "Устарело: изменились источники — нужна новая версия.",
        "staging": "Тестовая версия сайта (staging) — закрыта от поисковиков.",
        "no_posts": "Записей пока нет. Подробный дневник разработки — в Telegram.",
        "published": "Опубликовано", "discuss": "Эта запись на площадках",
        "feeds": "Подписаться", "not_found": "Страница не найдена",
        "not_found_text": "Такой страницы нет.", "not_found_home": "Начать с главной",
        "news_title": "Записи: новости, дневник, релизы",
        "articles_title": "Статьи",
        "articles_lead": "Длинные тексты о методе и проектах.",
        "latest_articles": "Статьи", "all_articles": "Все статьи", "minutes": "мин чтения",
        "breadcrumbs": "Навигационная цепочка",
        "projects_title": "Проекты",
        "skip": "К содержимому", "nav_label": "Разделы сайта", "other_lang": "Русская версия",
        "cat": {"news": "Новость", "devlog": "Дневник", "article": "Статья", "release": "Релиз", "page": "Страница"},
    },
    "en": {
        "home": "Home", "projects": "Projects", "news": "Posts (RU)", "articles": "Articles (RU)", "about": "About",
        "progress": "Progress",
        "toc": "On this page",
        "main_project": "Main project", "other_projects": "Other projects",
        "latest": "Latest posts", "all_posts": "All posts", "follow": "Follow",
        "contact": "Contact", "what": "What it is", "technology": "Technology", "platforms": "Platforms",
        "status": "Status", "scale": "Scale", "code": "Code", "links": "Links", "plan": "Plan",
        "related": "Posts about the project", "formerly": "formerly", "tags": "Tags",
        "draft": "Draft: not approved by the author — will not appear on the live site.",
        "stale": "Stale: sources changed — a new version is needed.",
        "staging": "Staging version of the site — hidden from search engines.",
        "no_posts": "No posts yet. The detailed development log is on Telegram (in Russian).",
        "published": "Published", "discuss": "This post elsewhere",
        "feeds": "Subscribe", "not_found": "Page not found",
        "not_found_text": "There is no such page.", "not_found_home": "Start from the home page",
        "news_title": "Posts", "projects_title": "Projects",
        "articles_title": "Articles",
        "articles_lead": "Long reads on method and projects.",
        "latest_articles": "Articles", "all_articles": "All articles", "minutes": "min read",
        "breadcrumbs": "Breadcrumbs",
        "skip": "Skip to content", "nav_label": "Site sections", "other_lang": "English version",
        "cat": {"news": "News", "devlog": "Devlog", "article": "Article", "release": "Release", "page": "Page"},
    },
}

PLATFORM_NAMES = {
    "telegram": "Telegram", "youtube": "YouTube", "github": "GitHub", "tiktok": "TikTok",
    "vk": "VK", "discord": "Discord", "telegraph": "Telegraph", "website": "dpsmpx.com",
}


def t(lang: str, key: str):
    return LABELS.get(lang, LABELS["ru"]).get(key) or LABELS["ru"][key]
