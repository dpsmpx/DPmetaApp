# SocialMediaImage — контентная платформа Бориса Давыдова / dpsmpx

Система исследования, создания, проверки, публикации и хранения
контента автора **Борис Давыдов / dpsmpx**: свой сайт `dpsmpx.com` как
центральная точка и архив, и адаптированные версии для Telegram, VK,
YouTube, TikTok, Discord, GitHub.

Это не «кросспостинг-бот». Контент — производная проверенного контекста
(Д1): кто автор, что он строит, что доказуемо, для кого и где. Материал
создаётся **один раз** как канон, проверяется один раз и показывается
многими родными формами; площадки — каналы распространения, а канон,
медиа, происхождение и история публикаций остаются у автора (Д21, Д22).

```
Author / Projects → Research / Facts → Canonical content → Adaptation → Validation → Approval (автор)
  → Website (dpsmpx.com) → Platform adapters → Publication journal → Analytics → Strategy (решения автора)
```

## Состояние на 4 октября 2026

| что | состояние |
|---|---|
| исследование | источники и площадки (`docs/audits/`), API площадок и сайт (`docs/research/publishing/`), матрица возможностей |
| идентичность, проекты, факты | сделано; имя открыто — Д20; бренды автора и проектов — Д27 |
| модель контента | v2: канон с версиями, варианты площадок, подстановка фактов, зависимости и устаревание, кампании, проекты, релизы |
| публикация | движок: журнал, идемпотентность, повторы, структурные ошибки, dry-run / safe / production, одобрение автора по хэшу; адаптеры — сайт, Telegram (Bot API), имитации всех площадок; VK, YouTube, Discord, GitHub, TikTok — ручной путь (Д33) |
| сайт | генератор, staging (`site serve`), предпросмотр (`preview --serve`), главная, «обо мне», проекты RU/EN, лента, фиды Atom/RSS/JSON, sitemap с `lastmod`/`hreflang`, `/api/v1/`, значок, `_headers` (CSP), доступность и телефон (390 px); домен `dpsmpx.com` не зарегистрирован — ОР-11 |
| проверки | `tools/check.sh` (quick / affected / full / release) — 17 проверок и тесты: модульные, контрактные, движок, сквозной |
| первый продукт CI-0001 | `UNDER_REVIEW`, переведён в v2 (канон, варианты сайта RU/EN, Telegram, VK, GitHub), кампания CMP-0001; ждёт ревизии подписи по Д20 и утверждения автора |

Что дальше — `ROADMAP/ROADMAP.md` (фазы П0–П11 платформы и К1–К7
контента); незавершённое — `TODO.md`; постановка — `docs/direction/PLATFORM_VISION.md`.

## С чего начать

| вопрос | документ |
|---|---|
| Кто автор и что он делает? | `docs/context/AUTHOR_MASTER_CONTEXT.md` |
| Что о нём доказуемо, что нельзя утверждать? | `docs/context/PROJECT_FACTS.md`, `data/facts/facts.yaml`, `docs/content/CLAIM_POLICY.md` |
| Какие проекты главные? | `docs/identity/PROJECT_MAP.md`, `data/projects/` |
| Аккаунты, имена, бренды, расхождения | `docs/identity/`, `data/identity/brands.yaml` |
| Для кого и что публиковать? | `docs/direction/CONTENT_STRATEGY.md` |
| Что умеет каждая площадка? | `docs/platforms/PUBLISHING_CAPABILITY_MATRIX.md`, `docs/platforms/` |
| Как устроен сайт? | `docs/platforms/WEBSITE.md` |
| Как создать контент? | `docs/content/CONTENT_PIPELINE.md`, `docs/content/CANONICAL_MODEL.md`, `templates/`, `prompts/` |
| Как опубликовать? | `docs/content/PUBLISHING.md`, `docs/content/GOVERNANCE.md` |
| Как проверить и когда готово? | `docs/content/QUALITY_MODEL.md`, `tools/check.sh` |
| Токены, окружения, резервные копии, сбои | `docs/operations/` |
| Как устроены инструменты? | `docs/ARCHITECTURE.md` |
| Что решено и почему? | `docs/direction/DECISIONS.md` |
| Где остановилась прошлая сессия? | `docs/session/HANDOFF.md` |

## Карта репозитория

```
CLAUDE.md  README.md  TODO.md      правила; точка входа; незавершённое
ROADMAP/                           фазы П0–П11 и К1–К7 с задачами
docs/
  direction/   решения, цели, стратегия, постановка платформы и её трассировка
  identity/    идентичность, конфликты, карта проектов
  context/     мастер-контекст автора, факты по проектам
  platforms/   модель каждой площадки, сайт, матрица возможностей
  content/     модель (v1, v2), конвейер, публикация, управление, качество, утверждения
  operations/  окружения, учётные данные, резервирование, восстановление
  research/    исследования по темам (в том числе publishing/)
  audits/      срезы на дату
  brand/ legal/ session/   визуальная система; права; передача между сессиями
data/
  facts/ identity/ projects/ releases/ campaigns/   факты, ссылки и бренды, проекты, релизы, кампании
  platforms/   возможности площадок, адресаты, размеры экспорта
  policy/ website/ publications/   политика публикации, сайт, журнал публикаций
  provenance/ metrics/ experiments/
schemas/                           JSON Schema всех данных
content/items/<id>/                объекты: item.yaml, canonical/, variants/, spec/, renders/, exports/
website/                           тема сайта (шаблон, стили)
prompts/ templates/                промпты и шаблоны публикаций
assets/                            шрифты (OFL), токены, REFERENCE_IMAGE
tools/
  check.sh                         все проверки
  cs/                              пакет системы: core/ (ядро), adapters/ (площадки), site/ (сайт), проверки, рендер
  render/                          рендер композиций (Chromium)
tests/                             тесты
```

## Команды

```sh
tools/check.sh                       # всё (--quick, --affected, --release, --fast)
python3 -m tools.cs status           # объекты контента и их состояния
python3 -m tools.cs campaign status CMP-0001       # сводка кампании по площадкам
python3 -m tools.cs preview CI-0001 --serve        # предпросмотр всех вариантов: http://localhost:8001/_preview/
python3 -m tools.cs site build && python3 -m tools.cs site serve   # staging сайта: http://localhost:8000
python3 -m tools.cs publish CI-0001 telegram.ru --to tg-devlog     # пробный прогон публикации
python3 -m tools.cs health           # площадки, учётные данные, сайт, хранилище
python3 -m tools.cs bundle           # выгрузка всей базы (резервная копия)
```

Нужны Python 3.10+ с `PyYAML` и `jsonschema`; для рендера изображений —
Node.js и Playwright с Chromium (`docs/ARCHITECTURE.md`, «Окружение»).
Одобрение (`approve`) и рабочая публикация (`--production`) — только
автор, в своём терминале, со своими учётными данными (Д12, Д26).

## Права

Репозиторий закрытый (Д2). Шрифты IBM Plex — SIL OFL 1.1
(`assets/brand/fonts/OFL-IBM-Plex.txt`). Происхождение всех
материалов — `docs/PROVENANCE.md`.
