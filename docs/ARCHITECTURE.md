# Архитектура DP_AI_Orcestrator

Срез на 6 октября 2026 (этапы 0–6). Решения, на которых держится
архитектура, — `docs/direction/DECISIONS.md`; здесь — как они разложены
по модулям и как через модули идут данные. Модели подсистем —
отдельными документами (§5).

## 1. Обзор

```
     android/ (этап 11)         cli/ (JVM)
   интерфейс · Keystore ·    dpai: doctor, сценарии,
   WorkManager · уведомления  режим без интерфейса, агент target
              \                  /
               ui/  состояния экранов, форматирование, deep links
                \               /
                 orchestrator/   команды, сверка и восстановление,
                      │          продолжение, полуавтономный режим
   ┌──────────┬───────┼─────────┬──────────┬───────────┬──────────┐
 tasks/    sessions/ contexts/ verification/ monitoring/ notifications/
 планиров- жизненный контекст,   профили     сводки,     дедупликация,
 щик, DAG  цикл,     handoff     проверки    устаревание агрегация
   │       heartbeat    │           │           │
   ├── accounts/ ───────┤        git/ ── worktree, владельцы, конфликты
   ├── execution/ ── target, процессы, ClaudeRunner, MockRunner
   │        │
   └──── security/ ── одобрения, хеш предложения, границы секретности
            │
   storage/ ── журнал событий, снимки, миграции, копии    ecosystem/ connectors/ analytics/
            │                                              DPmeta     GitHub      учёт
          core/ ── Outcome, Diagnostic, Secret, ID, Clock, хеши, строгий JSON,
                   автоматы состояний, события, Measure
```

Свойства, которые держат эту схему:

1. **Ядро не знает Android** (D-02): всё ниже `android/` — чистый
   Kotlin/JVM, собирается и проверяется на хосте; `cli` — вторая
   оболочка над тем же `orchestrator`.
2. **Сессия — исполнитель, а не состояние** (D-01, ТЗ, §150): задачи,
   контекст, handoff, события и рабочие деревья принадлежат
   оркестратору; сессию можно заменить без потери работы.
3. **Ядро детерминировано** (D-22): время — через `Clock`, случайность —
   через `RandomSource` (D-07), порядок при равенстве — по устойчивому
   ключу; LLM не принимает решений планировщика.
4. **Всё, что может отказать, возвращает `Outcome`** с диагностикой и
   причинами (D-05); команды оркестратора — `OperationResult` (D-06).
5. **Провайдер — за интерфейсом** (`ClaudeRunner`, `AIProvider`, ТЗ,
   §12, §52): ядро не зашивает Claude; V1 оптимизируется под Claude, и
   только через официальные интерфейсы (D-04).
6. **Экосистема — необязательна** (ТЗ, §5.3, §148): `ecosystem` —
   интерфейс и адаптеры; без DPmeta, GitHub и сети оркестратор работает
   настолько, насколько позволяют ресурсы.

## 2. Модули и границы

Граница модуля — граница ответственности. Таблица ниже — единственный
источник допустимых направлений зависимостей (D-21):
`tools/verify/check_modules.py` сверяет с ней `settings.gradle.kts` и
каждый `build.gradle.kts` (компилятор ловит отсутствующую зависимость,
но не лишнюю). Новый модуль или новое направление — правка этой таблицы
в том же изменении. Пустой модуль допустим: он закрепляет границу до
появления кода; этап в последнем столбце — когда появляется код.

