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
| `dpai.journal-record` | 1 | строка журнала: `position`, `prev` (хеш предыдущей), `event`, `hash` | 2 |
| `dpai.stored` | 1 | конверт документа: `sha256` содержимого и `content` | 2 |
| `dpai.snapshot` | 1 | снимок: `projection`, `position`, `recordHash`, `state` | 2 |
| `dpai.backup-manifest` | 1 | манифест копии: `createdAt`, `schemaVersion`, `files` (`path`, `size`, `sha256`), `hash` (ТЗ, §60) | 2 |
| `dpai.restore-marker` | 1 | маркер начатого восстановления: `backup`, `manifest` | 2 |
| `dpai.accounts-state` | 1 | состояние аккаунтов в снимке: подписки с окнами пределов, слоты, подписка задачи, переходы задач (`docs/ACCOUNT_MODEL.md`, §8) | 5 |
| `dpai.agent-reply` | 1 | ответ агента target одной строкой: `command`, `ok`, `diagnostics`, необязательные `run`, `events`, `stop`, `doctor` (`docs/EXECUTION_MODEL.md`, §1.3; D-31) | 3 |
| `dpai.agent-run` | 1 | запуск на target: команда, каталог, правка окружения, тайм-аут, состояние, идентификаторы и время старта супервизора и процесса | 3 |
| `dpai.context` | — | версия контекста | 8 |
| `dpai.handoff` | — | handoff | 8 |

### Событие `dpai.event` v1

| Поле | Тип | Смысл |
|---|---|---|
| `id` | `EventId` | идентичность события; повтор с тем же id — идемпотентен |
| `type` | строка `EventType` | вид события; имена — публичный контракт |
| `stream` | `{"kind", "id"}` | поток (сущность): `task`, `session`, `account`, `target`, `subscription`, `system` |
| `sequence` | целое ≥ 1 | номер в потоке без пропусков |
| `occurredAt` | `Timestamp` | когда произошло (по часам источника) |
| `recordedAt` | `Timestamp` | когда записано оркестратором |
| `correlationId` | `CorrelationId`, необязательно | цепочка, к которой относится |
| `causationId` | `EventId`, необязательно | событие-причина |
| `payload` | объект | данные вида события; своя схема по `type` |

## 5. Инварианты

Машинно проверяемые инварианты ТЗ, §118. Столбец «Проверка» — что их
ловит сейчас; «—» — проверка появится на этапе в скобках.

| Инвариант | Проверка |
|---|---|
| Каждая сессия в `RUNNING` связана ровно с одной задачей | — (6) |
| У задачи не больше одной активной сессии, если параллельность не разрешена явно | — (7) |
| Каждая активная сессия принадлежит одному execution target | — (6) |
| У каждой активной сессии есть аккаунт | — (6) |
| У каждого разрушительного действия есть запись одобрения | — (5, 18) |
| У каждого сохранённого события устойчивый id | `EventsTest` |
| Секрет не появляется в диагностике | `RedactorTest`, `DiagnosticTest` |
| У задачи `DONE` есть проверка или явное исключение политики | `LifecycleTablesTest` (нет пути в `DONE` мимо `VERIFIED` и `COMPLETION_UNVERIFIED`) |
| У удалённой задачи нет активной сессии | — (7) |
| У каждого worktree есть владелец или связь с сессией | — (9) |
| Два изолированных сеанса не владеют одним изменяемым worktree | — (9) |
| Недопустимый переход отвергается; сущность не попадает в невозможное состояние | `StateMachineTest`, `LifecycleTablesTest` |
