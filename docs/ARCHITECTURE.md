# Архитектура DPmetaApp

Как устроен репозиторий и приложение сейчас и куда приходят следующие
подсистемы. Что решено — `docs/direction/DECISIONS.md`; что строится и
зачем — ТЗ (`docs/direction/TECHNICAL_TASK.md`, §6). Срез — 10 октября
2026, этап 1 (`ROADMAP/ROADMAP.md`).

## 1. Всё в одном

Одно приложение, один репозиторий, одна сборка, одно ядро (D-27, D-28);
исключение — DPpass (D-30): пока свои APK, сборка и ядро.

```
DPmetaApp/                        сборка Gradle из корня: ./gradlew build
├── dpai/                         подсистема «AI» (DP_AI_Orcestrator) и ядро приложения
│   ├── core storage security notifications      ← ядро: общее для всех подсистем
│   ├── accounts execution sessions tasks contexts git verification
│   │   monitoring ecosystem connectors analytics orchestrator   ← контур «AI»
│   ├── ui cli jsch               представление без платформы, команда dpai, SSH на JVM
│   └── android                   оболочка Android — первая форма приложения DPmetaApp
├── dppass/                       подсистема DPpass (D-30): свой процесс, сборка и APK, ядро — пока своё
├── socialmediaimage/             подсистема SocialMediaImage (D-43): контент и сайт, Python, свой процесс
├── dpvisualisation/              подсистема DPvisualisation (D-43): модель экосистемы и просмотрщик, свой процесс
├── dpmeta/                       подсистема DPmeta (D-43): реестр экосистемы, контракты, очередь, свой процесс
├── dpprofile/                    подсистема DPprofile (D-43): проверяемая история пути автора, свой процесс
├── (этап 1+) core/ cli/ app/     модули DPmetaApp на ядре dpai/ (ТЗ, §6.2)
├── docs/ ROADMAP/ TODO.md        процесс приложения целиком
└── tools/check/                  проверки корня; подсистемы — dpai/tools/verify/, dppass/tools/hostcheck/,
                                  socialmediaimage/tools/check.sh, dpvisualisation/tools/check/,
                                  dpmeta/tools/check.sh, dpprofile/tools/verify/
```

Сейчас в сборке корня только `dpai/`: модули DPmetaApp из ТЗ, §6.2
появятся на этапе 1 и дальше, остальные подсистемы — в фазе B (D-25).
DPpass в сборку корня не входит: он собирается из `dppass/` (Go и NDK,
`DPpass:D-25`) отдельным APK с прежней личностью пакета (D-30).
SocialMediaImage — инструменты на Python и сайт, DPvisualisation — модель
на Python и просмотрщик на JavaScript, DPmeta — документы и инструменты на
Python, DPprofile — документы и проверки на Python; в сборку корня и APK
они не входят, их проверки — шаги `subsystem.socialmediaimage`,
`subsystem.dpvisualisation`, `subsystem.dpmeta` и `subsystem.dpprofile`
(D-43).

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
  проверка `modules.root` (`tools/check/check.py`, таблица `ROOT_MODULES`):
  направления по ТЗ, §6.2 и правила их кода — Android только в оболочке,
  время через `Clock`, печать только в `:cli`, секрет раскрывается в одном
  месте (`core/net/…/Auth.kt`).

### Модули DPmetaApp (этап 2)

| Модуль | Что делает | Зависит от |
|---|---|---|
| `:core:model` | типы предметной области: `RepoRef`, `Observation`, место `Place`, строка и реестр проектов | ядро |
| `:core:formats` | разборщики с корпусом: подмножество TOML, реестр DPmeta `schema = 1`, журнал решений по шаблону номера, `TODO.md`, ROADMAP (флажки или таблица «Состояние»), блоки состояния README, описание PR | `model` |
| `:core:net` | HTTP: список разрешённых адресов (D-10), условные GET с ETag, бюджет запросов прогона, счёт для A-02; транспорт `java.net.http` без переадресаций | ядро |
| `:core:github` | GitHub REST: PR, ветки, коммит по SHA, файл; коды `GITHUB_*`; device flow GitHub App шагами (D-17) | `net`, `model` |
| `:core:metrics` | состояние из наблюдений (D-34), «кто работает» (F-16), вопросы автору, точки истории; `Rules.kt`; хранение — документ `state` и журнал `METRIC_POINT_RECORDED` на `dpai/storage` | `model`, ядро (`storage`) |
| `:core:sync` | план по реестру с приоритетом (PR → ветки → файлы), чтение GitHub или каталога (корпус, клоны), отказы по источникам, отложенное по бюджету | `formats`, `github` |
| `:cli` | `dpmeta-cli`: `sync --corpus/--clones/--live`, `state`, `history`, `smoke` | все |
| `:tests:corpus` | синтетический корпус «ловит / пропускает» (`samples/`), экосистема для синхронизации (`ecosystem/`), свои документы DPmetaApp как образцы | все |

Подсистемы DPmetaApp синхронизация читает из каталога DPmetaApp
(`Subsystems.DIRS` в `:core:sync`), пока в реестре DPmeta нет поля каталога
подсистемы (D-46); их PR и ветки общие с DPmetaApp, поэтому «кто работает»
у подсистемы — «неизвестно».

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

## 5. Как приходит следующая подсистема

Что может работать в приложении, переезжает в него своим кодом (D-35),
как `dpai/` (D-28) и `dppass/` (D-30); когда — слово автора:

```
система активного репозитория
  → каталог подсистемы: код, документы и история (слияние поддерева);
    исходный репозиторий замораживается
  → свой процесс, проверки и сборка подсистемы — из её каталога;
    отдельная установка на устройство
  → вход в общее приложение DPmetaApp: экраны в его оболочке, ядро dpai/
```

Паритет тождественный: переносится сам код с тестами. Своя оболочка
DPmetaApp со всеми подсистемами появится, когда в общий APK войдёт
вторая; до того оболочка — `dpai/android`, а DPpass — отдельный APK
(D-30). Данные, которые приложение получает от систем вне его, —
состояние и история чисел в хранилище ядра (D-34); GitHub и другие
сервисы — коннекторы синхронизации, а не запрос на каждое действие
(D-27).
