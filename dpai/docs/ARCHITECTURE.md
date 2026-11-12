# Архитектура DP_AI_Orcestrator

Срез на 7 октября 2026 (этапы 0–16). Решения, на которых держится
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
| `execution` | `ExecutionTarget`, здоровье, процессы, `ClaudeRunner` и реализации, `MockRunner`; команды ходов Codex и Gemini и перевод их потоков в нормальную форму (D-100); процессы-собеседники с потоками байтов для языковых служб (`StreamHost`, D-137) | `core`, `security` | 3, 4 |
| `accounts` | реестр `AIAccount`, возможности, здоровье, cooldown, выбор аккаунта | `core`, `security` | 5 |
| `git` | репозитории, ветки, worktree и владельцы, diff, конфликты, безопасное слияние | `core`, `security` | 9 |
| `contexts` | `ContextBundle`, манифест, версии, отбор, сжатие, handoff | `core`, `security` | 8 |
| `tasks` | задачи, очередь, приоритеты, DAG, политики, планировщик | `core`, `security`, `accounts`, `execution` | 7 |
| `sessions` | реестр сессий, жизненный цикл, heartbeat, поток событий | `core`, `accounts`, `execution` | 6 |
| `verification` | профили FAST/NORMAL/FULL, доказательства, адаптер DP-Verify | `core`, `execution`, `git` | 10 |
| `monitoring` | сводки, временная шкала, свежесть, устаревание, ресурсы, самодиагностика | `core`, `accounts`, `execution`, `sessions`, `tasks` | 11 |
| `notifications` | категории, дедупликация, агрегация, cooldown, deep links, запросы человеку (D-44) | `core` | 12 |
| `ecosystem` | `EcosystemProvider`, адаптер DPmeta | `core` | 15 |
| `connectors` | GitHub и другие внешние сервисы | `core`, `security` | 15 |
| `files` | файлы пользователя (D-99): дерево файлов без символьных ссылок, копия папки с двусторонней синхронизацией по базе, текст для правки, вложения в чат | `core`, `storage` | — |
| `providers` | `AIProvider` (ТЗ, §52): провайдеры моделей по ключу — Anthropic, OpenAI-совместимые, Gemini; сеть со списком адресов `ProviderHosts`; переписка чата (D-98); модели GGUF с Hugging Face — список и скачивание с докачкой и сверкой суммы в приёмник вызывающего, хосты `HuggingFaceHosts` (D-110, D-119) | `core`, `accounts` | — |
| `localai` | LocalAIService (ТЗ, §77, §78; D-105): подсказки модели на этом устройстве или в частной сети — сводка, порядок контекста, классы строк журнала, отклонения, сводка уведомлений, категория задачи; ответ — JSON по схеме, выбор — из данного; ядро и планировщик от неё не зависят | `core`, `providers` | — |
| `lsp` | клиент языковых служб редактора (D-137): кадры и JSON-RPC протокола LSP поверх потоков, документы с версиями, диагностика, дополнение, переходы, переименование, форматирование, правки текста в UTF-16; команда clangd; процессов не запускает, execution — только для живого теста с настоящим clangd | `core`, `execution` | — |
| `sync` | облачная синхронизация (ТЗ, §4.1; D-107): копия состояния контура, зашифрованная на устройстве (PBKDF2-HMAC-SHA256, AES-256-GCM), архив с оглавлением и разрешёнными корнями, восстановление при запуске с сохранением прежних данных, клиент Git Data API к `api.github.com` для приватного репозитория; ядро без облака работает | `core`, `storage`, `providers` | — |
| `analytics` | учёт использования провайдера; телеметрия приложения — отдельно | `core` | 11, 17 |
| `orchestrator` | сборка системы: команды, сверка, восстановление, продолжение, полуавтономный режим | `storage`, `security`, `execution`, `accounts`, `git`, `contexts`, `tasks`, `sessions`, `verification`, `monitoring`, `notifications`, `ecosystem`, `connectors`, `analytics` | 13, 14, 16 |
| `ui` | представление без платформы: состояния экранов, форматирование, разбор `dpai://`, приватные снимки редактора; files только для тестов восстановления/сохранения | `orchestrator`, `files` | 11 |
| `cli` | оболочка JVM: команда `dpai` | `orchestrator` | 0 |
| `jsch` | SSH-хост на JVM (JSch) для targets там, где нет системного OpenSSH — приложение Android (D-35); интерактивная оболочка с PTY для терминала (D-71) | `execution` | 11 |
| `terminal` | эмулятор xterm: экран, разбор последовательностей, ввод — свой терминал (D-71); без Android | `core` | — |
| `android` | оболочка Android — библиотека (D-103): интерфейс, Keystore, WorkManager, уведомления, экран терминала (D-71); нативный код процессов и псевдотерминала (`JniProcesses`, C через NDK; D-75); ключи API-профилей и чат (D-98); файлы пользователя — папки системного окна и всё хранилище (D-99); сервер локальной модели llama.cpp и подсказки локального ИИ (D-105); политика прогона в окне «Экосистема» (D-106); облачная копия контура (D-107); языковая служба C++ в Linux приложения и её показ в редакторе (D-137); подключается при Android SDK | `ui`, `jsch`, `terminal`, `providers`, `files`, `localai`, `sync`, `lsp` | 11 |
| `android-app` | отдельная сборка «AI» (D-103): `applicationId`, версия, подпись, упаковка APK; своего кода нет; подключается при Android SDK | `android` | — |
| `tests/integration` | межмодульные сценарии | `orchestrator` | 1 |
<!-- check_modules: end -->

