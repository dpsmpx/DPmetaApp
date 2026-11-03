# Модель данных

Сущности оркестратора, их идентификаторы, форматы хранения и
инварианты. Сущность живёт в модуле своей подсистемы
(`docs/ARCHITECTURE.md`, §2); здесь — общий словарь. Поля — по ТЗ, §7;
что добавлено сверх ТЗ, сказано рядом.

## 1. Идентификаторы

Вид — `<префикс>_<26 знаков Crockford base32>`: 48 бит времени создания
(мс эпохи) и 80 бит случайности, сортируется по времени (D-08). Разбор
проверяет префикс и алфавит; чужой префикс — `ID_WRONG_KIND`.

| Тип | Префикс | Сущность |
|---|---|---|
| `TaskId` | `tsk` | задача |
| `SessionId` | `ses` | сессия оркестратора |
| `AccountId` | `acc` | слот аккаунта |
| `ExecutionTargetId` | `tgt` | execution target |
| `ProjectId` | `prj` | проект |
| `RepositoryId` | `rep` | репозиторий |
| `WorktreeId` | `wtr` | рабочее дерево |
| `ContextId` | `ctx` | контекст (все его версии) |
| `HandoffId` | `hnd` | handoff |
| `EventId` | `evt` | событие |
| `OperationId` | `op` | команда оркестратора |
| `CorrelationId` | `cor` | цепочка связанных событий и команд |
| `ApprovalId` | `apr` | запрос одобрения |
| `SubscriptionId` | `sub` | подписка Claude (`docs/ACCOUNT_MODEL.md`, §1) |
| `RequestId` | `req` | запрос человеку (D-44) |

`ProviderSessionId` — идентификатор, выданный провайдером (у Claude
Code — UUID, `docs/research/CLAUDE_CODE_CLI.md`): непрозрачная строка
без нашего префикса, отдельный тип. `providerSessionId ≠ SessionId`
(ТЗ, §28): одна сессия оркестратора может иметь не более одного
идентификатора провайдера; продолжение — новая сессия оркестратора,
которая ссылается на прежнюю.

## 2. Время

`Timestamp` — миллисекунды эпохи UTC; в JSON — целое число. Источник —
`Clock` (D-07). Точка во времени хранится для каждой записи события и
снимка: состояние «на момент T» воспроизводимо (ТЗ, §5.7).

## 3. Сущности

| Сущность | Модуль | Поля ТЗ, §7 | Сверх ТЗ |
|---|---|---|---|
| `AIAccount` (слот) | `accounts` | id, provider, label, authenticationRef, status, capabilities, usagePolicy, limits, health, createdAt, updatedAt | слот принадлежит подписке (`SubscriptionId`), пределы — у подписки; `authenticationRef` — каталог настроек Claude Code на target; `status` выводится из фактов (`docs/ACCOUNT_MODEL.md`, §2) |
| `ExecutionTarget` | `execution` | id, type, host, platform, runtime, capabilities, workspaceRoot, connection, health, lastSeen | `lastSeen` — `Measure` (D-13) |
| `Session` | `sessions` | id, provider, accountId, executionTargetId, taskId, projectId, repositoryId, branch, worktree, providerSessionId, state, startedAt, updatedAt, lastHeartbeat, usage, failure | `continuesSessionId` — прежняя сессия при продолжении; `contextId` + версия |
| `Task` | `tasks` | id, title, objective, project, priority, status, dependencies, constraints, executionPolicy, verificationPolicy, contextPolicy, assignedAccount, assignedTarget, sessionId, createdAt, updatedAt | `status` — автомат `TaskLifecycle` (`docs/SCHEDULER.md`) |
| `ContextSnapshot` | `contexts` | id, taskId, projectId, revision, sources, files, documents, decisions, constraints, recentChanges, verification, handoff, hash, createdAt | версия неизменна: изменение — новая версия (ТЗ, §24) |
| `Handoff` | `contexts` | sessionId, taskId, state, completed, verified, failed, warnings, openProblems, currentAction, nextAction, modifiedFiles, commit, branch, workspace, contextHash | `id: HandoffId`; минимум полей — ТЗ, §27 |

Значения, которых может не быть (использование, heartbeat, нагрузка),
хранятся как `Measure`: неизвестное — не ноль (D-13).

## 4. Форматы хранения

Каждый сохраняемый документ — JSON с заголовком (D-09):

