# Состояние экосистемы: 4 ноября 2026

Срез на дату — мета-проход по поручению автора (сессия DPmetaApp
4 ноября 2026, форма: «Сейчас, в этой сессии»). Повод — реестр после
переездов (M-16, SQ-36) и поправки к срезу
`docs/reports/2026-10-25-2-ECOSYSTEM_HEALTH.md`.

**Как собран.**
- **Клоны.** Шестнадцать репозиториев реестра и DPmetaApp — глубиной 50,
  со всеми ветками; получены 4 ноября около 15:20 UTC. Неполные клоны
  (`activity.py` их называет) — Galdhold, DPlang, DP_AI_Orcestrator,
  DP AIM Trainer: в них старая влитая ветка может выглядеть невлитой.
- **Отчёты.** Признаки работы — `docs/reports/2026-11-04-activity.md`
  (`--now` 15:30 UTC), сверка ссылок — `docs/reports/2026-11-04-xref.md`,
  манифест — `docs/reports/2026-11-04-dpai.ecosystem.json`.
- **Подсистемы.** Шесть систем — впервые без ключей `--clone`: строки
  реестра несут `dir`, файлы читаются из каталогов DPmetaApp на ветке
  прохода `be16a74c` (M-16).
- Срез задним числом не правится.

## 1. Итог

**Реестр сведён с системой (SQ-36 — сделано).**
- У шести подсистем — поле `dir`; DP AIM Trainer и DP_Clock — в реестре;
  Andors-Love и TrueJointTileMaker — `periphery` (M-16, `DPmetaApp:D-46`).
- DPmonetization — `archive` (слово автора, `DPmetaApp:D-58`): стадии и
  шлюзы готовности — метриками DPmetaApp, второй системы портфеля нет.
- Ссылок на номера, которых нет, — 0 из 1576 (25 октября по клонам
  замороженных репозиториев — 159).
- Манифест `dpai.ecosystem` — 16 проектов: периферия входит, архив
  (DPmonetization) — нет.

**Состав DPmetaApp — по словам автора 4 ноября 2026.**
- DPlang — отдельный проект до полного завершения, затем — в DPmetaApp;
  сейчас подключается для работы с ИИ, как другие (`DPmetaApp:D-58`).
- MAIN_OBSIDIAN_PC_works, DP_TextFile_Sorter и DP_Repeated_Tasks в
  DPmetaApp не переезжают; в реестр DPmeta не вносятся
  (`DPmetaApp:D-55`).
  - «Знания» — своя функция DPmetaApp по структуре Obsidian.
  - DP_Repeated_Tasks — часть DP_Clock.
- DP-Verify и анализ рынка — отдельные проекты; DPlang IDE — вне
  DPmetaApp; подсистемы на Python работают командами в Linux-среде
  приложения (`DPmetaApp:D-58`).
- Сайт выгружается и из приложения, если он проверен (`DPmetaApp:D-60`).
- Любой репозиторий профиля — для работы с ИИ (`DPmetaApp:D-56`).

**Кто работает** (`docs/reports/2026-11-04-activity.md`).
- **DPmetaApp — эта сессия.** Свежие ветки — рабочая этой сессии и ветка
  сборок `apk_debug`.
- **Остальные — без признаков по порогам.** Ближе всех к ним:
  - DPlang: коммит в `main` 7 ч назад, порог основной ветки — 6 ч;
  - Galdhold: ветка `ccr-d14e1404-2s2v9g` — 35 ч назад.

  Это не разрешение править (`docs/PROCESS.md`, раздел 2): в Galdhold и
  DPlang ветки DPmeta этот проход не пушил.
- **Замороженные репозитории.**
  - Архивированы: DP_AI_Orcestrator и DPpass.
  - Ещё не архивированы: SocialMediaImage, DPvisualisation, DPmeta,
    DPprofile (SQ-30); последние коммиты в них — надписи об архиве.
  - DPmonetization — ждёт архива автором (`DPmetaApp:D-58`).

**Сверено заново.**
- SQ-01: `dpsmpx/C-` — на месте, `Galdhold:CLAUDE.md:48`; ещё 8 строк в
  6 файлах (отчёт xref, «Прежние имена»).
- SQ-32: README DPlang по-прежнему «v0.2 — следующая, не начата»
  (`DPlang:README.md:21`, `0c40ca0`).
