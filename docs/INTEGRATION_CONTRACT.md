# Integration Contract DP_AI_Orcestrator

Контракт интеграции DP_AI_Orcestrator в будущий DPmetaApp (ТЗ, §146–§148,
§157, п. 19; критерий этапа 20 — `docs/direction/ROADMAP/20-v1-freeze.md`).
Разделы — поля минимальной структуры Migration Contract DPmetaApp
(`DPmetaApp:MESTER_TECHNICAL_TASK_ROADMAP.md`, раздел 8); перенос — по
правилу Semantic Parity (там же, раздел 9) и правилам нативной интеграции
(раздел 41). Текст и код DPmetaApp сюда не переносятся — только ссылки на
структуру (D-16).

Контракт фиксирует, что и в каком смысле позже войдёт в DPmetaApp; перенос
кода он не начинает (ТЗ, §157, п. 20). Срез — 8 октября 2026, до заморозки
V1. Всё ниже — по коду и документам репозитория; при расхождении правы
`docs/direction/DECISIONS.md` и код, а этот документ исправляется в том же
изменении (`docs/PROCESS.md`, §1).

## 1. Проект (Project)

| Поле | Значение |
|---|---|
| Имя | `DP_AI_Orcestrator` — как создал автор; в коде — `dpai`: пакет `com.dpsmpx.dpai`, команда `dpai`, ссылки `dpai://` (D-01) |
| Вид | control plane для сессий Claude Code: модули на чистом Kotlin/JVM и две оболочки — `cli` и `android` (D-02) |
| Лицензия | Apache-2.0 (`LICENSE`); репозиторий публичный (D-16) |
| Место в экосистеме | первый специализированный control plane; в DPmetaApp — подсистема «AI» над сессиями Claude (ТЗ, §156) |
| Ссылки извне | `DP_AI_Orcestrator:D-NN`, `DP_AI_Orcestrator:путь` (шапка журнала решений) |

## 2. Назначение (Purpose)

Превращает сессии Claude, подписки, execution targets, репозитории и задачи
в одну устойчивую систему: задача → слот и target → рабочее дерево →
контекст → сессия → события → проверка → `DONE` → продолжение в другой
сессии (ТЗ, §156; `docs/ARCHITECTURE.md`, §3.1). Сессия — не состояние, а
заменяемый исполнитель (ТЗ, §150; D-01). Объём V1 — пять сценариев
(`docs/direction/V1_SCOPE.md`, §1).

Не делает: не launcher и не WebView, не автоматизирует интерфейс Claude, не
обходит лимиты и не маскирует аккаунты (D-01, D-04), не хранит вход
claude.ai (D-27), не принимает решений планировщика моделью (D-22).

## 3. Канонический источник (Canonical Source)

- Репозиторий `github.com/dpsmpx/DP_AI_Orcestrator`, ветка `main`; слияние в
  неё — только после зелёного `tools/verify/run.sh full` на последнем
  коммите (`docs/PROCESS.md`, §5).
- Порядок источников истины — `CLAUDE.md`, «Источник истины»: решения →
  правила → ТЗ → план → `TODO.md` → документы устройства.
- Машинно проверяемые контракты: реестры `tools/verify/registry/diag_codes.txt`
  и `tools/verify/registry/event_types.txt`; таблица модулей
  `docs/ARCHITECTURE.md`, §2; форматы — `docs/DATA_MODEL.md`, §4;
  зависимости — `gradle/libs.versions.toml` и `docs/PROVENANCE.md`, §2.

## 4. Текущая версия (Current Version)

| Что | Версия | Где |
|---|---|---|
| Модули, jar, дистрибутив `dpai` | `0.1.0-dev.5` (сборка для проверки на устройстве — ветка `apk_debug`, каталог `dpai/`, D-67) | `gradle.properties`, `version`; в jar `cli` — `Implementation-Version`, его печатает `dpai version` |
| Приложение Android | `versionName` — версия проекта, `versionCode` — 4 | `android/build.gradle.kts` |
| Форматы хранения | свой номер у каждого (раздел 10) | `docs/DATA_MODEL.md`, §4 |
| Реестры | 116 кодов диагностики, 67 типов событий (8 октября 2026) | `tools/verify/registry/` |
| Решения | D-01…D-39, заменённых нет | `docs/direction/DECISIONS.md` |

V1 не заморожена: этапы 19 и 20 открыты (`README.md`, «Прогресс»).

## 5. Стабильная ревизия (Stable Revision)

Фиксируется при заморозке V1 (этап 20): метка выпуска на коммите `main`, на
котором прошли `tools/verify/run.sh full`, финальный аудит
`tools/audit/final_stability.py` с итогом ТЗ, §141 и чек-лист ТЗ, §158; к
метке прикладывается список хешей воспроизводимой сборки
`build/reproducible/release.sha256` (`docs/PROCESS.md`, §6). До неё
стабильной ревизии нет. Эталон паритета (раздел 23) — эта метка, а не
текущий `main`.

## 6. Граница публичного и внутреннего (Public/Internal Boundary)

Модификаторы Kotlin границу не задают: почти все типы `public`, явный режим
API не включён. Граница — этот раздел и реестры. Публичное меняется только
по правилу своего вида; внутреннее — свободно, пока проходят тесты
паритета (раздел 23).

### 6.1. Стабильный контракт

| Контракт | Где в коде | Правило изменения |
|---|---|---|
| Форматы `dpai.*` (22 вида, раздел 10) | константы `FORMAT` или `STATE_FORMAT` и номер версии в кодеках; заголовок — `FormatHeader` | новое поле или смысл — новая версия и явная миграция (D-09) |
| Коды диагностики | `core.diagnostics.DiagCode` | только добавлять, строкой в `tools/verify/registry/diag_codes.txt` (D-05; `check_contracts.py`) |
| Типы событий, виды потоков | `core.events.EventType`, `StreamKind` | только добавлять; типы — строкой в `tools/verify/registry/event_types.txt` (D-12) |
| Идентификаторы | `core.ids`: `<префикс>_<26 знаков Crockford base32>`, префиксы — `docs/DATA_MODEL.md`, §1 | префикс вида не меняется (D-08) |
| Канонический JSON и хеш | `CanonicalJson`, `ContentHash` (`sha256:<64 hex>`) | не меняется: на нём цепочка журнала, снимки, одобрения, версии контекста и handoff (D-09, D-10) |
| Автоматы состояний | `core.state`: `SessionLifecycle`, `TaskLifecycle`, `AccountLifecycle`, `TargetLifecycle`, `CircuitLifecycle`; оценка `HeartbeatFreshness` | только новым решением (D-11, D-23) |
| Протокол агента target | команда `dpai agent` (раздел 11.2); `dpai.agent-reply` v2, `dpai.run-spec` v1, `dpai.agent-run` v2 | новая версия формата; версии агента и контроллера сверяет `TargetDoctor` (расхождение — `WARN`) |
| Ссылки | `dpai://<вид>/<id>`: `task`, `session`, `project`, `account`, `subscription`, `event` (`notifications.DeepLink`); `dpai://approve\|deny/<apr_…>?hash=sha256:…` (`notifications.ApprovalAction`) | вход из уведомлений (ТЗ, §76); изменение — новым решением |
| Манифест экосистемы | `dpai.ecosystem` v1 (`docs/ECOSYSTEM.md`, §2) | внешний контракт с DPmeta; чтение строгое |

### 6.2. API библиотеки — стабилен по смыслу

Что DPmetaApp переиспользует как библиотеку (раздел 21). До заморозки V1
бинарная совместимость не обещается, смысл — обещается: он не меняется
молча (D-05).

