# Передача между сессиями

Обновлено: 4 октября 2026, сессия «DP Content Platform». Сессия
остановлена автором из-за лимита контекста — **посреди задачи сайта
П2-09…П2-14** (не начаты в коде). Следующая сессия начинает **отсюда**.

## Ветка и PR

- Ветка `ccr-16a9602f-9ogizk`; черновой PR #3 в `main`:
  https://github.com/dpsmpx/SocialMediaImage/pull/3 (сессия подписана на
  события PR). PR #1, #2 — влиты.
- Рабочее дерево на момент остановки — чистое, всё запушено (последний
  коммит — запись Д34 + этот HANDOFF).

## Поручение автора и ответы (сверка с решениями)

1. Постановка «DP Content Platform» — дословно в
   `docs/direction/PLATFORM_VISION.md`; трассировка разделов —
   `docs/direction/PLATFORM_TRACE.md`.
2. Ответы автора: **Д12 пересмотрен** (публикуется только одобренная
   автором версия, токены — только у автора, dry-run по умолчанию,
   автопубликации нет, первая площадка — Telegram); **ОР-1 закрыт → Д20**
   (имя «Борис Давыдов / Boris Davydov» рядом с `dpsmpx`; написание сверено
   по подписи статьи Telegraph); **Д21** — сайт `https://dpsmpx.com/`, RU +
   EN для главной, «обо мне», проектов; домен **не зарегистрирован**
   (RDAP 404, NXDOMAIN 04.10.2026) → **ОР-11**.
3. Позже автор: «разрабатывай сайт внутри репозитория, пока я не скажу,
   что сайт готов» → **Д34** (записано в `DECISIONS.md`, `CLAUDE.md` —
   исключение из «готово — остановиться», `ROADMAP` П2 «Сайт — в работе»,
   `TODO.md`, `docs/platforms/WEBSITE.md`).

## Сделано (в PR #3)

- **Решения:** Д12 (пересмотр), Д20–Д34, ОР-11; история в `DECISIONS.md`, §4.
- **Идентичность под Д20:** факт AU-NAME, `IDENTITY.md`, IC-03 RESOLVED,
  мастер-контекст, `CLAUDE.md`, `SECURITY.md`, промпт `post.v1`.
- **Исследование** (`docs/research/publishing/`): API площадок (Telegram
  Bot API 10.3; VK — право `wall` только по запросу в поддержку; YouTube —
  гранулярные квоты с 01.06.2026, без аудита — только приватно, обложки
  до 50 МБ с 14.09.2026; Discord; GitHub; TikTok — без аудита SELF_ONLY),
  сайт (A/B/C, хостинг), устройство системы.
- **Матрица** `docs/platforms/PUBLISHING_CAPABILITY_MATRIX.md` из
  `data/platforms/capabilities.yaml` (`capabilities --write`).
- **Код (модульный монолит `tools/cs`):** `core/` (repo, textdoc, markup,
  inject, content, validator, ledger, engine, errors, retry, credentials,
  obs, campaigns, analytics, generation, events, migrate, bundle, health),
  `adapters/` (base, website, telegram — реальный через транспорт, manual
  — VK/YouTube/Discord/GitHub/TikTok, mock — имитации с отказами),
  `site/` (build, feeds, i18n, preview), `platform_cli.py`,
  `platform_checks.py`, `capabilities.py`; тема `website/`.
- **Данные и схемы:** brands, projects (galdhold, dplang), destinations,
  policy, site, releases (пусто), campaigns (CMP-0001), publications
  (пустые журналы); схемы canonical, variant, project, brands,
  destinations, capabilities, policy, site, releases, campaign,
  publication; content_item v2; metrics/assets расширены.
- **CI-0001 → v2:** миграция `copy/` → `variants/`; канон `canonical/v1.md`
  (зафиксирован); варианты `website.ru`, `website.en`, `telegram.ru`,
  `vk.ru`, `github.en` — `ready`, `trust: validated`, **не одобрены**;
  тексты Telegram и VK — с именем по Д20; `github.en` alt — ещё «dpsmpx»
  (ждёт ревизии изображения). Цели публикации в `item.yaml`, кампания CMP-0001.
- **Сайт (staging):** главная, «обо мне», проекты RU/EN, лента, метки,
  фиды Atom/RSS/JSON, sitemap, robots, `/api/v1/`, 404; production-сборка
  пуста до одобрения и публикации.