<!-- check_modules: begin -->
| Модуль | Отвечает за | Может зависеть от | Этап |
|---|---|---|---|
| `core` | `Outcome`, `OperationResult`, `Provenance`, `Diagnostic` и коды, `Secret`, `Redactor`, типизированные ID, `Clock`, `RandomSource`, хеши, строгий и канонический JSON, автоматы состояний и жизненные циклы, модель событий, `Measure` | — | 1 |
| `testkit` | property-тесты с зерном, генераторы, фикстуры; только для тестов | `core` | 1 |
| `storage` | атомарная запись, журнал событий, снимки, миграции, резервные копии, повреждения | `core` | 2 |
| `security` | уровни операций, одобрения с хешем предложения, границы секретности, ссылки на учётные данные | `core` | 5, 18 |
| `execution` | `ExecutionTarget`, здоровье, процессы, `ClaudeRunner` и реализации, `MockRunner` | `core`, `security` | 3, 4 |
| `accounts` | реестр `AIAccount`, возможности, здоровье, cooldown, выбор аккаунта | `core`, `security` | 5 |
| `git` | репозитории, ветки, worktree и владельцы, diff, конфликты, безопасное слияние | `core`, `security` | 9 |
| `contexts` | `ContextBundle`, манифест, версии, отбор, сжатие, handoff | `core`, `security` | 8 |
| `tasks` | задачи, очередь, приоритеты, DAG, политики, планировщик | `core`, `security`, `accounts`, `execution` | 7 |
| `sessions` | реестр сессий, жизненный цикл, heartbeat, поток событий | `core`, `accounts`, `execution` | 6 |
| `verification` | профили FAST/NORMAL/FULL, доказательства, адаптер DP-Verify | `core`, `execution`, `git` | 10 |
| `monitoring` | сводки, временная шкала, свежесть, устаревание, ресурсы, самодиагностика | `core`, `accounts`, `execution`, `sessions`, `tasks` | 11 |
| `notifications` | категории, дедупликация, агрегация, cooldown, deep links | `core` | 12 |
| `ecosystem` | `EcosystemProvider`, адаптер DPmeta | `core` | 15 |
| `connectors` | GitHub и другие внешние сервисы | `core`, `security` | 15 |
| `analytics` | учёт использования провайдера; телеметрия приложения — отдельно | `core` | 11, 17 |
| `orchestrator` | сборка системы: команды, сверка, восстановление, продолжение, полуавтономный режим | `storage`, `security`, `execution`, `accounts`, `git`, `contexts`, `tasks`, `sessions`, `verification`, `monitoring`, `notifications`, `ecosystem`, `connectors`, `analytics` | 13, 14, 16 |
| `ui` | представление без платформы: состояния экранов, форматирование, разбор `dpai://` | `orchestrator` | 11 |
| `cli` | оболочка JVM: команда `dpai` | `orchestrator` | 0 |
| `android` | оболочка Android: интерфейс, Keystore, WorkManager, уведомления; подключается при Android SDK | `ui` | 11 |
| `tests/integration` | межмодульные сценарии | `orchestrator` | 1 |
<!-- check_modules: end -->

Правила, которые Gradle не выражает (тоже `check_modules.py`):

- `android.*` и `androidx.*` — только в модуле `android` (D-02).
- `testkit` — только в тестовых конфигурациях (`testImplementation`); в
  основном коде любого модуля — нельзя.
- Модуль `android` может отсутствовать в `settings.gradle.kts`: он
  подключается на этапе 11 и только при найденном Android SDK, как в
  `DPpass:settings.gradle.kts`. Плагин Android объявляет сам модуль, а не
  `build-logic`: без Google Maven ядро собирается.
- Запуск процессов — только в `execution` (D-03); системное время и
  случайность — только в `core`, пакет `clock` (D-07)
  (`tools/verify/check_forbidden_apis.py`).

Пакет модуля — `com.dpsmpx.dpai.<модуль>` (`tests/integration` —
`com.dpsmpx.dpai.tests.integration`).

Сборка: convention-плагины в `build-logic/` (`dpai.kotlin-library`,
`dpai.kotlin-application`, `dpai.test-suite`), версии —
`gradle/libs.versions.toml`. Байт-код — Java 17, инструменты — JDK 21,
предупреждения компилятора — ошибки.

## 3. Потоки данных

### 3.1. Задача → проверенный результат (целевой поток V1)

```
User intent ─► Task (tasks) ─► Scheduler: зависимости → приоритет →
  ресурсы → аккаунт (accounts) + target (execution) + worktree (git)
  ─► ContextBundle (contexts: версия + хеш)
  ─► Session (sessions) ─► ClaudeRunner (execution): stream-json
  ─► события (core.events) ─► журнал (storage) ─► мониторинг
  ─► «реализовано» ─► проверка (verification) ─► VERIFIED ─► DONE
  ─► handoff (contexts) ─► следующая задача или продолжение
```