| Слой | Типы |
|---|---|
| Результат и диагностика | `Outcome`, `OperationResult`, `Provenance`, `Actor`, `Diagnostic`, `DiagCode`, `Severity`, `Category`, `Retryability`, `Secret`, `Redactor` |
| Основа | `TypedId`, `IdKinds`, `Clock`, `ManualClock`, `Timestamp`, `RandomSource`, `ContentHash`, `Hashing`, `JsonValue`, `JsonParser`, `CanonicalJson`, `FormatHeader`, `DiagnosticJson`, `TransitionTable`, `Measure` |
| События | `EventEnvelope`, `EventType`, `StreamRef`, `StreamKind`, `SequenceTracker` |
| Точки расширения (интерфейсы) | `ProcessHost` и `RunningProcess` (`execution.process`), `ClaudeRunner` (`execution.claude`), `EcosystemProvider` (`ecosystem`), `Vfs` (`storage.vfs`), `Projection` (`storage.snapshot`), `StepRunner` (`verification`), `ControlView` (`ui`) |
| Решения подсистем | чистые функции над состоянием, итог — события: `Accounts`, `Sessions`, `Tasks`, `Worktrees`; события `AccountEvent`, `SessionEvent`, `TaskEvent`, `WorktreeEvent`, `MergeEvent`; кодеки `AccountCodec`, `SessionCodec`, `TaskCodec`, `WorktreeCodec`, `ContextCodec` |
| Модели | `ExecutionTarget` (данные, не интерфейс), `SshEndpoint`, `ClaudeRequest`, `ClaudeTurn`, `RunnerUpdate`, `ContextBundle`, `Handoff`, `Proposal`, `Approval`, `VerificationProfile`, `VerificationReport` |
| Хранилище | `EventJournal`, `DocumentStore`, `SnapshotStore`, `StateRecovery`, `Migration`, `Migrator`, `BackupService`, `NioVfs` |
| Сборка системы | `Orchestrator`, `ProjectConfig`, `TargetSlot`, `EcosystemRun`, `RunPolicy`, `Invariants` |

### 6.3. Внутреннее

Меняется без объявления:

- разбор и сборка вызова провайдера: `ClaudeStreamParser`,
  `ClaudeTurnAssembler`, `ClaudeCodeCli`; кавычки и оболочки: `RemoteShell`,
  `ArgumentEncoding`; процессы: `Detacher`, `ProcessTree`;
- устройство агента (`AgentRuntime`; раскладка каталога `runs/` — кроме
  форматов файлов), `EventRecorder`, проекции журнала (`AccountsProjection`,
  `SessionsProjection`, `TasksProjection`, `WorktreesProjection`,
  `StreamsProjection`, `MergesProjection`);
- изменяемые поля `Orchestrator` (`problems`, `posted`) и пороги политик по
  умолчанию (`SchedulerPolicy`, `AccountPolicy`, `SessionPolicy`,
  `TargetHealthPolicy`, `NotificationPolicy`, `RunPolicy`);
- представление: `Screens`, `DashboardText`, `DemoSystem`, `LiveSystem` и
  вся оболочка `android`;
- `testkit`, `tools/verify/`, `tools/audit/` — средства процесса, не продукта.

## 7. Основные понятия (Core Concepts)

| Понятие | Смысл | Где |
|---|---|---|
| Задача (`TaskId`, `tsk`) | цель, приоритет, зависимости (DAG), политики; автомат `TaskLifecycle`; в `DONE` — только после `VERIFIED` или записанного исключения (D-23) | `docs/SCHEDULER.md` |
| Подписка (`SubscriptionId`, `sub`) и слот (`AccountId`, `acc`; `AIAccount` ТЗ, §7) | подписка — аккаунт Claude с общими пределами (`SubscriptionState`); слот — подписка, вошедшая в Claude Code на target в своём `CLAUDE_CONFIG_DIR` (`SlotState`; D-27, D-30) | `docs/ACCOUNT_MODEL.md` |
| Execution target (`tgt`) | где работает Claude: `LOCAL`, `TERMUX_LOCAL`, `LINUX_SSH`, `WINDOWS_REMOTE`, `MOCK`; транспорт — SSH (D-26) | `docs/EXECUTION_MODEL.md` |
| Агент target | `dpai agent`: запускает ход отделённым супервизором, пишет журнал запуска с номерами, переживает обрыв SSH (D-31) | `docs/EXECUTION_MODEL.md`, §1.3 |
| Сессия (`SessionId`, `ses`) | временный исполнитель: задача + слот + target + рабочее дерево + процесс Claude; автомат `SessionLifecycle`; `ProviderSessionId` — отдельный тип | `docs/SESSION_MODEL.md` |
| Ход | один процесс `claude -p --verbose --output-format stream-json`; продолжение — `--resume` (D-32) | `docs/EXECUTION_MODEL.md`, §3 |
| Рабочее дерево (`wtr`) | изолированный git worktree задачи с одним владельцем, ветка `task/<id>` | `docs/RECOVERY.md`, §7 |
| Контекст (`ctx`) | `ContextBundle`: версия, хеш, источники с уровнем секретности и происхождением | `docs/CONTEXT_MODEL.md` |
| Handoff (`hnd`) | неизменяемая хешированная передача работы следующей сессии (ТЗ, §27) | `docs/CONTEXT_MODEL.md`, §5 |
| Событие (`evt`) | `dpai.event`: поток, номер, время, корреляция; журнал — источник истины, состояние — свёртка (D-12, D-24) | `docs/DATA_MODEL.md`, §4 |
| Одобрение (`apr`) | разрешение на хеш предложения: одноразовое, со сроком (D-10) | `docs/SECURITY_MODEL.md`, §3 |
| Проверка | профиль `FAST`/`NORMAL`/`FULL` задаёт пользователь; вердикт `PASSED`/`FAILED`/`INCOMPLETE` | `docs/SCHEDULER.md`, §8 |
| `Measure` | значение с источником (`OBSERVED`, `DERIVED`, `ESTIMATED`) или `Unknown` с причиной (D-13) | `core.measure` |
| Экосистема | необязательный `EcosystemProvider` (ТЗ, §148) | `docs/ECOSYSTEM.md` |

## 8. Вход (Input)

| Источник | Что | Как принимается |
|---|---|---|
| Пользователь | задачи (заголовок, цель, приоритет, зависимости, возможности, область путей), targets, проект, профили проверки, ответы на одобрения | формы с проверкой (`TargetForm`, `ProjectForm`); ключ хоста — после сверки отпечатка человеком (`HostKeyProbe`, T2) |
| Агент target | `dpai.agent-reply` v2 (без `more` — v1): состояние запуска, события с номерами, отчёт `doctor` | строгое чтение (D-09); номера — `RunChannel` и `SequenceTracker`, пропуск — сверка |
| Claude Code | stream-json хода (`system`, `assistant`, `rate_limit_event`, `result`); `claude auth status --json` | `ClaudeStreamParser` → `ClaudeTurn`: состояние — из машинных полей, не из текста (D-23, D-32); вход — `AuthStatus` |
| Git на target | `git status --porcelain=v2`, коды выхода | `GitStatus`; команды строит только `GitCommands` |
| Шаги проверки | код выхода, хеш вывода, длительность | `Verifier` → `VerificationReport` |
| DPmeta | манифест `dpai.ecosystem` v1 | `ManifestEcosystem`, строго; недоступен — `ECOSYSTEM_UNAVAILABLE` |
| GitHub | вывод `gh` | `GitHubCommands`; непонятный ответ — `CONNECTOR_REPLY_INVALID` |
| Время, случайность | `Clock`, `RandomSource` | только через них (D-07) |

Всё внешнее — недоверенный вход: вывод процессов и ответы Claude проходят
`Redactor` до журнала и диагностики (D-14), содержимое репозиториев
попадает в prompt блоками данных с пометкой происхождения (ТЗ, §64; T3).

## 9. Выход (Output)