Правила, которые Gradle не выражает (тоже `check_modules.py`):

- `android.*` и `androidx.*` — только в модуле `android` (D-02).
- `testkit` — только в тестовых конфигурациях (`testImplementation`); в
  основном коде любого модуля — нельзя.
- Модули `android` и `android-app` могут отсутствовать в
  `settings.gradle.kts`: они подключаются только при найденном Android
  SDK, как в `DPpass:settings.gradle.kts`. Плагины Android и Compose —
  convention-плагины `build-logic` (D-102): плагин Kotlin загружается
  один раз; AGP — из Google Maven или build_tools автора.
- Запуск процессов — только в `execution` (D-03); системное время и
  случайность — только в `core`, пакет `clock` (D-07)
  (`tools/verify/check_forbidden_apis.py`).

Пакет модуля — `com.dpsmpx.dpai.<модуль>` (`tests/integration` —
`com.dpsmpx.dpai.tests.integration`).

Сборка: convention-плагины в `build-logic/` (`dpai.kotlin-library`,
`dpai.kotlin-application`, `dpai.test-suite`, `dpai.android-library`,
`dpai.android-application`), версии —
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
| `vfs` | `Vfs` — единственный путь к файлам: запись, дописывание, fsync, атомарное переименование, fsync каталога, символьные ссылки и права (корневая система Linux-среды); `NioVfs` на `java.nio` |
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
| `accounts` | `Accounts`: решения (`registerSlot`, `observeAuthStatus`, `observeLimit`, `lease`, `tick`…) возвращают события `AccountEvent`; `apply` сворачивает их в `AccountsState` с проверкой переходов `AccountLifecycle`. `AuthStatus` — разбор `claude auth status --json`, `codex login status` и пробы входа Gemini (D-100); `SlotEnvironment` — окружение процесса исполнителя для слота (D-29, D-100); `AccountCodec` — события в `dpai.event` и состояние в `dpai.accounts-state` |
| `notifications` | `NotificationEngine`: ключ предмета, окно агрегации, эскалация, предел частоты и сводка, `resolve`; `DeepLink` — `dpai://вид/id`; `UserRequest`, `UserRequests` — запрос человеку, его автомат и проверка ответа, `RequestAction` — кнопка `dpai://answer/…` (D-44) |
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
| `linux` | Linux-среда в приложении (D-75, D-82): `ProotHost` — процессы в Alpine под proot (команда proot, окружение гостя, `apk` — без `/proc`); `NativeProcesses` и `NativeProcessHost` — процессы без `ProcessHandle` (остановка сеанса по `/proc`, `ProcStat`); реализация — JNI модуля `android` |
| `agent` | протокол `dpai.agent-reply`, `RunSpec`, `AgentClient`, `RunChannel` (номера и сверка), `MockAgentHost` |
| `claude` | `ClaudeRunner`, `ClaudeCodeCliRunner`, `MockRunner`, `ClaudeCodeCli` (команда), `ClaudeStreamParser` и `ClaudeTurnAssembler` (поток → итог хода) |