Каждая стрелка — событие в журнале с корреляцией (D-12); каждый шаг —
`Outcome` с причинами (D-05). Чувствительное действие ждёт одобрения,
привязанного к хешу предложения (ТЗ, §68; `docs/SECURITY_MODEL.md`).

### 3.2. Событие исполнителя → состояние

```
target: событие с номером в потоке ─► SequenceTracker
  принято   → применить к производному состоянию, записать
  повтор    → ничего (идемпотентно)
  пропуск   → не применять; снимок или повтор с target; сверка
  конфликт  → тот же номер, другое событие: STATE_CONFLICT
```

### 3.3. Перезапуск приложения

```
чтение снимка + хвоста журнала ─► сессии в рабочих состояниях →
RECOVERY_PENDING ─► проба target и процесса ─► сверка
  → RUNNING | FAILED | COMPLETED | остаётся RECOVERY_PENDING с конфликтом
```

Подробно — `docs/RECOVERY.md`.

## 4. Ядро (`core`)

Пакеты `com.dpsmpx.dpai.core.…` (этап 1, `docs/direction/ROADMAP/01-core.md`).
Пакеты не образуют циклов: `diagnostics` ← `result` ← `ids` ← `operation`;
`OperationResult` отделён от `Outcome`, потому что ссылается на
идентификаторы, а разбор идентификаторов возвращает `Outcome`.

| Пакет | Содержимое |
|---|---|
| `result` | `Outcome<T>` (`Success` с предупреждениями, `Failure` с ошибками), `then`, `map`, `wrapFailure`, `combine` |
| `operation` | `OperationResult<T>` с `operationId`, временем и `Provenance`; `Actor` |
| `diagnostics` | `Diagnostic` (код, серьёзность, место, причины, сводка исключения), `DiagCode` с категорией и классом повтора, `Secret`, `Redactor` |
| `ids` | `TypedId`, виды идентификаторов с префиксами, `IdGenerator`, `ProviderSessionId` |
| `clock` | `Clock`, `ManualClock`, `Timestamp`, `RandomSource` |
| `hashing` | `ContentHash`, `Hashing.sha256` |
| `serialization` | `JsonValue`, строгий `JsonParser`, `CanonicalJson`, `JsonFields` (чтение с путём), `JsonPath`, `FormatHeader`, `DiagnosticJson` (диагностика между процессами) |
| `state` | `TransitionTable`, `Transition`; таблицы `SessionLifecycle`, `TaskLifecycle`, `AccountLifecycle`, `CircuitLifecycle`, `TargetLifecycle`; `HeartbeatFreshness` |
| `events` | `EventEnvelope`, `EventType`, `StreamRef`, `SequenceTracker` |
| `measure` | `Measure` (`Known` с источником, `Unknown` с причиной) |

Коды диагностики и типы событий — публичный контракт: только
добавляются (`tools/verify/registry/`).

## 5. Хранилище (`storage`)

Пакеты `com.dpsmpx.dpai.storage.…` (этап 2, D-24, D-25):

| Пакет | Содержимое |
|---|---|
| `vfs` | `Vfs` — единственный путь к файлам: запись, дописывание, fsync, атомарное переименование, fsync каталога; `NioVfs` на `java.nio` |
| `document` | `DocumentStore`: документы «ключ → JSON» в конверте `dpai.stored` с хешем; атомарная замена; last known good; миграция при чтении |
| `journal` | `EventJournal`: `dpai.journal-record` с цепочкой хешей; подтверждение после fsync; восстановление хвоста; карантин |
| `snapshot` | `Projection`, `SnapshotStore` (`dpai.snapshot`), `StateRecovery`: снимок + хвост журнала |
| `migration` | `Migration`, `Migrator`: цепочка соседних версий формата, отказ без изменения документа |
| `backup` | `BackupService`: копия с манифестом `dpai.backup-manifest`, проверка, возобновляемое восстановление |

```
команда ─► событие ─► EventJournal.append ─► fsync ─► подтверждение
                                   │
              производное состояние (Projection) ─► снимок (DocumentStore)
запуск ─► BackupService.resumeIfInterrupted ─► EventJournal.open (хвост, карантин)
       ─► StateRecovery.recover: снимок, если сходится с журналом, + хвост
```

Транзакция — одна запись журнала: изменение состояния либо записано и
подтверждено, либо нет; производное состояние пересчитывается (D-12).