| Что | Форма | Кому |
|---|---|---|
| События | `dpai.event` v1 в журнале `dpai.journal-record` | проекции, мониторинг, DPmetaApp (раздел 12) |
| Производное состояние | снимки `dpai.*-state` | восстановление, экраны |
| Действия на target | ход Claude (`dpai.run-spec` агенту), команды `git -C`, шаги проверки; команды `gh` (`gh pr create`, `gh pr view`) строит `GitHubCommands` — контур их пока не вызывает | target, репозиторий, GitHub |
| Результат задачи | коммит в ветке задачи, `DONE`; слияние в ветку интеграции — после одобрения | репозиторий пользователя |
| Отчёт проверки | `VerificationReport` (`dpai.verification-report` v1, текст — `render`); в журнале — вердикт и хеш отчёта | задача, аудит |
| Итог глобального прогона | документ `dpai.ecosystem-results` v1 | пользователь, DPmeta |
| Уведомления | `NotificationDecision` (`Post`, `Update`, `Held`), сводка — `flush`; категория и ссылка `dpai://` | оболочка (`Notifier`) |
| Экраны | `DashboardScreen`, `TaskBoard`, `SessionScreen` через `ControlView`; текстом — `DashboardText` | оболочка |
| Диагностика | `Diagnostic` с кодом и причинами; `SelfDiagnosis` | пользователь, журнал |
| Резервная копия | файлы данных с манифестом `dpai.backup-manifest` v1 | восстановление |
| Агент | одна строка `dpai.agent-reply` на команду; коды выхода 0, 1, 2 | контроллер |

## 10. Сохраняемое состояние (Persistent State)

Принципы (D-24, D-25, D-37):

- источник истины — журнал событий: строки канонического JSON
  `dpai.journal-record` с цепочкой хешей; запись подтверждается после fsync;
  снимки и любые индексы — производные, пересчитываются из журнала;
- документ — атомарная замена: `.tmp` → fsync → переименование; прежняя
  целая версия — `.prev` (last known good); конверт `dpai.stored` с
  `sha256` содержимого;
- журнал контроллера — сегментами по 20 000 записей: активный файл и
  `<журнал>.seg-<номер первой записи>`, номера и цепочка сквозные; журналы
  запусков агента — без сегментов;
- снимки — раз в 20 000 записей, шесть проекций: аккаунты, сессии, задачи,
  деревья, последние номера потоков, слияния; после перезапуска — снимок,
  если он сходится с журналом по позиции и хешу записи, плюс хвост;
- секретов в хранилище нет: копия — файлы как есть (D-25); вход claude.ai —
  у Claude Code на target (D-27); закрытый ключ SSH приложения — в Android
  Keystore;
- файлы пишет только `storage` через `Vfs` (`check_forbidden_apis.py`,
  правило `file-write`).

Форматы (поля — `docs/DATA_MODEL.md`, §4; в коде — `FORMAT`/`STATE_FORMAT`
и номер версии в указанном типе):

| Формат | Версия (читается) | Тип | Где лежит |
|---|---|---|---|
| `dpai.event` | 1 | `EventEnvelope` | внутри записи журнала |
| `dpai.journal-record` | 1 | `EventJournal` | журнал контроллера `live.journal` и его сегменты; журнал запуска агента `events.jsonl` |
| `dpai.stored` | 1 | `DocumentStore` | конверт каждого документа |
| `dpai.snapshot` | 1 | `SnapshotStore` | документ `snapshot-<проекция>` в `snapshots/` |
| `dpai.accounts-state` | 1 | `AccountCodec` | снимок проекции аккаунтов |
| `dpai.sessions-state` | 1 | `SessionCodec` | снимок проекции сессий |
| `dpai.tasks-state` | 2 (1–2) | `TaskCodec` | снимок проекции задач; v1 — без `scope` |
| `dpai.worktrees-state` | 1 | `WorktreeCodec` | снимок репозиториев и деревьев |
| `dpai.streams-state` | 1 | `StreamsProjection` | снимок последних номеров потоков |
| `dpai.merges-state` | 2 | `MergesProjection` | снимок ожидающих и выполненных слияний и коммитов задач |
| `dpai.context` | 2 (только 2) | `ContextBundle`, `ContextCodec` | документ `context-<задача>` контура |
| `dpai.handoff` | 1 | `Handoff`, `ContextCodec` | документ `handoff-<задача>` контура |
| `dpai.verification-report` | 1 | `VerificationReport` | только пишется; контур хранит вердикт и хеш |
| `dpai.ecosystem` | 1 | `ManifestEcosystem` | файл DPmeta или пользователя; только читается |
| `dpai.ecosystem-results` | 1 | `EcosystemRun` | документ `ecosystem-results` |
| `dpai.backup-manifest` | 1 | `BackupService` | `manifest.json` копии |
| `dpai.restore-marker` | 1 | `BackupService` | `restore.pending` — начатое восстановление |
| `dpai.app-targets` | 2 (1–2) | `TargetBook` | документ `targets` приложения; v1 — без `configDir` |
| `dpai.app-project` | 2 (1–2) | `ProjectBook` | документ `project` приложения; v1 — без маршрута `claude` (D-50) |
| `dpai.agent-reply` | 2 | `AgentReply` | стандартный вывод агента; не хранится |
| `dpai.run-spec` | 1 | `RunSpec` | стандартный вход `dpai agent start --spec-stdin`; внутри `dpai.agent-run` |
| `dpai.agent-run` | 2 (только 2) | `AgentRuntime` | `runs/<SessionId>/run` на target |
| `dpai.agent-pruned` | 1 | `AgentRuntime` | `runs/<SessionId>/pruned` на target — надгробие запуска, удалённого по сроку |

Раскладка. Контроллер приложения (D-35) — `filesDir/controller/`:
`docs/` (`targets`, `project`), `known_hosts`, `app_key.pub` (открытый
ключ), `live/` — `live.journal` с сегментами, `snapshots/`, `docs/`
(контексты и handoff задач). Агент — `~/.dpai-agent/runs/<SessionId>/`:
`run`, `events.jsonl`, `stop-request`; корень меняет `--home`.

Миграции (D-09, D-12):

- формат не тот — `FORMAT_MISMATCH`; версия новее — `FORMAT_VERSION_NEWER`
  («создано более новой версией»); старее поддерживаемой —
  `FORMAT_VERSION_UNSUPPORTED`; неизвестное поле — `FIELD_UNKNOWN`;
- миграция — только между соседними версиями, чистой функцией (`Migration`,
  `Migrator`); отказ шага — `MIGRATION_FAILED`, документ не меняется;
  применённая — `MIGRATION_APPLIED`; чтение с миграцией —
  `DocumentStore.readMigrated`;
- прежнюю версию напрямую читают `dpai.tasks-state`, `dpai.app-targets` и
  `dpai.app-project`;
  v1 `dpai.context` и `dpai.agent-run` заменены до выпуска и не читаются;
- сегменты не изменили формат строки журнала (D-37).

## 11. Команды (Commands)

### 11.1. Команды оркестратора

По D-06 команда, видимая пользователю и журналу, возвращает
`OperationResult<T>`: статус выводится из `Outcome`, плюс `operationId`,
время и `Provenance`. На срезе тип есть и проверен (`OperationResultTest`),
но API контура возвращает `Outcome` или значения; обёртка на границе
команд — долг (раздел 19) и требование к слою команд DPmetaApp (раздел 22).

| Команда | Где | Итог |
|---|---|---|
| создать задачу | `Orchestrator.addTask`, в приложении — `LiveSystem.addTask` | `Outcome<TaskId>`; `TASK_CREATED`, `TASK_SCOPE_CHANGED` (если область задана), `TASK_STATE_CHANGED` (в очередь) |
| такт контура | `Orchestrator.tick`, в оболочке — `ControlView.step` | очередь → план → аренда слота → сессия → дерево → контекст → ход; опрос; проверка → коммит → ревью по политике (D-59) → `DONE` → запрос слияния |
| ответ на запрос (в том числе слияние, D-49) | `Orchestrator.answer(uri, text, by)` | `OperationResult<RequestAnswer>`; действует только ответ на ждущий запрос с тем же хешем |
| сверка после перезапуска | `Orchestrator.recover(agentFor)` | `Reconciler.Result` по сессиям, затем `OrphanScan` |
| факты аккаунтов | `Orchestrator.commitAccounts`; проба входа — `LiveSystem.probeSlot` (`SlotAuthProbe`) | события аккаунтов |
| чтение | `dashboard`, `invariants`, `lastContext`, `eventCount`; поиск по истории — `EventSearch` | без изменений |
| глобальный прогон | `EcosystemRun.inspect`, `selectNext`, `step`, `run` с `RunPolicy` | `RunRecord`, `dpai.ecosystem-results` |
| явные операции автоматов | `Tasks.explicit` (`FAILED` → `QUEUED`, `CANCELLED` → `BACKLOG`, `DONE` → `BACKLOG`); продолжение — новая сессия через `Sessions.create` (D-11) | события задачи и сессии |