Сторона target — `orchestrator.agent.AgentRuntime` (журнал запуска через
`storage`), команда — `dpai agent` модуля `cli`. Дерево задачи для хода на
другом target — `orchestrator.agent.WorkspaceMcp` (`dpai agent mcp`), а
запрос такого хода — `orchestrator.RemoteWorkspace` (D-50;
`docs/EXECUTION_MODEL.md`, §7). Где живёт супервизор хода —
`orchestrator.agent.Supervision`: на target — отдельный процесс ОС
(`ProcessSupervision`), в приложении — поток (`ThreadSupervision`).
Target «в приложении» (`IN_APP`, D-75) — тот же агент в процессе
приложения: `orchestrator.agent.InAppAgentHost` — `ProcessHost`, который
исполняет вызовы `dpai agent …` без SSH; разбор параметров и ответы —
`AgentCommands`, общие с `dpai agent` (`docs/EXECUTION_MODEL.md`, §8).
Корневую систему Linux-среды в приложении распаковывает
`orchestrator.linux.RootfsInstaller` через `Vfs` (D-24; символьные ссылки
и права — `Vfs.symlink`, `Vfs.setMode`); proot, его загрузчик и minirootfs
Alpine кладёт в APK сборка модуля `android` (`docs/EXECUTION_MODEL.md`, §8).

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

Модуль `tasks` (этап 7; `docs/SCHEDULER.md`, §7): `Tasks` (решения и
план назначений), `TaskCodec`; проекция журнала — `orchestrator.tasks`.

Модуль `contexts` (этап 8; `docs/CONTEXT_MODEL.md`, §7):
`ContextAssembler`, `PromptRenderer`, `Handoff`, `Compaction`.

Модуль `git` (этап 9): `Worktrees` (реестр, владелец, события),
`OverlapPolicy` (ТЗ, §31), `GitCommands` (безопасные команды, одобрения
`security.ApprovalLedger`), `GitStatus` (разбор porcelain v2). Исполнение —
`orchestrator.git.GitService` через `ProcessHost`.

Модуль `verification` (этап 10; `docs/SCHEDULER.md`, §8): `Verifier`,
профили, `VerificationReport`; поток завершения —
`orchestrator.verification.CompletionFlow`.

Модуль `monitoring` (этап 11): `Dashboard` (сводка ТЗ, §38; `STALE` по
свежести, ТЗ, §122), `Timeline` (ТЗ, §40), `SelfDiagnostics` (ТЗ, §79,
§80: что разрешено в деградации). Модуль `ui`: `Screens` — состояния
экранов (сводка, экран сессии, доска задач с уровнями DAG) и смысл цвета
`Tone`; `AppPalette` — смысловые цвета всего приложения и запасная схема
Android 11 (`DPmetaApp:D-74`); `LinkTaps` — касание внешней ссылки во всём
приложении (`DPmetaApp:D-75`); `AiTab` — вкладки «AI» (D-112); `DashboardText` — те же экраны текстом для `cli`; `DemoSystem` —
настоящий `Orchestrator` на `MockRunner` для показа и замеров;
`LiveSystem` — контроллер приложения (D-35) над targets из
`dpai.app-targets`: слот на target, `SlotAuthProbe`, `RoutingRunner`;
с проектом (`dpai.app-project`) — репозиторий на target, `GitService`
поверх SSH-хоста (`git -C`), проверка через агента (`AgentStepRunner`),
слияние по одобрению.
Обе — `ControlView`: оболочка рисует любую.