## 6. Аккаунты и уведомления

Логика аккаунтов не зависит от транспорта и начата раньше этапов 3–4
(D-30): решения — чистые функции над состоянием, состояние — свёртка
событий.

| Модуль, пакет | Содержимое |
|---|---|
| `accounts` | `Accounts`: решения (`registerSlot`, `observeAuthStatus`, `observeLimit`, `lease`, `tick`…) возвращают события `AccountEvent`; `apply` сворачивает их в `AccountsState` с проверкой переходов `AccountLifecycle`. `AuthStatus` — разбор `claude auth status --json`; `SlotEnvironment` — окружение процесса `claude` для слота (D-29); `AccountCodec` — события в `dpai.event` и состояние в `dpai.accounts-state` |
| `notifications` | `NotificationEngine`: ключ предмета, окно агрегации, эскалация, предел частоты и сводка, `resolve`; `DeepLink` — `dpai://вид/id` |
| `orchestrator` | `EventRecorder` — конверты событий с номерами по потокам; `accounts.AccountsProjection` — проекция для снимков и восстановления (§5); `accounts.AccountNotifications` — какие события аккаунтов становятся уведомлениями |

```
факт (claude auth status, ответ сессии, такт) ─► Accounts.решение ─► события
   ─► EventRecorder ─► EventJournal ─► AccountsProjection (снимок)
   ─► AccountNotifications ─► NotificationEngine ─► показ / обновление / сводка
```

## 7. Исполнение и агент target

Модуль `execution` (этап 3, D-26, D-31; `docs/EXECUTION_MODEL.md`):

| Пакет | Содержимое |
|---|---|
| `target` | `ExecutionTarget`, `SshEndpoint`, `TargetHealthTracker` (пороги и предохранитель), `TargetDoctor`, `ExecutionTargetManager` |
| `process` | `ProcessHost`, `LocalProcessHost`, `ProcessTree` (остановка дерева), `Detacher` (процесс, переживающий сессию SSH), `run` с тайм-аутом |
| `ssh` | `SshProcessHost` (системный OpenSSH), `RemoteShell` (кавычки POSIX и Windows) |
| `agent` | протокол `dpai.agent-reply`, `RunSpec`, `AgentClient`, `RunChannel` (номера и сверка), `MockAgentHost` |
| `claude` | `ClaudeRunner`, `ClaudeCodeCliRunner`, `MockRunner`, `ClaudeCodeCli` (команда), `ClaudeStreamParser` и `ClaudeTurnAssembler` (поток → итог хода) |

Сторона target — `orchestrator.agent.AgentRuntime` (журнал запуска через
`storage`), команда — `dpai agent` модуля `cli`.

```
контроллер: AgentClient ─► ProcessHost (local | ssh | mock) ─► dpai agent <команда>
target:     start ─► Detacher ─► supervise ─► процесс; вывод ─► Redactor ─► events.jsonl
контроллер: RunChannel.poll ─► events --after N ─► SequenceTracker ─► принято | сверка
```

Модуль `sessions` (этап 6; `docs/SESSION_MODEL.md`, §6): `Sessions`
(решения), `SessionCodec`, `SessionDriver` (сессия поверх `ClaudeRunner`,
факты для аккаунтов). Проекция журнала — `orchestrator.sessions`.

```
задача ─► Accounts.lease ─► Sessions.create ─► bindingFor (сверка слота)
   ─► SessionDriver.launch ─► ClaudeRunner ─► poll ─► события сессии + факты аккаунтов
```

## 8. Модели подсистем

| Подсистема | Документ |
|---|---|
| Данные, сущности, инварианты | `docs/DATA_MODEL.md` |
| Execution target и runner | `docs/EXECUTION_MODEL.md` |
| Аккаунты и учётные данные | `docs/ACCOUNT_MODEL.md` |
| Сессии и их автомат | `docs/SESSION_MODEL.md` |
| Контекст и handoff | `docs/CONTEXT_MODEL.md` |
| Планировщик | `docs/SCHEDULER.md` |
| Восстановление | `docs/RECOVERY.md` |
| Безопасность | `docs/SECURITY_MODEL.md` |
| Тестирование | `docs/TESTING.md` |
| Производительность | `docs/PERFORMANCE.md` |