Решения подсистем (`Accounts.lease`, `Sessions.create`, `Tasks.plan`,
`Tasks.recordVerification`, `Tasks.complete`…) возвращают события и не
меняют состояние сами (D-30); команда контура — решения, запись событий,
свёртка.

### 11.2. Команда `dpai`

| Вызов | Что |
|---|---|
| `dpai version` (`--version`) | версия (`Implementation-Version`) |
| `dpai help` (`--help`, `-h`) | справка; без команды или с неизвестной — код 2 |
| `dpai agent doctor [--home DIR]` | агент, ОС, Java, Termux, диск, память, `claude --version`, `git --version` (`docs/EXECUTION_MODEL.md`, §1.2) |
| `dpai agent start --run ID [--dir DIR] [--timeout-seconds N] [--env K=V]… [--unset K]… -- КОМАНДА…` | записать запуск `dpai.agent-run` и запустить отделённый супервизор |
| `dpai agent start --run ID --spec-stdin` | то же; что запускать — `dpai.run-spec` на стандартном входе |
| `dpai agent supervise --run ID` | супервизор — единственный писатель `events.jsonl` |
| `dpai agent status --run ID` | состояние запуска; супервизор умер без записи выхода — `LOST` |
| `dpai agent events --run ID [--after N] [--limit N]` | подтверждённые события с номером больше N |
| `dpai agent follow --run ID [--after N]` | то же потоком, пока идёт ход: строка `dpai.agent-reply` команды `follow` на каждую новость журнала, смену состояния и раз в 15 с без новостей (D-62, D-65) |
| `dpai agent stop --run ID [--grace-seconds N]` | просьба супервизору; потерянный — остановка по pid и времени старта; ход ещё без супервизора — только просьба (`AGENT_STOP_PENDING`) |
| `dpai agent mcp --root DIR [--shell bash\|sh\|powershell\|cmd]` | MCP-сервер рабочего дерева по stdio (JSON-RPC 2.0 строками; `initialize`, `ping`, `tools/list`, `tools/call`; инструменты `run`, `read`, `write`, `edit`, `glob`, `grep`) — для хода Claude на другом target (D-50; `docs/EXECUTION_MODEL.md`, §7) |

`ID` — `SessionId`. Каждая команда агента отвечает одной строкой
`dpai.agent-reply`, кроме `follow` — строка на каждую новость, пока идёт
ход, — и `mcp` — она отвечает строками JSON-RPC, пока открыт вход; коды выхода: 0 — успех, 1 — отказ (подробности в ответе), 2 —
неверный вызов. Режима контроллера без интерфейса в `cli` нет
(раздел 19).

### 11.3. Проверки репозитория

`tools/verify/run.sh static | affected | full | plan | report | doctor` —
раздел 18.

## 12. События (Events)

Модель — `dpai.event` v1 (`EventEnvelope`; поля — `docs/DATA_MODEL.md`, §4):
`id` (`EventId`), `type` (`EventType`), `stream` (`kind` и `id`), `sequence`
от 1 без пропусков в потоке, `occurredAt` и `recordedAt` (мс UTC),
необязательные `correlationId` и `causationId`, `payload` со схемой по типу.

- Виды потоков (`StreamKind`): `task`, `session`, `account`, `target`,
  `subscription`, `system`, `repository`, `worktree`; только дописываются.
- Типы — 67, `tools/verify/registry/event_types.txt`; группы `EventType`:
  задачи (8), сессии (14), проверка (4), handoff и продолжение (3),
  аккаунты и targets (19), targets и процессы на них (6), Git, деревья и слияния
  (13). Только дописываются (D-12; `check_contracts.py`).
- Номер выдаёт `EventRecorder` по потоку; после перезапуска нумерация
  продолжается из `dpai.streams-state` (D-37).
- Приём — `SequenceTracker` (ТЗ, §56; `docs/ARCHITECTURE.md`, §3.2):
  следующий — `Accepted`; тот же id — `Duplicate`, ничего не меняется;
  дальше ожидаемого — `Gap` (`EVENT_SEQUENCE_GAP`): не применять до
  заполнения; тот же номер с другим событием — `Conflict`
  (`EVENT_SEQUENCE_CONFLICT`, далее `STATE_CONFLICT`). Итог не зависит от
  порядка доставки.
- События агента (`TARGET_PROCESS_*`) — в потоке сессии; контроллер
  забирает их `RunChannel` с номера после последнего принятого; пропуск,
  который не закрыла сверка, — `RUN_RECONCILIATION_REQUIRED`.
- Повтор события узнаётся в пределах активного сегмента журнала (D-37).
- Шины подписки в памяти нет: событие → журнал → проекции → уведомления
  (`NotificationEngine`) и экраны; поиск по истории — `EventSearch`.

## 13. Ошибки (Errors)

- Ожидаемый отказ — значение, а не исключение (D-05): шаг возвращает
  `Outcome<T>` (`Success` с предупреждениями или `Failure` с ошибками),
  команда — `OperationResult<T>` (D-06).
- `Diagnostic`: `code`, `message` (по-русски, без секретов — D-14, D-18),
  `subject` (путь или сущность), `severity` (`INFO`, `WARNING`, `ERROR`),
  `causes` (цепочка, ТЗ, §5.1), `error` (сводка исключения без стека).
  Между процессами — `DiagnosticJson`.
- `DiagCode` несёт категорию и класс повтора. 135 кодов
  (`tools/verify/registry/diag_codes.txt`; 22 октября 2026); по
  категориям: `STATE` 53, `EXECUTION` 28, `INPUT` 17, `INTEGRITY` 15,
  `FORMAT` 14, `NETWORK` 4, `STORAGE` 2, `UNSUPPORTED` 1, `INTERNAL` 1; по
  классу повтора: `NON_RETRYABLE` 105, `RETRYABLE` 20, `UNKNOWN` 10. Группы в коде: JSON,
  поля, форматы, ID, хеши, автоматы, события, исполнение, хранилище,
  аккаунты, targets и процессы, Claude Code CLI, сессии, задачи, контекст,
  одобрения и Git, проверка, восстановление, экосистема, общее.
- У обёртки с классом `UNKNOWN` класс выводится из причин: хоть одна
  неповторяемая — не повторять, все повторяемые — повторять
  (`Diagnostic.retryability`). Политика: `RETRYABLE` — пауза с удвоением до
  `maxRetries`, `UNKNOWN` — один повтор и `BLOCKED`, `NON_RETRYABLE` — без
  слепого повтора (`docs/SCHEDULER.md`, §5).
- Неподдержанное — `UNSUPPORTED`, а не «похожее» (D-05); неизвестное —
  `Measure.Unknown`, не ноль (D-13).
- Коды только добавляются (`check_contracts.py`): имя кода — ключ
  сохранённых событий, ожиданий тестов и корпусов.

## 14. Разрешения (Permissions)

### 14.1. Android

Манифест — `android/src/main/AndroidManifest.xml`:

| Разрешение | Зачем |
|---|---|
| `INTERNET` | SSH к targets из контроллера приложения (D-35) |
| `POST_NOTIFICATIONS` | уведомления; спрашивается при запуске на Android 13+, отказ — не ошибка (D-36) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | `ControllerService` типа `specialUse`: такт контроллера при закрытом экране (D-36) |
| `WAKE_LOCK` | частичный wakelock службы контроллера: такт идёт при выключенном экране (D-39) |