Модуль `android` (этап 11, D-34): Jetpack Compose рисует снимок `ui`;
шаг системы — вне главного потока. Сторонние библиотеки во время
выполнения (Compose, AndroidX) разрешены только ему (D-17,
`check_modules.py`).

Одобрение слияния — запрос человеку `task.merge` (D-49): ответ только на
ждущий запрос с тем же хешем; ссылки `dpai://approve|deny`
(`notifications.ApprovalAction`) больше не создаются.

Ревью задачи (D-73) — `orchestrator.review.Review`: prompt роли, схема
ответа, разбор находок и выжимка для повтора; запуск, итог и запрос
человеку `task.review` — в `Orchestrator` после проверки и коммита.

Запросы человеку (D-44): модель и автомат — `notifications.UserRequest`,
`UserRequests`; журнал и снимок — `orchestrator.requests.UserRequestsProjection`;
назначения и их действия — `orchestrator.requests.RequestPurpose` и
`Orchestrator.answer`. Оболочка Android показывает ждущие запросы по
состоянию, а не по потоку уведомлений (`RequestPresenter`: уведомление,
поле ввода, окно поверх приложений `RequestOverlay`), ответ — тот же
`answer` с любого канала. Опасный ответ окна поверх приложений при нужном
входе приложения (`DpaiApplication.loginNeeded`, D-104) — подтверждением
на экране, как у уведомления.

Команды (D-42, п. 1): `orchestrator.commands.Command` — действие
человека или оболочки; `CommandInbox` — очередь с одним писателем:
кладёт любой поток (`Orchestrator.submit`), исполняет поток такта
(`runCommands`) в начале такта и между вызовами target, вне пачки.
Ключ идемпотентности — `OperationsProjection` (`OPERATION_RECORDED`).
Оболочка Android не держит замок на действие: команда исполняется тактом
или, если такт не идёт, самим вызывающим; экран при занятом такте рисует
последний опубликованный снимок. Журнал приложения (D-43) получает
события контура через `Orchestrator(onEvents = …)` и `EventLog`.

Восстановление (этап 13; `docs/RECOVERY.md`, §7): `orchestrator.recovery`
— `Reconciler`, `OrphanScan`, `WorktreeProbe`.

Контур (этап 14): `orchestrator.Orchestrator` — такт: очередь → план
назначений → аренда слота → сессия → рабочее дерево → контекст и prompt →
запуск; опрос сессий и разбор исходов: проверка → коммит → `DONE` →
одобрение слияния; прерванный ход → handoff → продолжение той же сессии
провайдера; упавший — повтор по классу. Сценарии V1 1–4 —
`tests/integration`, `V1ScenariosTest`.

Экосистема (этапы 15, 16; `docs/ECOSYSTEM.md`): `ecosystem` —
`EcosystemProvider`, `ManifestEcosystem`; `connectors` — `GitHubCommands`;
`orchestrator.ecosystem.EcosystemRun` — глобальный прогон с ограничителями.

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

Ручные профили рабочего проекта (D-134): `ui.ManualProfiles` хранит
отдельные настройки и неизменяемый якорь; `android.DemoModel` под общим
замком проверяет свободный контур/черновики и резервирует его. Worker
LongJobs со службой опрашивает закреплённый target вне этого замка.
`externalPause` в LiveSystem/Orchestrator останавливает tick и исполнение
команд; якорь читается до построения контура. Сетевой отказ не освобождает
проект, новая система с другими настройками видит ту же общую паузу.

