# Передача между сессиями

Обновлено: 4 октября 2026, сессия инициализации (остановлена автором у
лимита контекста). Следующая сессия начинает **отсюда**, не переделывая
сделанное.

## Текущая цель

Инициализация репозитория как системы контента dpsmpx (постановка
автора, 58 пунктов) и первый продукт — презентационное изображение.

## Ветка и PR

- Ветка: `ccr-a6143891-q6jj0g`; PR #1 (черновик) в `main`:
  https://github.com/dpsmpx/SocialMediaImage/pull/1
- Коммиты: `15f6abe` (каркас), `40710d6` (исследование), затем WIP-коммит
  с этим файлом (инструменты и спецификации — незаконченные).

## Сделано (в ветке)

- Исследование: `docs/audits/2026-10-04-SOCIAL_AUDIT.md` + классификация
  всех 53 записей канала-дневника `docs/audits/data/2026-10-04-telegram-devlog.csv`.
  GitHub, Telegram (автор, канал), YouTube, VK (ACCESS = LIMITED),
  Discord (UNKNOWN / AUTH REQUIRED), Telegraph.
- Решения: `docs/direction/DECISIONS.md` — Д0–Д18, ОР-1…ОР-9 (ОР-2 закрыт
  фотографией автора → Д18).
- `CLAUDE.md`, `README.md`, `.gitignore`.
- Идентичность: `docs/identity/IDENTITY.md`, `IDENTITY_CONFLICTS.md`
  (IC-01…IC-09, IC-01 = IDENTITY_LINK_INCONSISTENCY `@9_6ox`),
  `PROJECT_MAP.md`; `data/identity/links.yaml`.
- Контекст: `docs/context/AUTHOR_MASTER_CONTEXT.md`, `PROJECT_FACTS.md`;
  реестр `data/facts/facts.yaml`, запреты `data/facts/forbidden.yaml`.
- Стратегия: `docs/direction/CONTENT_STRATEGY.md`, `SYSTEM_GOALS.md`.
- Площадки: `docs/platforms/{TELEGRAM,YOUTUBE,GITHUB,VK,DISCORD}.md`;
  `data/platforms/export_specs.yaml`.
- Модель: `docs/content/{CONTENT_MODEL,CONTENT_PIPELINE,QUALITY_MODEL,CLAIM_POLICY,EXPERIMENTS_AND_ANALYTICS}.md`.
- Бренд: `docs/brand/VISUAL_SYSTEM.md`, `assets/brand/tokens.json`,
  шрифты IBM Plex (OFL) в `assets/brand/fonts/`.
- Фото автора: `assets/reference/author/2026-10-04-author-reference-01.jpg`
  (A-001, sha256 `2202da19…`, без EXIF); реестр
  `data/provenance/assets.yaml` (A-001, A-002 шрифты, A-003 токены).
- Схемы: `schemas/{content_item,facts,assets,item_provenance,composition,experiment,metrics}.schema.json`.
- Инструменты (WIP, не проверены целиком): `tools/cs/common.py`,
  `schema.py`, `model.py`, `render.py`; `tools/render/render.js`.
- Первый продукт: спецификации вариантов
  `content/items/CI-0001-personal-presentation/spec/variant-{a,b,c,d}.yaml`.

## Проверки, которые выполнены

- Пробный рендер (только глазами, файлы в `build/test/` — не в Git):
  A — master и telegram_post (RU); B, C, D — telegram_post (RU).
  Шрифты загружены, внешних запросов нет.
- Предварительная оценка: **A — лучший** (человек главный, 4 группы
  текста, интерьер затемнён; на обоях справа от лица — слабый узор,
  minor). **B** — мелкий текст (< 16 px: подписи ворот, метрик, sub),
  «Android → Windows» двусмысленно (major factual). **C** — читается
  интерьер (обои справа) — Safety major; мало доказательств. **D** — нет
  человека — нарушает иерархию «человек первым».