Других разрешений нет. Экспортируется только `MainActivity` (запуск);
`ApprovalReceiver` и `ControllerService` — `exported="false"`;
`allowBackup="false"`. `applicationId` — `com.dpsmpx.dpai`; minSdk 30,
targetSdk 36, compileSdk 37 (D-28, D-34).

### 14.2. Уровни операций и одобрения

Уровни `READ`, `SAFE_WRITE`, `SENSITIVE_WRITE`, `DESTRUCTIVE`,
`IRREVERSIBLE` — модель `docs/SECURITY_MODEL.md`, §3. В коде они выражены
местами, а не перечислением: разрушительные операции Git (`GitOp` с
`destructive`: `RESET_HARD`, `CLEAN_FORCE`, `PUSH_FORCE`,
`BRANCH_DELETE_FORCE`, `WORKTREE_REMOVE_FORCE`) строит только
`GitCommands` и только с одобрением `ApprovalLedger`; слияние в ветку
интеграции ждёт ответа на запрос `task.merge` (D-49). Одобрение — на хеш
канонического `Proposal` (действие, предмет, аргументы), одноразовое, со
сроком (D-10): нет — `APPROVAL_REQUIRED`; другое предложение, истёкшее или
повторное — `APPROVAL_INVALID`.

### 14.3. Прочие ограничители

- Глобальный прогон — `RunPolicy`: `allowedProjects`, `allowedOperations`,
  `confirm`, `maxConcurrency`, `maxTasks`, `maxTicks`; задача из предложения
  создаётся только с подтверждения (`docs/ECOSYSTEM.md`, §4).
- Деградация — `SelfDiagnostics`: какие `Capability` (`VIEW`, `SCHEDULE`,
  `GIT`, `NOTIFY`, `DESTRUCTIVE_WRITES`) разрешены при каком `SystemHealth`
  (ТЗ, §79, §80).
- Контекст — `ContextPolicy.allowSecret`, по умолчанию выключено.
- Профиль проверки задаёт пользователь, а не репозиторий (T4).
- Окружение процесса `claude` — разрешённым набором (`SlotEnvironment`,
  D-29).

## 15. Безопасность (Security)

| Правило | Решение | Проверка |
|---|---|---|
| Только официальные интерфейсы провайдера: Claude Code CLI (`-p`, `stream-json`, `--session-id`, `--resume`); нет cookies, приватных эндпоинтов, разбора протокола клиента, обхода лимитов и маскировки аккаунтов | D-04, D-32 | `check_forbidden_apis.py`, правило `provider-private` |
| Секрет — `Secret` (`toString` скрыт, `reveal()` — в разрешённых местах); свободный текст снаружи — через `Redactor` до журнала и диагностики | D-14 | `SecretTest`, `RedactorTest`, `DiagnosticTest`; правило `secret-reveal` |
| Копия без секретов: в хранилище их нет | D-25 | правило `storage-secret`; `StorageLeakTest`, `BackupTest` |
| Вход claude.ai не читается, не копируется, не хранится; `.credentials.json`, `setup-token`, `CLAUDE_CODE_OAUTH_TOKEN` в коде запрещены; `credentialRef` — путь к каталогу настроек | D-27 | правило `provider-private` |
| Вход только формой Claude (`authMethod = "claude.ai"`), иначе слот в `ERROR` (`ACCOUNT_AUTH_METHOD_FORBIDDEN`); перекрывающие вход переменные удаляются | D-29 | `AccountsTest`, `SlotEnvironmentTest` |
| Одобрение привязано к хешу предложения | D-10, D-49 | `UserRequestsFlowTest`, `V1ScenariosTest`, `GitLogicTest`, `GitServiceTest` |
| SSH — `BatchMode=yes`, `StrictHostKeyChecking=yes`; ключ хоста — только после сверки человеком; ключ приложения — ECDSA P-256 в Android Keystore | D-31, D-35 | `SshProcessHostTest`, `JschProcessHostTest`, `SshKeysTest` |

Угрозы `docs/SECURITY_MODEL.md`, §4: T1 — кража учётных данных, T2 —
перехват сессии, T3 — prompt injection, T4 — вредоносное содержимое
репозитория (коммит с `--no-verify`, пустой `core.hooksPath`,
`core.fsmonitor=false`), T5 — вредоносный скрипт, T6 — утечка между
проектами, T7 — путаница аккаунтов, T8 — секреты в журнале и отчётах,
T9 — утечка через копию, T10 — разрушительное без одобрения, T11 — цепочка
поставки, T12 — обход ограничений провайдера. Аудит этапа 18 —
`docs/audits/2026-10-07-security.md`: находки A-1…A-7 закрыты с тестами.

## 16. Зависимости (Dependencies)

Ядро во время выполнения — только Kotlin stdlib (D-17): JSON, хеши,
идентификаторы, property-тесты — свои. Версии — только в
`gradle/libs.versions.toml`, у каждой — строка `docs/PROVENANCE.md`, §2
(`check_dependencies.py`).

| Компонент | Версия | Лицензия | Куда попадает |
|---|---|---|---|
| Kotlin: stdlib, плагин, kotlin-test | 2.4.20 | Apache-2.0 | stdlib — во время выполнения; остальное — сборка и тесты |
| JUnit: BOM, Jupiter, Platform Launcher | 6.1.3 | EPL-2.0 | только тесты |
| Android Gradle Plugin | 9.4.1 | Apache-2.0 | сборка модуля `android` |
| Compose BOM (Material 3) | 2026.09.00 | Apache-2.0 | `android` во время выполнения (D-34) |
| Activity Compose | 1.13.0 | Apache-2.0 | `android` во время выполнения (D-34) |
| JSch, форк mwiede | 2.28.7 | BSD-3-Clause, ISC | `jsch` → `android` (D-35) |

Gradle 9.8.0 — через wrapper со сверкой SHA-256 дистрибутива и
`gradle-wrapper.jar` (`docs/PROVENANCE.md`, §3). Байт-код — Java 17,
инструменты — JDK 21. Сторонние библиотеки во время выполнения разрешены
только `android` и `jsch` (`check_modules.py`). Направления зависимостей
модулей — `docs/ARCHITECTURE.md`, §2 (D-21).

Внешние программы — не зависимости сборки, а инструменты на target,
вызываемые процессами: Claude Code CLI (на телефоне — в `proot-distro`
Alpine через `tools/termux/claude-proot.sh`, D-33), Java 17+ для агента,
`git`, OpenSSH (клиент `ssh` на JVM, `sshd` на targets), `gh`, команда
DP-Verify как шаг профиля проверки (D-16).

## 17. Владение данными (Data Ownership)

| Владелец | Данные | Что делает оркестратор |
|---|---|---|
| Оркестратор (контроллер) | журнал, снимки, документы контура (контекст, handoff, итоги прогона), реестры задач, сессий, слотов, деревьев, слияний; настройки приложения (`dpai.app-targets`, `dpai.app-project`), known_hosts приложения | источник истины (D-01, D-24): переживает смену сессии, аккаунта и перезапуск |
| Агент на target | `runs/<SessionId>/`: документ запуска, журнал `events.jsonl`, `stop-request` | пишет только супервизор (D-31); контроллер читает события по номерам |
| Claude Code на target | каталог слота `CLAUDE_CONFIG_DIR`: вход claude.ai, настройки, своя история сессий | не читает, не копирует, не хранит, не передаёт (D-27); знает только итог `claude auth status --json` — способ входа и отпечаток аккаунта (хеш `orgId` и email; сам email не хранится, D-30) |
| Anthropic | аккаунт, подписка, пределы | наблюдает пределы по ответам Claude Code и не обходит их (D-04) |
| Пользователь | профили проверки, одобрения, состав подписок и `rotationAllowed`, ключ выпуска | исполняет его политику; ключ выпуска в репозиторий не попадает (`docs/PROCESS.md`, §6) |
| DPmeta | содержимое манифеста `dpai.ecosystem` | только читает (D-16); без него работает (ТЗ, §148) |
| Git (репозиторий пользователя) | код, история, ветки | создаёт деревья и ветки `task/<id>`, коммитит в ветку задачи, сливает в ветку интеграции только по одобрению и только когда она выгружена в рабочей копии без изменений, неудавшееся слияние отменяет (`git merge --abort`; ревью A-54, A-68); после слияния или отказа удаляет дерево задачи только `git worktree remove` без `--force` — ветка задачи остаётся, дерево с неотслеживаемым остаётся тоже (ревью A-65); разрушительное — только с одобрением (T10) |
| GitHub | PR; вход — у `gh` пользователя | строит команды `gh`, токенов не хранит (D-14) |
| Android | Keystore с закрытым ключом SSH приложения | ключ не покидает устройство; у оркестратора — ссылка (T2) |