Восстановление редактора (D-135): `ui.EditorDraftStore` хранит ограниченный
снимок в зашифрованном конверте DocumentStore, без доступа к FileTree.
Android `Drafts` объединяет изменения и сериализует IO/удаление копий;
`DraftEncryption` держит отдельный ключ в Keystore. Восстановление идёт
до разрешения нового редактирования/ручных профилей. Сохранение исходника
остаётся в TextFileEdit и прежних проверках владельца/исходных байтов.

Языковые службы (D-137): `lsp.LspClient` говорит со службой по потокам
процесса-собеседника `execution.StreamHost`; текст открытого документа и
его версию помнит клиент, изменение уходит одной правкой
`TextEdits.delta`. Диагностика приходит слушателю из потока чтения,
запросы блокируют вызывающий поток до ответа или тайм-аута. Правки службы
(`TextEdit`, `WorkspaceEdit`) применяет редактор через `TextEdits.apply`;
запись в файл — только его «Сохранить». В приложении
`android.CodeService` держит один clangd на проект, пока открыт хотя бы
один его файл: проект ищется от файла вверх (`Clangd.locateCommand`),
служба идёт в госте через `LinuxSetup.guestStreams`; `CodeAssist` шлёт
текст после паузы ввода, показывает замечания, полоску дополнения,
сведения, переход и форматирование, `StudioCodeField.mark` подчёркивает
отрезки `Assist.marks`.

Нативное поле редактора (D-136): `android.StudioCodeField` адаптирует Sora
0.24.7 к снимкам/undo `ui.CodeEditorBuffer`; `ui.EditorActions` выполняет
атомарные команды, `EditorTabs` хранит только пути. `EditorLanguages`
загружает локальные TextMate-грамматики/темы. Языковых серверов и сетевого
дополнения в поле пока нет. Сохранение обновляет baseline буфера, сохраняя
undo; закрытие использует identity буфера и готовность восстановления.

Представление IDE (D-138): `ui.EditorSessionBook` — метаданные вкладок,
настроек и команды, без кода. `android.IdeState` сериализует их IO в
noBackupFilesDir; `IdeChrome`/`IdeWindow` ограничивают скрытие панелей
временем жизни поля. Режим просмотра не удаляет CodeEditorBuffer.
`ui.EditorTerminalRun` строит argv с путями как данными, а TerminalActivity
открывает отдельную PTY без автоматического повтора при восстановлении.

Зависимости запуска (D-139): ui.EditorRunOutput держит кольцевой хвост
128 КиБ только первой команды IDE; CppDependencies сопоставляет
Clang/GCC/linker с каталогом Alpine, не превращая чужой вывод в пакеты.
TerminalHub.EditorRun принадлежит своей PTY-вкладке; EditorDependencyUi
показывает предложение и подтверждение. Установка — PultCommands.install
и LongJobs.LINUX_APK с общими исключениями гостя, без ввода в PTY.
Шаблон команды сохраняется отдельно по выбору человека через IdeState;
ни установка, ни восстановление экрана не повторяют программу.

Готовность C++ (D-140): CppToolchain — начальная команда/проба; CppSetup
запускает штатную компиляцию и проверяет запись без изменения проекта.
AlpinePackageState учитывает виртуальные provides. Api FIX требует
конкретные пакеты, ProotHost запускает apk без /proc для add и fix.

Языки запуска (D-141): EditorRunOutput возвращает RunDiagnosis по пути
файла, C++ использует прежний CppDependencies. PythonRuntime/KotlinRuntime
формируют команды/диагнозы без Android, ProjectRuntime определяет приватный
каталог окружения. RuntimeSetup выполняет подтверждённые планы через
LinuxSetup/LongJobs; EditorDependencyUi показывает действия и сохраняет
команду выбранного языка через EditorRootSession.withCommand. Метаданные
v2 читают v1; текст по-прежнему хранится отдельно в EditorDraftStore.
SDK проверяется до распаковки; resolver пишет отдельный снимок и меняет
current только после успеха. Шаблон JVM использует manifest Class-Path.