```json
{"format": "dpai.<вид>", "version": 1, …}
```

- `format` не тот — `FORMAT_MISMATCH`; `version` больше текущей —
  `FORMAT_VERSION_NEWER` («создано более новой версией»), меньше
  поддерживаемой — миграция или `FORMAT_VERSION_UNSUPPORTED`;
- неизвестное поле — `FIELD_UNKNOWN`; повтор ключа — `JSON_DUPLICATE_KEY`;
- хеш документа — SHA-256 канонического вида (D-10).

| Формат | Версия | Что | Этап |
|---|---|---|---|
| `dpai.event` | 1 | событие в журнале (`docs/ARCHITECTURE.md`, §4) | 1 |
| `dpai.journal-record` | 1 | строка журнала: `position`, `prev` (хеш предыдущей), `event`, `hash`; журнал контроллера — сегментами `<журнал>.seg-<номер первой записи>`, номера и цепочка сквозные (D-37) | 2, 19 |
| `dpai.streams-state` | 1 | снимок последних номеров потоков событий: `kind`, `id`, `last` — нумерация после перезапуска без чтения всей истории (D-37) | 19 |
| `dpai.merges-state` | 2 | ожидающие слияния (задача, запрос одобрения, предложение, срок), слитые задачи с одобрением каждой и последний коммит контура в ветке задачи (хеш, ветка, время); события — `TASK_MERGE_REQUESTED`, `TASK_MERGE_DECLINED`, `TASK_MERGED`, `GIT_COMMIT_CREATED` в потоке задачи; v1 читается — без коммитов | 14, 19 |
| `dpai.user-requests-state` | 1 | запросы человеку (D-44): вид, назначение, предмет, текст, варианты, задача и сессия, срок, состояние, доставки, ответ; события — `USER_REQUEST_CREATED`, `_DELIVERED`, `_ANSWERED`, `_EXPIRED`, `_CANCELLED`, `_FAILED` в потоке запроса | 11, 12 |
| `dpai.operations-state` | 1 | исполненные команды с ключом идемпотентности (D-42): ключ, операция, итог, значение, код отказа; событие — `OPERATION_RECORDED` в системном потоке | 11 |
| `dpai.control-state` | 1 | настройки контура от человека (D-42, п. 5, 6): выключенные targets (новых сессий не получают) и общий предел одновременных сессий; события — `CONTROL_TARGET_ENABLED_SET` в потоке target, `CONTROL_SESSION_LIMIT_SET` в системном | 11 |
| `dpai.task-messages` | 1 | итоговые сообщения ходов задачи (D-42) — документ задачи `messages-<задача>`: сессия, номер хода, время, итог хода, модель и полный текст (до 256 КиБ; в журнале событий текст модели урезан до `maxOutputChars`); последние 50 ходов | 11 |
| `dpai.stored` | 1 | конверт документа: `sha256` содержимого и `content` | 2 |
| `dpai.snapshot` | 1 | снимок: `projection`, `position`, `recordHash`, `state` | 2 |
| `dpai.backup-manifest` | 1 | манифест копии: `createdAt`, `schemaVersion`, `files` (`path`, `size`, `sha256`), `hash` (ТЗ, §60) | 2 |
| `dpai.restore-marker` | 1 | маркер начатого восстановления: `backup`, `manifest` | 2 |
| `dpai.accounts-state` | 2 | состояние аккаунтов в снимке: подписки с окнами пределов, слоты, подписка задачи, переходы задач (`docs/ACCOUNT_MODEL.md`, §8); v2 — загрузка окна `utilizationBp` (0…10000) и порог «у предела» `nearPercent` (D-45), пишутся, только если известны или отличны от умолчания; v1 читается | 5, 11 |
| `dpai.agent-reply` | 2 | ответ агента target одной строкой: `command`, `ok`, `diagnostics`, необязательные `run` (с номером хода `turn`, этап 4), `events`, `stop`, `doctor`; v2 — `more`: страница событий обрезана по объёму, за ней есть ещё (ответ без `more` пишется как v1, читаются обе); поток `follow` — такие строки одна за другой (D-76, D-79) (`docs/EXECUTION_MODEL.md`, §1.3; D-31) | 3 |
| `dpai.agent-run` | 3 | запуск на target: `spec` (`dpai.run-spec`), номер хода `turn`, состояние, идентификаторы и время старта супервизора и процесса; v3 — время загрузки `bootAt`: процесс узнаётся по старту относительно загрузки, скачок часов не делает живой ход `LOST` (ревью A-11); `journalError` — почему журнал хода перестал писаться: ход остановлен, `status` предупреждает (ревью A-15); v2 читается, v1 этапа 3 заменён до выпуска и не читается | 4 |
| `dpai.agent-pruned` | 1 | надгробие запуска, удалённого по сроку хранения (ревью A-6): номер последнего хода `turn`, последнего события `lastSequence`, время `prunedAt`; текста хода нет — следующий ход сессии продолжает нумерацию | 4 |
| `dpai.app-targets` | 3 | targets приложения Android (D-35): `id`, имя, хост, порт, пользователь, платформа, команда агента, каталог Claude слота `configDir` (с v2; v1 читается без него); с v3 — target «в приложении» (`"kind": "in-app"`: только `id`, имя и каталог слота в госте; D-75, шаг 5); ключи хостов — в known_hosts приложения | 11 |
| `dpai.api-profiles` | 1 | API-профили приложения (D-98): `id` (`acc_…`), имя, провайдер (`anthropic`, `openai-compatible`, `gemini`), адрес API, модель, отметка своего адреса `customHost`, предел ответа `maxOutputTokens` (если задан); ключей нет — они в Keystore приложения | — |
| `dpai.api-chats` | 1 | переписка чатов с API-профилями (D-98): разговор (`id` — `cor_…`, профиль, заголовок) и реплики — роль, текст, модель, как кончилась (`ok`, `length`, `refused`, `cancelled`, `failed`), пометка, токены входа и выхода (если провайдер сообщил); до 50 разговоров по 400 реплик | — |
| `dpai.file-links` | 1 | связи папок автора с копиями в Linux (D-99): `id` (`cor_…`), имя папки, адрес папки системного окна (`content://…`), каталог копии в `~/files/`, время последней синхронизации `lastSync`, база — файлы после неё (`path`, `sha256`, размер и время изменения в папке и в копии) | — |
| `dpai.app-project` | 2 | проект контроллера приложения (D-35): постоянные id проекта и репозитория, target, каталог репозитория и деревьев задач на нём, ветка интеграции, шаги проверки; v2 — `claude`: ход на другом target (`target`, ключ SSH `identityFile` и `knownHostsFile` на нём, D-50), пишется, только если задан; v1 читается | 11 |
| `dpai.run-spec` | 2 | что запустить под агентом: команда, каталог, правка окружения, тайм-аут, вход процесса (prompt); передаётся агенту на стандартный вход; v2 — способ вывода `capture` (D-69), без него пишется v1 | 4 |
| `dpai.auth-status` | 1 | свод `claude auth status` агентом на target: вход, способ, провайдер, план, отпечаток аккаунта — без email и организации; не разобралось — `invalid` (D-69) | 5 |
| `dpai.sessions-state` | 4 | состояние сессий в снимке: записи сессий со слотом, target, сессией провайдера, продолжением, состоянием, heartbeat; v2 — накопленный расход `usage` (D-40); v3 — инструменты ходов `usage.tools` (D-41); v4 — назначение сессии: роль ревью `review` (D-73), пишется, только если есть; прежние читаются | 6 |
| `dpai.tasks-state` | 7 | состояние задач в снимке: задачи с политикой, зависимостями, возможностями, счётчиками повторов и продолжений, ключами отказов; с v2 — заявленная область `scope` (v1 читается без неё); v3 — класс задачи, модель и усилие в политике (D-40), пишутся, только если заданы; v4 — удержание человеком `held` (D-42), пишется, только если удержана; v5 — архив `archived` (D-42), пишется, только если в архиве; v6 — ревью (D-73): роли `reviews` в политике и итоги ревью `reviews` у задачи, пишутся, только если есть; v7 — слот, с которого задачу перенесли, `excludedSlot` (D-77), пишется, только если есть; события — `TASK_POLICY_CHANGED`, `TASK_HOLD_CHANGED`, `TASK_COUNTERS_RESET`, `TASK_ARCHIVE_CHANGED`, `TASK_REVIEW_RECORDED`, `TASK_SLOT_EXCLUDED` | 7, 9, 10, 11 |
| `dpai.worktrees-state` | 1 | репозитории и рабочие деревья в снимке: путь, ветка, владелец-сессия, удалено ли; события — `REPOSITORY_REGISTERED`, `WORKTREE_*` в потоках `repository` и `worktree` | 9, 13 |
| `dpai.verification-report` | 1 | отчёт проверки: проект, уровень, коммит, итог, шаги с командой, кодом выхода, хешем вывода, длительностью и временем | 10 |
| `dpai.ecosystem` | 1 | манифест экосистемы: проекты, состояние, документы, предложения задач (`docs/ECOSYSTEM.md`, §2) | 15 |
| `dpai.ecosystem-results` | 1 | итоги глобального прогона: проект, предложение, задача, состояние, вердикт проверки | 16 |
| `dpai.context` | 3 | версия контекста: задача, проект, цель, следующее действие, включённые источники с хешами, не вошедшие источники `omitted` с оценкой размера и `sameAs` для повтора (v3, D-40; v2 читается), хеш прежней версии (`docs/CONTEXT_MODEL.md`, §7); номер версии — `contextVersion` (в v1 он затирал версию формата, v1 не читается); последняя версия задачи хранится документом контура — для продолжения после перезапуска | 8 |
| `dpai.handoff` | 2 | handoff: поля ТЗ, §27, ключ идемпотентности, причина, контекст с хешем, `hash`; v2 — причины `VERIFICATION_FAILED` и `CONTEXT_OVERFLOW` (D-40), v1 читается | 8 |