## 18. Проверка (Verification)

| Уровень | Команда | Что |
|---|---|---|
| static | `tools/verify/run.sh static` | секунды, без сборки: формат, ссылки, модули, запрещённые API, секреты, контракты, зависимости, раздел «Прогресс», самотесты проверок |
| affected | `tools/verify/run.sh affected` | static, сборка и тесты изменённых модулей и их потребителей |
| full | `tools/verify/run.sh full` | всё; пропуск недоступного шага — ошибка; собранный `dpai`; при Android SDK — сборка `android` и `check_android_api.py` |
| plan, report, doctor | `tools/verify/run.sh plan full`, `report`, `doctor` | что будет запущено; итог для PR; окружение проверок |

Подробно — `docs/PROCESS.md`, «Проверки». GitHub Actions не используется
(D-15).

- Финальный аудит (ТЗ, §99–§123; этап 19): после `full` —
  `python3 tools/audit/final_stability.py --endurance <отчёт>`. Пункты
  аудитов A–S сопоставлены с тестами; статус — из результатов JUnit
  (`PASS`, `FAIL`, `SKIPPED`, `DEVICE`, `N/A`, `GAP`); отчёт —
  `build/audits/final-stability/`. На 7 октября 2026: 72 пункта PASS, 4 — на
  устройстве, 2 — без прогона со свойствами.
- Инварианты ТЗ, §118 — `Invariants` после каждого такта сценариев и в
  конце нагрузки (`InvariantsTest`).
- Воспроизводимая сборка — `tools/verify/reproducible.sh`: две сборки с
  нуля, 23 артефакта совпали (7 октября 2026); хеши —
  `build/reproducible/release.sha256`.
- Внешний мир — только явно: `-Ddpai.test.ssh=…` (`AgentCommandTest`,
  `JschProcessHostTest`), `-Ddpai.test.claude=true` (`RealClaudeTest`),
  `-Ddpai.endurance.minutes=1440` (`EnduranceTest`); `docs/TESTING.md`, §1
  и §8.

## 19. Известные ограничения (Known Limitations)

| Ограничение | Где записано |
|---|---|
| V1 не заморожена: этапы 19 и 20 открыты, чек-лист ТЗ, §158 не закрыт | `docs/direction/COMPLETION_MATRIX.md` |
| Выносливость 24 ч и 48 ч (ТЗ, §116) не пройдена: первый прогон нашёл остановку контура (исправлено), 24 ч — идёт | `docs/direction/ROADMAP/19-stability-audit.md` |
| Не принято на устройстве: приёмка на Redmi Note 13 (этапы 11, 12, 17), уведомления в фоне и ограничения HyperOS, слоты в `CLAUDE_CONFIG_DIR` внутри proot, контроллер с ходом в proot, аудит жизненного цикла (ТЗ, §96, §97) | `TODO.md`, «На устройстве (Redmi Note 13)»; матрица |
| Две подписки на одном target параллельно — не замерено | `docs/direction/ROADMAP/05-accounts.md` |
| Отказ по пределу подписки в корпусе построен по догадке (`rate-limited.jsonl`) | D-32; `TODO.md`, «Известные долги» |
| Команды контура возвращают `Outcome`, а не `OperationResult` (D-06 — пока на уровне типа) | раздел 11.1 |
| `cli` — только `version`, `help` и `agent`: режима контроллера без интерфейса, `doctor` контроллера и сценариев на `MockRunner` в команде нет; контроллер — в приложении (D-35) | `cli/src/main/kotlin/com/dpsmpx/dpai/cli/Main.kt` |
| Тело `dpai.verification-report` контур не сохраняет: в журнале — вердикт и хеш | раздел 10 |
| Один проект на контроллер приложения | `dpai.app-project` |
| `GitHubCommands` строит команды `gh`, но контур их не вызывает; предохранитель `CircuitLifecycle` подключён только к здоровью targets | `docs/ECOSYSTEM.md`, §3; `docs/EXECUTION_MODEL.md`, §2 |
| Модуль `analytics` пуст: граница закреплена, кода нет | `docs/ARCHITECTURE.md`, §2 |
| Канал запуска — опрос агента; вывод процессов читается как UTF-8 (кодовая страница OEM на Windows искажается); `SequenceTracker` канала держит id событий запуска в памяти | `TODO.md`, «Известные долги» |
| Индекс для поиска не выбран (производное от журнала); экспорт секретов (ключей SSH) не сделан | `docs/direction/DECISIONS.md`, «Отложено» |
| Agent SDK и другие провайдеры не поддерживаются (D-32); интерфейса `AIProvider` (ТЗ, §52) в коде нет — абстракция провайдера сейчас `ClaudeRunner` | `docs/direction/V1_SCOPE.md`, §3 |
| FUTURE: локальная модель, задачи из состояния экосистемы без подтверждения, полный GitHub, облачная синхронизация, отдельный runner, коммерческая готовность | `docs/direction/V1_SCOPE.md`, §3 |

## 20. Интеграция с DPmeta (DPmeta Integration)

- DPmeta → оркестратор: манифест `dpai.ecosystem` v1 (`docs/ECOSYSTEM.md`,
  §2) — проекты (`key`, `name`, `repository`), `state` (свободные пары
  строк, поля решает источник), `documents`, `suggestions` (`key`, `title`,
  `objective`, `priority` — уровень `Priority`, `operation`). Читает
  `ManifestEcosystem`, строго (D-09); код DPmeta не переносится (D-16).
  Выгрузка манифеста из DPmeta — открытый вопрос к DPmeta (`TODO.md`).
- Оркестратор → DPmeta: итоги прогона `dpai.ecosystem-results` v1 (проект,
  предложение, задача, состояние, вердикт, хеш отчёта); события
  `dpai.event` — для состояния активности.
- Необязательность (ТЗ, §5.3, §148): манифеста нет — `EcosystemProvider.None`
  (`ECOSYSTEM_DISABLED`, не ошибка); недоступен или испорчен —
  `ECOSYSTEM_UNAVAILABLE`, ручные задачи идут
  (`EcosystemTest.withoutEcosystemTheOrchestratorStillWorks`).
- Документы проекта из манифеста попадают в контекст как данные с пометкой
  происхождения (`EcosystemSources`; T3), а не как указания.
- Ссылки между репозиториями — `Проект:номер` и `Проект:путь`
  (`DPmeta:docs/NAMING.md`); принципы DPmeta E-07 и E-10 приняты как D-15 и
  D-18.
- Остальное, что ТЗ, §5.3 называет у DPmeta (карта проектов, границы,
  контракты, риски, очередь синхронизации, решения), в V1 не читается;
  расширение — новой версией манифеста.

## 21. Интеграция с DPmetaApp (DPmetaApp Integration)

### 21.1. Подсистемы ТЗ, §146 → модули и типы

