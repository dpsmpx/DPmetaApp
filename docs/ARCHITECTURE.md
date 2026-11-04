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
│   ├── android                   оболочка Android — библиотека (DP_AI_Orcestrator:D-103)
│   └── android-app               отдельная сборка «AI» (D-35)
├── dppass/                       подсистема DPpass (D-30): свой процесс, сборка и APK, ядро — пока своё
├── socialmediaimage/             подсистема SocialMediaImage (D-43): контент и сайт, Python, свой процесс
├── dpvisualisation/              подсистема DPvisualisation (D-43): модель экосистемы и просмотрщик, свой процесс
├── dpmeta/                       подсистема DPmeta (D-43): реестр экосистемы, контракты, очередь, свой процесс
├── dpprofile/                    подсистема DPprofile (D-43): проверяемая история пути автора, свой процесс
├── core/ cli/ app/               модули DPmetaApp на ядре dpai/ (ТЗ, §6.2); app — пульт
├── shell/                        приложение DPmetaApp — один APK: пульт и «AI» (D-53)
├── config/projects/              панели проектов (F-10, D-57)
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
ядро dpai/ ◄── подсистема «AI» dpai/ ◄── оболочка dpai/android (библиотека) ◄─┐
     ▲                                                                       ├── приложение :shell (D-53)
     └──── модули DPmetaApp (ТЗ, §6.2) ◄── пульт :app (библиотека) ◄────────────┘
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
| `:core:formats` | разборщики с корпусом: подмножество TOML, реестр DPmeta `schema = 1`, журнал решений по шаблону номера, `TODO.md`, ROADMAP (флажки или таблица «Состояние»), блоки состояния README, описание PR, панель проекта `config/projects/*.toml` (F-10, D-57) | `model` |
| `:core:net` | HTTP: список разрешённых адресов (D-10), условные GET с ETag, бюджет запросов прогона, счёт для A-02; транспорт `java.net.http` без переадресаций | ядро |
| `:core:github` | GitHub REST: PR, ветки, коммит по SHA, файл; коды `GITHUB_*`; device flow GitHub App шагами (D-17) | `net`, `model` |
| `:core:metrics` | состояние из наблюдений (D-34), «кто работает» (F-16), вопросы автору, точки истории; `Rules.kt`; хранение — документ `state` и журнал `METRIC_POINT_RECORDED` на `dpai/storage` | `model`, ядро (`storage`) |
| `:core:sync` | план по реестру с приоритетом (PR → ветки → файлы), чтение GitHub или каталога (корпус, клоны), отказы по источникам, отложенное по бюджету | `formats`, `github` |
| `:cli` | `dpmeta-cli`: `sync --corpus/--clones/--live`, `state`, `history`, `smoke` | все |
| `:tests:corpus` | синтетический корпус «ловит / пропускает» (`samples/`), экосистема для синхронизации (`ecosystem/`), свои документы DPmetaApp как образцы | все |

### Пульт `:app` (этап 3)

Android-библиотека корня (своего `applicationId` нет, D-27, D-49): модели
экранов на чистом Kotlin (`app/…/screens/`, снимки текстом на JVM —
`app/src/test/snapshots/`), экраны Compose (`app/…/ui/`, токены оформления
`Tokens`), хранилище токена под Keystore, блокировка `BiometricPrompt`
(F-45), синхронизация по кнопке; лента событий из разницы состояний (F-05),
итог проверок PR (F-15), «о себе» — блоки состояния README и панель
проекта по данным `config/projects/<Проект>.toml` (F-10, D-57: пункты
читаются при открытии карточки, файлы — в ресурсах APK),
документы — каталог и файлы по запросу с переходами (F-09; разбор Markdown —
`Markdown` из `dpai/ui`, одна функция на приложение). Подключается только
при найденном Android SDK, как оболочка `dpai/android`.

### Приложение `:shell` (D-35, D-53, D-54)

Своя оболочка DPmetaApp — модуль-приложение корня: один APK
`com.dpsmpx.dpai` (D-49) с подписью «DPmetaApp». Берёт библиотеками пульт
`:app` и оболочку «AI» `dpai/android` (`DP_AI_Orcestrator:D-103`; подмена
координаты `com.dpsmpx.dpai:android` в `settings.gradle.kts`). Первый экран
— `DpmetaActivity`, наследник `PultActivity`: пульт со вкладкой «AI»,
которая открывает экран подсистемы; пульт и оболочка «AI» друг о друге не
знают — связывает их только этот модуль. Экран «AI» наружу не
экспортируется, его вход из лаунчера снят (ТЗ, T-03). Процесс —
`DpmetaApplication` (наследник `DpaiApplication`): вход по PIN устройства
на все окна (D-59) — `AppLock` пульта по `LockPolicy` (раз за запуск и
после часа в фоне) закрывает любое окно экраном `LockActivity`; службы и
уведомления «AI» работают и без входа. Он же отвечает подсистеме
`loginNeeded()`: опасный ответ окна запроса «AI» поверх приложений — только
после входа (D-61, `DP_AI_Orcestrator:D-104`).

Версия — DPmetaApp по этапам (`gradle.properties`, `0.3.0-dev.N`);
`versionCode` выводится из неё (`0.N.P-dev.M` → N·10000 + P·100 + M,
выпуск — …99) и всегда больше, чем у отдельной сборки «AI», поэтому APK
ставится поверх неё с теми же данными и ключами Keystore. Версия «AI»
для сверки с агентом — своя (`BuildConfig.DPAI_VERSION` оболочки). Ключ
выпуска — тот же, что у сборки «AI» (`dpai.release.*`). Lint модуля —
предупреждения как ошибки, код библиотек проверяет Lint их модулей.
Сборка — `tools/build-apk.sh` (APK — в каталоге сборки модуля `shell`);
отдельная сборка «AI» — `dpai/android-app` (D-35).

Подсистемы DPmetaApp синхронизация читает из их каталога в DPmetaApp — поле
`dir` реестра DPmeta (D-46, `DPmeta:M-16`); их PR и ветки общие с DPmetaApp,
поэтому «кто работает» у подсистемы — «неизвестно».

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
- Без Android SDK собирается всё, кроме `:app` и `:shell` (как в
  `dpai/settings.gradle.kts`).
- Плагины Android и Compose — convention-плагины `dpai/build-logic`
  (`dpai.android-library`, `dpai.android-application`;
  `DP_AI_Orcestrator:D-102`): с одного classpath с Kotlin, плагин Kotlin
  загружается один раз. AGP — из Google Maven, где он закрыт, — из
  build_tools автора (`tools/build_tools_install.py`).

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
DPmetaApp — `:shell` (D-53): в ней пульт и «AI»; следующая подсистема
входит в APK её библиотекой, а DPpass — пока отдельный APK (D-30). Данные, которые приложение получает от систем вне его, —
состояние и история чисел в хранилище ядра (D-34); GitHub и другие
сервисы — коннекторы синхронизации, а не запрос на каждое действие
(D-27).