- `tools/check.sh` **не существует и не запускался**; тестов нет;
  схемы ни разу не прогонялись по данным.

## Не сделано

1. Инструменты: `tools/cs/__init__.py`, `__main__.py` (CLI: status,
   validate, render, export, new, metrics), `validate.py`, `facts.py`,
   `claims.py`, `provenance.py`, `secrets.py`, `links.py`, `visual.py`,
   `exports.py`, `metrics.py`, `newitem.py`; `tools/check.sh`;
   `.claude/settings.json` + хук начала сессии; `tests/` (unittest).
2. Первый продукт: `item.yaml` CI-0001, рендеры всех вариантов в
   `renders/`, `provenance.yaml`, экспорт A по форматам
   (`exports/`, RU и EN), документы
   `docs/content/products/personal_presentation/{BRIEF,CREATIVE_DIRECTION,PROMPT,CRITIQUE,DECISION,OUTPUTS,PROVENANCE}.md`,
   тексты `copy/`.
3. Документы: `docs/ARCHITECTURE.md`, `docs/PROCESS.md`,
   `docs/PROVENANCE.md`, `docs/SECURITY.md`, `docs/legal/RIGHTS_MATRIX.md`,
   `docs/audits/INITIAL_AUDIT.md`, `docs/research/*` (по темам + источники),
   `ROADMAP/ROADMAP.md` (фазы 0–7), `TODO.md`, `prompts/*` (включая
   `prompts/image/presentation-composition.v1.md`, на который уже ссылается
   `render.py`), `templates/*`, `assets/reference/README.md`.
4. **TikTok** (просьба автора, не начато): `https://www.tiktok.com/@dpsmpx`
   (параметры `_r`, `_t` из ссылки — трекинг, не сохранять). Исследовать
   профиль без входа; добавить в `IDENTITY.md`, `links.yaml`, аудит
   (дополнение с датой), `docs/platforms/TIKTOK.md`, роли площадок в
   `CONTENT_STRATEGY.md`, перечисление `platforms` в схемах
   (`content_item`, `experiment`, `metrics`) и `export_specs.yaml`.
5. Обновить описание PR после завершения.

## Известные проблемы и риски

- **`README.md` опережает факты**: таблица «Состояние» говорит, что
  первый продукт в `UNDER_REVIEW` с готовым экспортом и что есть
  `tools/check.sh` и `python3 -m tools.cs status` — этого ещё нет.
  Исправить при завершении.
- В документах есть ссылки на ещё не созданные файлы (п. 3) — проверка
  ссылок будет красной, пока их нет.
- `render.py`: `record_output` жёстко добавляет вход `A-002`; детектор
  переполнения ложно срабатывает на `name` и `portrait` (нужен допуск
  2 px и пропуск `data-text="0"`); `model.reached` для `ARCHIVED`/
  `REJECTED` возвращает False — в валидаторе брать последнее
  нетерминальное состояние из истории.
- Рендер нужен Node + Playwright (`/opt/node22/lib/node_modules/playwright`);
  Chromium сессии не доверяет CA прокси — для страниц из сети
  использовался обход через `route.fetch` (только для исследования;
  рендер работает с локальными файлами).
- Права фотографа на A-001 не подтверждены (гейт Legal → `pending`).

## Открытые решения автора

ОР-1 (настоящее имя), ОР-3 (ссылка `@9_6ox` в Telegram — **срочно**,
14 дней), ОР-4 (README профиля GitHub), ОР-5, ОР-6, ОР-7, ОР-8, ОР-9 —
`docs/direction/DECISIONS.md`, §3.

## Следующий безопасный шаг

1. Прочитать этот файл, `CLAUDE.md`, `DECISIONS.md`.
2. Доделать инструменты (п. 1) и `tools/check.sh`; прогнать на текущих
   данных, исправить найденное.
3. Выполнить п. 4 (TikTok).
4. Первый продукт (п. 2), затем документы (п. 3), README — по факту.
