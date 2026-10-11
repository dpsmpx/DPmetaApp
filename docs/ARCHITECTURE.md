# Архитектура DPmetaApp

Как устроен репозиторий и приложение сейчас и куда приходят следующие
подсистемы. Что решено — `docs/direction/DECISIONS.md`; что строится и
зачем — ТЗ (`docs/direction/TECHNICAL_TASK.md`, §6). Срез — 10 октября
2026, этап 1 (`ROADMAP/ROADMAP.md`).

## 1. Всё в одном

Одно приложение, один репозиторий, одна сборка, одно ядро (D-27, D-28):

```
DPmetaApp/                        сборка Gradle из корня: ./gradlew build
├── dpai/                         подсистема «AI» (DP_AI_Orcestrator) и ядро приложения
│   ├── core storage security notifications      ← ядро: общее для всех подсистем
│   ├── accounts execution sessions tasks contexts git verification
│   │   monitoring ecosystem connectors analytics orchestrator   ← контур «AI»
│   ├── ui cli jsch               представление без платформы, команда dpai, SSH на JVM
│   └── android                   оболочка Android — первая форма приложения DPmetaApp
├── (этап 1+) core/ cli/ app/     модули DPmetaApp на ядре dpai/ (ТЗ, §6.2)
├── docs/ ROADMAP/ TODO.md        процесс приложения целиком
└── tools/check/                  проверки корня; подсистема — dpai/tools/verify/
```

Сейчас в сборке только `dpai/`: модули DPmetaApp из ТЗ, §6.2 появятся
на этапе 1 и дальше, остальные подсистемы — в фазе B (D-25).

## 2. Ядро одно

Ядро — модули `dpai/` ниже `orchestrator` (D-28; их устройство —
`dpai/docs/ARCHITECTURE.md`, §4, §5):

| Модуль | Что даёт всему приложению |
|---|---|
| `dpai/core` | `Outcome` и `OperationResult`, `Diagnostic` с реестром `DiagCode`, `Secret` и `Redactor`, типизированные ID, `Clock` и `RandomSource`, хеши SHA-256, строгий и канонический JSON, автоматы состояний, события с номерами в потоке, `Measure` («неизвестно» — не ноль) |
| `dpai/storage` | журнал событий с цепочкой хешей и fsync, атомарные документы с последней целой версией, снимки и пересчёт из журнала, миграции форматов, резервные копии |
| `dpai/security` | уровни операций, одобрение по хешу предложения, границы секретности |
| `dpai/notifications` | категории, дедупликация, агрегация, deep links, запросы человеку |

Правило: тип ядра объявлен один раз — в `dpai/core` (проверка
`core.single`); подсистема заводит свои коды диагностики и типы событий
в реестрах `dpai/tools/verify/registry/`, а не второе перечисление.
Ядро — одна stdlib Kotlin (`DP_AI_Orcestrator:D-17`).

## 3. Направления зависимостей

```
ядро dpai/ ◄── подсистема «AI» dpai/ ◄── оболочка dpai/android
     ▲
     └──── модули DPmetaApp (ТЗ, §6.2) ◄── приложение (:app / оболочка)
```

- Подсистема «AI» от модулей DPmetaApp не зависит
  (`DP_AI_Orcestrator:D-55`): `dpai/` собирается и проверяется и сам.
- Модули DPmetaApp зависят от ядра и, где нужно, от подсистемы «AI», но
  не наоборот; Android-типы — только в оболочке.
- Внутри `dpai/` направления держит `dpai/tools/verify/check_modules.py` по
  таблице `dpai/docs/ARCHITECTURE.md`, §2; для модулей DPmetaApp —
  проверка модулей корня (этап 1).

## 4. Сборка

- Корень — составная сборка: `settings.gradle.kts` подключает `dpai/`
  целиком (`includeBuild`), задачи корня `build`, `check`, `clean` зовут
  общие задачи подсистемы `:buildAll`, `:checkAll`, `:cleanAll`
  (`dpai/build.gradle.kts`).
- Wrapper один: копия `dpai/` в корне совпадает с ней байт в байт
  (проверка `build.wrapper`); версия Gradle и сверка суммы —
  `dpai/docs/PROVENANCE.md`.
- Каталог версий один — `dpai/gradle/libs.versions.toml`; модули
  DPmetaApp берут его оттуда.
- Без Android SDK собирается всё, кроме оболочки (как в
  `dpai/settings.gradle.kts`).

## 5. Как приходит следующая подсистема (фаза B)

```
законченная система (COMPLETE по мастер-ТЗ, раздел 5)
  → её Integration Contract (мастер-ТЗ, раздел 8)
  → модули DPmetaApp на ядре dpai/: свои форматы, коды, события
  → тесты семантического паритета с исходным проектом (мастер-ТЗ, раздел 9)
  → экраны в оболочке приложения
```

Исключение — подсистема «AI»: вошла сама своим кодом с историей, поэтому
паритет тождественный (D-28). Данные других систем — наблюдения в
журнале ядра; GitHub и другие сервисы — коннекторы синхронизации, а не
запрос на каждое действие (D-27).