- SQ-13, SQ-14, SQ-16: ветки `dpmeta/docs-sync` в
  DP_game_market_analytic_system и Andors-Love — по-прежнему не влиты.
- **Пути, которых нет: 45** (25 октября — 43). Новые:
  - `DPmetaApp:app/…/LinksTest.kt:31` — намеренный образец пути с «..»
    в тесте переходов пульта, не ошибка;
  - остальные — те же, что прежде: срезы на дату и запланированное у
    DP-Verify.

## 2. Поправки к срезу `2026-10-25-2`

Исправлено после того среза, в той же ветке (PR #105); срезы задним числом
не правятся (`docs/PROCESS.md`, раздел 1, п. 8), поэтому поправки — здесь.

1. **§2, находка 2.** Ответ автора 17 октября в RISK-01 добавлен позже
   среза, по итогам второго круга проверки (`860ec37`), а не был там к
   срезу.
2. **Находка 3.** 06:32 UTC — `updated_at` репозитория, а не
   установленное время архивации: архивирован не позже него (SQ-30).
3. **Находка 6.**
   - У M-10 стояло «дополнено».
   - Строки истории D-46, M-16 и `DPvisualisation:D-27` не называли
     изменённые решения.
   - `DPvisualisation:D-20`, `DPvisualisation:D-22` («Проверка») и ТЗ
     `DPvisualisation:F-01` не были сведены с `DPvisualisation:D-27`.
4. **§3.** «UNKNOWN… только в записи истории за 5 октября» верно лишь о
   проверках `SocialMediaImage:Д35` (SQ-24).
5. **Проверка ветки.** Срез назвал только первый круг проверки отдельным
   проходом. Ветку проверяли и дальше, со второго круга до вливания (M-13,
   «Проверка»; описание PR #105).

## 3. Проверки

| репозиторий | коммит | команда | итог |
|---|---|---|---|
| DPmeta | ветка прохода | `CLONES=<клоны 4.11> tools/check.sh` | ✓ 16 клонов, самотестов 38 |
| DPmeta | ветка прохода | `tools/xref.py --root <клоны> --clone DPmeta=.` | ✓ номеров нет: 0 |
| DPmetaApp | `be16a74c` | `tools/check/run.sh full --strict` | ✓ 23 из 23 |

Galdhold, DPlang, DP-Verify, DPlearn, DP_game_market_analytic_system,
DPmonetization, Andors-Love, TrueJointTileMaker, DP AIM Trainer, DP_Clock
и шесть замороженных репозиториев — только чтение: правок в них нет, их
проверки проход не запускал.

## 4. Ветка прохода (M-13)

| где | ветка | проверки | известные находки | вливание |
|---|---|---|---|---|
| DPmetaApp (`dpmeta/` и остальное PR #152) | `ccr-d89d85c4-h08aww`, PR #152 | ✓ | — | после проверки отдельным проходом без подтверждённых находок |

В другие репозитории ветки DPmeta этот проход не пушил.

## 5. Что правилось

| документ | что |
|---|---|
| `tools/ecosystem.toml` | `dir` у шести подсистем; `periphery` — Andors-Love, TrueJointTileMaker, DP AIM Trainer, DP_Clock; DPmonetization — `archive` |
| `tools/xref.py`, `tools/manifest.py`, `tests/` | файлы подсистемы — из клона DPmetaApp и её `dir`; место `periphery`; самотесты на поле и место |
| `docs/SYNC_QUEUE.md` | SQ-36 — сделано; SQ-11, SQ-22, SQ-29, SQ-30 — по ответам автора 4 ноября; строки прохода в SQ-01, SQ-32 |
| `docs/RISKS.md` | RISK-01 закрыт: лицензии заменены (`DPmetaApp:D-47`) |
| `docs/ECOSYSTEM.md`, `docs/CONTRACTS.md`, `docs/PROCESS.md` | поле `dir`, периферия в реестре, DPmonetization в архиве, DP_Repeated_Tasks — часть DP_Clock |

## 6. Что решает автор

- **SQ-30** — архивировать SocialMediaImage, DPvisualisation, DPmeta,
  DPprofile (надписи об архиве на месте) и DPmonetization
  (`DPmetaApp:D-58`); закрыть PR #5 DPmeta.
- **Устаревшие ветки DPmeta** — удалить `dpmeta/docs-sync` в
  DP_game_market_analytic_system (SQ-13, SQ-14); ветка Andors-Love —
  освежить или закрыть (SQ-16).