| Подсистема | Модуль и типы | Перенос |
|---|---|---|
| AI Account Manager | `accounts`: `Accounts`, `AccountsState`, `AuthStatus`, `SlotEnvironment`, `AccountCodec`; `orchestrator.accounts`: `AccountsProjection`, `SlotAuthProbe`, `AccountNotifications` | библиотека |
| Execution Target Manager | `execution.target`: `ExecutionTarget`, `ExecutionTargetManager`, `TargetHealthTracker`, `TargetDoctor`; `execution.process`, `execution.ssh`; `jsch` | библиотека + агент на target |
| Session Manager | `sessions`: `Sessions`, `SessionDriver`, `SessionCodec`; `execution.claude`: `ClaudeRunner`, `ClaudeCodeCliRunner`, `MockRunner`, `RoutingRunner` | библиотека; ход — процесс на target |
| Scheduler | `tasks`: `Tasks.plan`, `SchedulerPolicy`, `Priority`; такт — `Orchestrator.tick` | библиотека |
| Task DAG | `tasks`: `Tasks.addDependency` (`DEPENDENCY_CYCLE`), `TaskLifecycle` | библиотека |
| Context Manager | `contexts`: `ContextAssembler`, `PromptRenderer`, `Compaction`, `ContextCodec`; `security`: `Sensitivity`, `Origin` | библиотека |
| Handoff Engine | `contexts`: `Handoff`, `Handoffs` | библиотека |
| Event Bus | `core.events`: `EventEnvelope`, `EventType`, `StreamRef`, `SequenceTracker`; `EventRecorder`, `EventSearch`; `storage.journal.EventJournal` | библиотека + отображение в единую модель событий DPmetaApp |
| Notification Engine | `notifications`: `NotificationEngine`, `DeepLink`, `ApprovalAction` | библиотека; показ — оболочка |
| Monitoring | `monitoring`: `Dashboard`, `Timeline`, `SelfDiagnostics`; `ui`: `Screens` | библиотека; экраны — оболочка |
| Verification integration | `verification`: `Verifier`, `VerificationProfile`, `VerificationReport`, `StepRunner`; `orchestrator.verification`: `CompletionFlow`, `AgentStepRunner` | библиотека; шаги — процессы на target |
| Approval Engine | `security`: `ApprovalLedger`, `Proposal`, `Approval`; слияние — запрос `task.merge` (D-49) | библиотека |
| Storage | `storage`: `Vfs`, `NioVfs`, `EventJournal`, `DocumentStore`, `SnapshotStore`, `StateRecovery`, `Migrator`, `BackupService` | библиотека или адаптер к хранилищу DPmetaApp с паритетом форматов |
| Recovery | `orchestrator.recovery`: `Reconciler`, `OrphanScan`, `WorktreeProbe`; `Orchestrator.recover` | библиотека |
| Security | `core.diagnostics`: `Secret`, `Redactor`; `security`; `SlotEnvironment`; `jsch`: `SshKeys`, `HostKeyProbe`; `KnownHosts` (`ui`); `KeystoreKey` (`android`) | библиотека; Keystore — оболочка |
| Git/Workspace manager | `git`: `Worktrees`, `GitCommands`, `GitOp`, `GitStatus`, `OverlapPolicy`, `WorktreeCodec`; `orchestrator.git`: `GitService`, `WorktreesProjection` | библиотека; `git` — процесс на target |

### 21.2. Библиотека, процесс, оболочка

- Библиотека: все модули ниже `ui` — чистый Kotlin/JVM на stdlib, без
  Android (D-02, D-17); подключаются в DPmetaApp как есть, без концептуальной
  переписи (ТЗ, §146). `ui` — представление без платформы, тоже
  переносимо; `jsch` — SSH из приложения Android.
- Процесс (D-03): агент `dpai agent` на каждом target — дистрибутив модуля
  `cli`, JVM 17+, — и всё, что он запускает: Claude Code CLI, `git`, шаги
  проверки. Claude Code остаётся внешним сервисом, которым управляют, а не
  встраивают (ТЗ, §11; `DPmetaApp:MESTER_TECHNICAL_TASK_ROADMAP.md`,
  раздел 42). DPmetaApp совместима с агентами по протоколу (раздел 6.1), а
  не по коду.
- Оболочка: модуль `android` (Compose, `ControllerService`, `Notifier`,
  Keystore) — образец; DPmetaApp рисует свою оболочку над `ControlView` и
  `Orchestrator`.

### 21.3. Автономность (ТЗ, §148; ТЗ, §157, п. 20)

- DP_AI_Orcestrator существует самостоятельно: интеграция не вводит
  зависимости его модулей от DPmetaApp; направление — только «DPmetaApp →
  модули оркестратора». Таблица `docs/ARCHITECTURE.md`, §2 остаётся
  источником границ.
- Работает настолько, насколько позволяют ресурсы: DPmeta выключен —
  `EcosystemProvider.None`; GitHub недоступен — контур V1 его не вызывает
  (слияние — локальное, по одобрению); нет интернета — состояние и история
  локальны, targets доступны по локальной сети или на `127.0.0.1`, отказ
  хода разбирается по классу повтора; target недоступен — пороги здоровья и
  предохранитель (`TargetHealthTracker`, `CircuitLifecycle`), сессии в
  `RECOVERY_PENDING`, сверка при возвращении; слот без входа —
  `AUTH_REQUIRED`, задачи ждут или идут на другой слот.
  Доказательства: `EcosystemTest.withoutEcosystemTheOrchestratorStillWorks`,
  `MonitoringTest.selfDiagnosisDegradesInsteadOfStopping`,
  `ChaosLoopTest.loopKeepsProgressingThroughRestartsAndTargetLoss`,
  `LiveSystemTest.slotWithoutLoginKeepsTasksWaiting`, `RecoveryTest`.
- Превращение оркестратора в мета-платформу до стабильной V1 не
  начинается (ТЗ, §157, п. 20).

### 21.4. Правила нативной интеграции DPmetaApp (раздел 41) — что уже есть

| Правило | Здесь |
|---|---|
| домен-владелец | модуль на подсистему, `docs/ARCHITECTURE.md`, §2 (D-21) |
| типизированные контракты | типизированные ID (D-08), версии форматов (D-09), `Outcome` |
| происхождение | `Provenance` у команд (ТЗ, §5.4), `Origin` у источников контекста, `docs/PROVENANCE.md` |
| детерминированное ядро | D-22, D-07 |
| единая диагностика | `Diagnostic` и `DiagCode` с реестром (D-05): отображаются без потери кода, категории, класса повтора и причин |
| общее хранилище DPmetaApp | сейчас свои файлы (D-24); перенос — адаптер `Vfs` или перенос форматов с паритетом |
| единая модель событий | `dpai.event` v1 отображается поле к полю (раздел 22) |
| тесты | раздел 23 |
| граница одобрений | `ApprovalLedger` (D-10), запросы `UserRequests` (D-44, D-49) |
| тесты переноса и паритета | раздел 23 |
| связь с Integration Contract | этот документ |

## 22. Заметки о переносе (Migration Notes)

1. Эталон — стабильная ревизия (раздел 5), не текущий `main`.
2. Путь — исходный проект → адаптер → нативный домен
   (`DPmetaApp:MESTER_TECHNICAL_TASK_ROADMAP.md`, раздел 9). Первый шаг —
   адаптер над модулями оркестратора как библиотекой; нативная замена
   подсистемы — только после паритета (раздел 23).
3. Коды диагностики, типы событий и виды потоков переходят по именам: имя —
   ключ сохранённых данных и ожиданий тестов. Если единая модель DPmetaApp
   вводит пространства имён, имена оркестратора сохраняются внутри своего.
4. Данные пользователей V1 — журналы `dpai.journal-record` с цепочкой хешей
   и снимки. DPmetaApp либо читает их как есть, либо мигрирует явным шагом:
   проверка цепочки, пересчёт снимков из журнала (D-09, D-24). Чтение
   «примерно так же» не допускается.
