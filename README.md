# SocialMediaImage — система контента dpsmpx

Система исследования, создания, проверки и хранения контента автора
**dpsmpx** (GitHub, Telegram, YouTube, TikTok, VK, Discord): изображения,
посты, описания, обложки, анонсы, дневники разработки.

Это не «генератор постов». Контент здесь — производная проверенного
контекста: кто автор, что он строит, что доказуемо, для кого и где.
Знание об авторе, проектах, аудиториях и площадках живёт в
репозитории, а не в памяти сессии ИИ.

```
идентичность → намерение → исследование → спецификация → генерация
→ проверка → публикация (автор) → измерение → выводы
```

## Состояние

| что | состояние на 4 октября 2026 |
|---|---|
| исследование источников | сделано, с ограничениями доступа — `docs/audits/INITIAL_AUDIT.md` |
| идентичность, проекты, факты | сделано — `docs/identity/`, `docs/context/`, `data/facts/` |
| модель контента, конвейер, гейты | сделано — `docs/content/`, `schemas/`, `tools/cs/` |
| проверки | `tools/check.sh` |
| первый продукт — презентационное изображение | `UNDER_REVIEW`: выбран вариант, экспорт готов; ждёт фотографию и решение автора (ОР-1, ОР-2) — `docs/content/products/personal_presentation/` |

Что дальше — `ROADMAP/ROADMAP.md`; незавершённое — `TODO.md`.

## С чего начать

| вопрос | документ |
|---|---|
| Кто автор и что он делает? | `docs/context/AUTHOR_MASTER_CONTEXT.md` |
| Что о нём доказуемо, что нельзя утверждать? | `docs/context/PROJECT_FACTS.md`, `data/facts/facts.yaml`, `docs/content/CLAIM_POLICY.md` |
| Какие проекты главные? | `docs/identity/PROJECT_MAP.md` |
| Аккаунты, имена, расхождения | `docs/identity/IDENTITY.md`, `docs/identity/IDENTITY_CONFLICTS.md` |
| Для кого и что публиковать? | `docs/direction/CONTENT_STRATEGY.md` |
| Что публиковать на каждой площадке? | `docs/platforms/` |
| Какой визуальный язык? | `docs/brand/VISUAL_SYSTEM.md` |
| Как создать новый контент? | `docs/content/CONTENT_PIPELINE.md`, `templates/`, `prompts/` |
| Как его проверить и когда он готов? | `docs/content/QUALITY_MODEL.md`, `tools/check.sh` |
| Что решено и почему? | `docs/direction/DECISIONS.md` |
| Где остановилась прошлая сессия? | `docs/session/HANDOFF.md` |

## Карта репозитория

```
CLAUDE.md                правила работы (кратко)
TODO.md                  незавершённое
ROADMAP/                 фазы 0–7
docs/
  direction/             решения, цели системы, контент-стратегия
  identity/              идентичность, конфликты, карта проектов
  context/               мастер-контекст автора, факты по проектам
  platforms/             модель каждой площадки
  content/               модель, конвейер, качество, утверждения, эксперименты
    products/            документы продуктов (бриф, направление, критика…)
  brand/                 визуальная система
  research/              исследования по темам
  audits/                срезы на дату
  legal/                 права на материалы
  session/               передача между сессиями
  ARCHITECTURE.md  PROCESS.md  PROVENANCE.md  SECURITY.md
data/
  facts/                 реестр утверждений и запрещённые формулировки
  identity/              проверенные ссылки
  platforms/             требования экспорта площадок
  metrics/ experiments/  метрики и эксперименты (пока пусто)
schemas/                 JSON Schema объектов
content/items/<id>/      объекты контента: item.yaml, спецификация, варианты, экспорт
prompts/                 версионированные промпты
templates/               структурные шаблоны публикаций
assets/
  brand/                 шрифты (OFL) и токены
  reference/             REFERENCE_IMAGE — настоящие фотографии автора
tools/
  check.sh               все проверки
  cs/                    инструменты системы (Python)
  render/                рендер композиций (Chromium)
tests/                   тесты инструментов
```

## Проверки

```sh
tools/check.sh            # всё
tools/check.sh --fast     # без рендера и тестов рендера
python3 -m tools.cs status   # объекты контента и их состояния
```

Нужны Python 3.10+ с `PyYAML` и `jsonschema`; для рендера — Node.js и
Playwright с Chromium (`docs/ARCHITECTURE.md`, «Окружение»).

## Права

Репозиторий закрытый (Д2). Шрифты IBM Plex — SIL OFL 1.1
(`assets/brand/fonts/OFL-IBM-Plex.txt`). Происхождение всех
материалов — `docs/PROVENANCE.md`.