- **Документы:** `ARCHITECTURE.md` (переписан), `content/CANONICAL_MODEL.md`,
  `PUBLISHING.md`, `GOVERNANCE.md`, `docs/operations/` (README,
  ENVIRONMENTS, CREDENTIALS, BACKUP, DISASTER_RECOVERY),
  `platforms/WEBSITE.md`, `ROADMAP/ROADMAP.md` (фазы П0–П11, К1–К7 с
  задачами), `README.md`, `TODO.md`, `PROCESS.md`, `SYSTEM_GOALS.md`,
  `CONTENT_STRATEGY.md` (§5–6), модели площадок, ссылки `copy/` → `variants/`.
- **`tools/check.sh`:** уровни `--quick`, `--affected`, `--release` (+ полный).

## Проверки на момент остановки

- Последний полный прогон `tools/check.sh` (до записи документов
  эксплуатации): все проверки зелёные, **кроме `links`**; тесты **103/103 OK**
  (с рендером).
- После него исправлены ложные срабатывания `links`; **осталась одна
  ошибка `links`:** `docs/direction/PLATFORM_TRACE.md` ссылается на
  `docs/audits/2026-10-04-PLATFORM_AUDIT.md`, которого **нет** (аудит не
  написан). Итог `tools/check.sh` поэтому — **красный**.
- После записи Д34 и правок документов полный прогон **не выполнялся**.
- Вручную проверено: dev-публикация CI-0001 (сайт и Telegram на
  имитациях), повтор без дубля, dry-run рабочего режима отказывает без
  одобрения; `health`, `bundle` (сверка манифеста ок), `preview CMP-0001`,
  `campaign status CMP-0001`; скриншоты staging (главная RU/EN, проект,
  «обо мне», лента).

## Не сделано / незавершено

1. **Аудит на дату** `docs/audits/2026-10-04-PLATFORM_AUDIT.md` — не
   написан (из-за него красный `links`). Либо написать срез, либо убрать
   ссылку из `PLATFORM_TRACE.md` (строка §121 Documentation).
2. **Сайт П2-09…П2-14** (`ROADMAP`, «Сайт — в работе») — **открыты**, в
   коде не начаты:
   - П2-09: предпросмотр кладёт сайт в `build/preview/<id>/site/`, а
     страницы ссылаются на `/style.css` от корня — **во фрейме
     предпросмотра сайт без стилей** (известная ошибка, видно на
     скриншоте); план — сайт в корень сборки предпросмотра, страница
     предпросмотра отдельно, `preview --serve`.
   - П2-10 доступность; П2-11 значок; П2-12 `_headers`; П2-13 sitemap
     `lastmod`/`hreflang`; П2-14 вёрстка 390 px.
3. Список задач трекера сессии (#3 «Архитектура и документы» — почти
   готово, #7 «Проверки, CI-0001, ROADMAP, HANDOFF», #8 «Сайт П2-09…14»)
   — не закрыты.
4. **К1-01** (сессия): ревизия подписи изображения CI-0001 по Д20 и
   alt-текста `github.en` — не начата.
5. Описание PR #3 — обновлено при остановке (сделано / осталось).

## Известные риски и открытые решения

- **ОР-11 (срочно):** домен `dpsmpx.com` свободен — его может занять
  другой; рабочий сайт и выгрузки нет (`site-production` — `blocked`).
- **ОР-3 (срочно):** ссылки YouTube в профилях Telegram/TikTok.
- Telegram не подключён: нет бота, тестового канала, id
  (`tg-devlog`, `tg-test` — `not_connected`) — П4-01…03, за автором.
- CI-0001: утверждение (Brand), О-1, права фотографа (Legal), одобрение
  вариантов — за автором; изображение ещё с подписью «dpsmpx».
- Числа с `recheck_by` (DP-STAGE, GH-READINESS — до 18.10.2026; GH-LOC и
  др. — до 04.11.2026): смена факта сделает варианты CI-0001 устаревшими.
- Защита «не из сессии ИИ» — по переменным `CLAUDECODE`/`CLAUDE_CODE_REMOTE`
  (от случайности; граница — отсутствие токенов в сессии).
- Telegram не даёт сверить исход публикации по ключу — после обрыва
  решает человек (`publish --reconcile`).
- Каталог `build/` (сборки, кэш, журнал development, выгрузка) — не в Git.

## Следующий безопасный шаг

1. Прочитать этот файл, `CLAUDE.md` (исключение Д34), `ROADMAP/ROADMAP.md`, П2.
2. Устранить красный `links` (п. 1 «Не сделано»), прогнать `tools/check.sh`.
3. По Д34 продолжить сайт: П2-09 (ошибка предпросмотра), затем П2-10…П2-14;
   после каждой — `site build`, скриншоты, коммит, отметка в ROADMAP.
4. Если автор ответил: ОР-11, П4-01…03, К1 — по `TODO.md`, «За автором».