### Событие `dpai.event` v1

| Поле | Тип | Смысл |
|---|---|---|
| `id` | `EventId` | идентичность события; повтор с тем же id — идемпотентен |
| `type` | строка `EventType` | вид события; имена — публичный контракт |
| `stream` | `{"kind", "id"}` | поток (сущность): `task`, `session`, `account`, `target`, `subscription`, `system`, `repository`, `worktree` (виды только дописываются) |
| `sequence` | целое ≥ 1 | номер в потоке без пропусков |
| `occurredAt` | `Timestamp` | когда произошло (по часам источника) |
| `recordedAt` | `Timestamp` | когда записано оркестратором |
| `correlationId` | `CorrelationId`, необязательно | цепочка, к которой относится |
| `causationId` | `EventId`, необязательно | событие-причина |
| `payload` | объект | данные вида события; своя схема по `type` |

## 5. Инварианты

Машинно проверяемые инварианты ТЗ, §118. Все, кроме последнего, —
`Invariants` в `orchestrator` (имя инварианта — в скобках): проверяются
после каждого такта в сценариях V1 и контроллера и в конце нагрузки
(`docs/TESTING.md`, §1). Столбец «Проверка» — что их ловит.

| Инвариант | Проверка |
|---|---|
| Каждая сессия в `RUNNING` связана ровно с одной задачей | `session-has-task`, `InvariantsTest` |
| У задачи не больше одной активной сессии, если параллельность не разрешена явно | `task-single-session` |
| Каждая активная сессия принадлежит одному execution target | `session-has-target`; `SessionsTest` (сессия создаётся на target своего слота, сверка перед ходом) |
| У каждой активной сессии есть аккаунт | `session-has-account`; `SessionsTest` (без аренды слота сессия не создаётся) |
| У каждого разрушительного действия есть запись одобрения | `destructive-approved`; `GitLogicTest`, `GitServiceTest` (Git) |
| У каждого сохранённого события устойчивый id | `event-stable-id`, `event-sequence`; `EventsTest` |
| Секрет не появляется в диагностике | `no-secret-in-diagnostics` (держится по построению `Diagnostic`); `RedactorTest`, `DiagnosticTest` |
| У задачи `DONE` есть проверка или явное исключение политики | `done-has-verification`; `LifecycleTablesTest` (нет пути в `DONE` мимо `VERIFIED` и `COMPLETION_UNVERIFIED`), `CompletionFlowTest` (без записи проверки или исключения `DONE` отвергается) |
| У удалённой задачи нет активной сессии | `cancelled-no-session` |
| У каждого worktree есть владелец или связь с сессией | `worktree-owner` |
| Два изолированных сеанса не владеют одним изменяемым worktree | `worktree-exclusive`; `GitServiceTest` |
| Недопустимый переход отвергается; сущность не попадает в невозможное состояние | `StateMachineTest`, `LifecycleTablesTest` |