5. Канонический JSON и SHA-256 переносятся бит-в-бит: на них держатся хеши
   записей журнала, снимков, одобрений, версий контекста и handoff (D-10).
6. Время и случайность — через `Clock` и `RandomSource` и в DPmetaApp: на
   этом держатся детерминизм и тесты без сна (D-07, D-22).
7. Слой команд DPmetaApp оборачивает контур в `OperationResult` с
   `Provenance` (D-06) — долг раздела 19 закрывается на этой границе.
8. Агент target — отдельный выпускаемый артефакт. Смена протокола — новая
   версия `dpai.agent-reply`/`dpai.run-spec`/`dpai.agent-run`, и контроллер
   понимает обе версии, пока на targets есть прежние агенты; расхождение
   версий видно в `doctor`.
9. Секреты: DPmetaApp не получает входа claude.ai (D-27); `Secret` и
   `Redactor` остаются на границе; хранилище DPmetaApp, как и копия, без
   секретов (D-25).
10. Требования оболочки Android переходят в оболочку DPmetaApp: каналы по
    категориям, неэкспортируемый приёмник кнопок одобрения, служба
    `specialUse`, только нужные разрешения (D-36; раздел 14.1).
11. Решения D-01…D-39 переходят как ограничения подсистемы «AI»; ссылки —
    `DP_AI_Orcestrator:D-NN`.
12. Перенос — после V1 (`docs/direction/V1_SCOPE.md`, §3, строка «Перенос в
    DPmetaApp»).

## 23. Требования семантического паритета (Semantic Parity Requirements)

Перенос подсистемы успешен, только если на одном входе нативная реализация
даёт тот же результат, что исходная
(`DPmetaApp:MESTER_TECHNICAL_TASK_ROADMAP.md`, раздел 9). Эталон — тесты и
корпуса стабильной ревизии.

Как сравнивать:

- вход детерминирован: `ManualClock`, `RandomSource` с зерном, свойства
  `dpai.property.seed` и `dpai.property.iterations` (`docs/TESTING.md`, §3);
  при одинаковом входе и времени результат один (D-22);
- сравниваются машинные итоги, а не тексты: состояния автоматов, коды
  диагностики и класс повтора, типы событий по потокам с номерами и
  полезной нагрузкой, канонический JSON и хеши документов и снимков;
- сценарии — «золотым журналом»: исходная реализация пишет `dpai.event` на
  сценарии; нативная на тех же входах даёт те же типы, потоки, номера и
  полезную нагрузку (идентификаторы сопоставляются по порядку появления) и
  тот же канонический хеш итоговых снимков `dpai.*-state`;
- расхождение, найденное при переносе, сначала становится файлом корпуса и
  тестом здесь, затем правится нативная реализация (`docs/TESTING.md`, §6).

| Подсистема | Эталон | Вход | Что совпадает |
|---|---|---|---|
| JSON, канонический вид, хеши | `JsonTest`, `CanonicalJsonTest`, `HashingTest` (`knownVectors`) | генераторы `testkit` | отказы строгого JSON теми же кодами; канонический вид и `sha256:` бит-в-бит |
| Идентификаторы | `IdsTest` | зерно | формат, префиксы, порядок по времени, `ID_WRONG_KIND` |
| Результат, диагностика, секреты, время, `Measure` | `OutcomeTest`, `OperationResultTest`, `DiagnosticTest`, `RedactorTest`, `SecretTest`, `ClockTest`, `MeasureTest` | — | статус из `Outcome`; класс повтора через причины; секрет не в тексте; `Unknown` — не ноль |
| Автоматы | `LifecycleTablesTest`, `StateMachineTest` | все пары состояний | таблицы пяти жизненных циклов и `HeartbeatFreshness`; повтор перехода ничего не меняет |
| События | `EventsTest`, `SequenceTrackerTest` (`anyDeliveryOrderYieldsTheSameStream`) | перестановки и повторы | `Accepted`/`Duplicate`/`Gap`/`Conflict`; итог не зависит от порядка доставки |
| Хранилище | `EventJournalTest`, `EventJournalSegmentsTest`, `DocumentStoreTest`, `SnapshotRecoveryTest`, `MigratorTest`, `BackupTest`, `StorageLeakTest`, `NioVfsTest` | модель сбоев `SimulatedVfs`, обрыв перед каждой операцией | «прежнее или новое», подтверждённое не теряется, карантин хвоста, last known good, набор ТЗ, §61 |
| Вывод Claude Code | `ClaudeStreamTest`, `MockRunnerTest` | `tests/data/claude-stream/` (8 образцов), сценарии ТЗ, §89 | итог хода и код по каждому образцу; ошибка входа при `subtype: success` — отказ входа; «готово» без `result` — не завершение; порча не бросает исключений |
| Протокол агента | `RunChannelTest`, `AgentRuntimeTest`, `AgentCommandTest` | настоящие процессы (`TestChild`); SSH — со свойствами | ответы `dpai.agent-reply`, сверка пропусков, `LOST`, остановка дерева без сирот |
| Targets | `TargetHealthTest`, `ExecutionTargetManagerTest`, `LocalProcessHostTest`, `SshProcessHostTest`, `JschProcessHostTest`, `RoutingRunnerTest` | — | пороги `DEGRADED` и `OFFLINE`; отказы SSH кодами |
| Аккаунты | `AccountsTest`, `AccountsPropertyTest`, `AuthStatusTest`, `SlotEnvironmentTest`, `AccountCodecTest`, `AccountsJournalTest`, `AccountNotificationsTest`, `SlotAuthProbeTest` | свойства с зерном | состояние слота из фактов, выбор слота, защиты от пересечения, очищенное окружение |
| Сессии | `SessionsTest`, `SessionsJournalTest` | — | автомат, продолжение новой сессией, сверка слота перед ходом, свежесть heartbeat |
| Задачи, планировщик, DAG | `TasksTest`, `OverlapTest` | — | порядок со старением и развязкой по `TaskId`, циклы, повтор по классу, петли, пересечение областей |
| Контекст и handoff | `ContextTest` | `tests/data/context-injection/` | детерминированный отбор и хеш версии, граница проекта, `SECRET` исключён по умолчанию, блоки данных с обезвреженными маркерами, идемпотентный handoff |
| Git | `GitLogicTest`, `WorktreeCodecTest`, `GitServiceTest` | настоящий `git` | безопасные команды, разрушительное — только с одобрением, один владелец дерева |
| Проверка и завершение | `CompletionFlowTest`, `AgentStepRunnerTest` | — | вердикт → состояние задачи; `DONE` только с проверкой или исключением |
| Уведомления и одобрения | `NotificationEngineTest`, `UserRequestsFlowTest` | шторм событий | агрегация, эскалация, предел частоты; ответ с чужим хешем ничего не одобряет |
| Мониторинг и экраны | `MonitoringTest`, `ScreensTest` | — | `STALE` вместо «работает», деградация вместо остановки |
| Восстановление и инварианты | `RecoveryTest`, `RestartTest`, `ChaosLoopTest`, `InvariantsTest` | хаос с зерном | исходы сверки ТЗ, §55; подъём из снимков и сегментов; инварианты ТЗ, §118 после каждого такта |
| Сквозные сценарии V1 | `V1ScenariosTest` (сценарии 1–4), `EcosystemTest` (сценарий 5), `IsolationTest`, `LiveSystemTest`, `LiveProjectTest` | `MockRunner`, настоящий `git` | путь `docs/direction/V1_SCOPE.md`, §1 целиком |
| Нагрузка и выносливость | `PerformanceTest`, `EnduranceTest` | ТЗ, §93, §94, §116 | не смысл, а регрессия: пределы `docs/PERFORMANCE.md` не хуже |

Тесты с внешним миром (`AgentCommandTest` по SSH, `JschProcessHostTest`,
`RealClaudeTest`) — эталон только при тех же свойствах `dpai.test.*`; без
них сравниваются отказы. `RealClaudeTest` тратит лимит подписки и в
паритет по умолчанию не входит.
