# Модель исполнения

Где и как работает Claude: execution target, процессы, runner, протокол
событий. Код — модуль `execution` (этапы 3, 4). Мобильный контроллер
отделён от исполнителя (D-03).

## 1. Execution target

```
Mobile Controller (android/ или cli/)
        │  протокол событий с номерами (транспорт — этап 3)
        ▼
Execution Target  ── TERMUX_LOCAL · LINUX_SSH · WINDOWS_REMOTE · MOCK  (V1, D-26)
        │  агент оркестратора на target (оболочка cli)
        ▼
Claude runtime    ── Claude Code CLI (print, stream-json), Claude Agent SDK
```

Транспорт к любому target — SSH (D-26): к Termux — на `127.0.0.1` того
же телефона, к Linux и Windows — с проверкой ключа хоста. На JVM — системный
клиент OpenSSH (D-31); вид `LOCAL` — машина самого контроллера, тот же
агент без SSH; `MOCK` — `MockAgentHost` без процессов (ТЗ, §89). Работает
ли Claude Code в Termux на Redmi Note 13 — проверка на устройстве
(`docs/research/CLAUDE_CODE_CLI.md`, §5; `TODO.md`).

Поля — `docs/DATA_MODEL.md`, §3. Код — пакет `execution.target`:
`ExecutionTarget`, `ExecutionTargetManager`, `TargetHealthTracker`,
`TargetDoctor`.

### 1.1. Здоровье target

Здоровье — по пробам с несколькими порогами, а не по одному пропуску
(ТЗ, §14): проба — вызов `dpai agent doctor`. Первый пропуск — только
`DEGRADED`; `OFFLINE` — когда подряд не меньше `offlineAfterFailures`
неудачных проб **и** с первой из них прошло не меньше `offlineAfter`
(по умолчанию 3 пробы и минута); возврат — после
`recoverAfterSuccesses` удачных проб подряд. Медленный ответ — тоже
`DEGRADED`. Автомат — `core`, `TargetLifecycle`:

| Из | В |
|---|---|
| `UNKNOWN` | `ONLINE`, `DEGRADED`, `OFFLINE`, `DISABLED` |
| `ONLINE` | `DEGRADED`, `DISABLED` |
| `DEGRADED` | `ONLINE`, `OFFLINE`, `DISABLED` |
| `OFFLINE` | `ONLINE`, `DEGRADED`, `DISABLED` |
| `DISABLED` | (явно: `UNKNOWN`) |

`ONLINE` → `OFFLINE` за одну пробу невозможен по таблице. Время
последнего ответа — `Measure` (D-13): target, который ни разу не ответил,
«неизвестно когда», а не «в начале эпохи».

### 1.2. `doctor` для target

`dpai agent doctor` сообщает версию агента, ОС, Java, время, Termux,
свободный диск и память, `claude --version`, `git --version` (ТЗ, §34).
Контроллер оценивает (`TargetDoctor`): связь; Claude Code нет — FAIL
(D-26); Git нет — WARN; диск меньше 1 ГиБ — WARN, меньше 200 МиБ — FAIL;
память меньше 256 МиБ — WARN; расхождение часов (время target минус
середина интервала запроса, за вычетом половины задержки) больше 30 с —
WARN, больше 5 мин — FAIL. Неизвестное — UNKNOWN, а не OK.

### 1.3. Агент на target

`dpai agent` (модуль `cli`, логика — `orchestrator.agent.AgentRuntime`):

| Команда | Что делает |
|---|---|
| `start --run ID [--dir] [--timeout-seconds] [--env K=V]… [--unset K]… -- КОМАНДА…` | записывает запуск (`dpai.agent-run`) и запускает отдельный супервизор |
| `supervise --run ID` | единственный писатель журнала запуска: процесс, вывод через `Redactor`, heartbeat, тайм-аут, просьба остановиться, выход |
| `events --run ID --after N --limit M` | подтверждённые события с номером больше N и состояние запуска |
| `status --run ID` | состояние запуска; супервизор умер, не записав выхода, — `LOST` |
| `stop --run ID [--grace-seconds]` | просьба супервизору; потерянный супервизор — остановка процесса по идентификатору и времени старта |
| `doctor` | §1.2 |

Каталог агента: `runs/<SessionId>/run` (документ запуска),
`events.jsonl` (журнал), `stop-request`. Поток событий запуска — поток
сессии (`StreamRef` вида `session`): `TARGET_PROCESS_STARTED`,
`TARGET_PROCESS_OUTPUT`, `TARGET_PROCESS_HEARTBEAT`,
`TARGET_PROCESS_EXITED` (причина: `EXITED`, `TIMEOUT`, `STOPPED`,
`START_FAILED`).

Контроллер читает события каналом `RunChannel`: с номера, следующего за
последним принятым, поэтому обрыв связи ничего не теряет. Пропуск номера
— повторный запрос с первого непринятого (сверка); пропуск, который
сверка не закрыла, — `RUN_RECONCILIATION_REQUIRED`, события через дыру
не применяются (D-12; `docs/ARCHITECTURE.md`, §3.2).

## 2. Предохранитель

Для внешних ресурсов (GitHub, SSH target, провайдер, Termux, сеть) —
предохранитель (ТЗ, §81), автомат `core`, `CircuitLifecycle`:

| Из | В |
|---|---|
| `HEALTHY` | `FAILING` |
| `FAILING` | `HEALTHY`, `OPEN` |
| `OPEN` | `HALF_OPEN` |
| `HALF_OPEN` | `RECOVERED`, `OPEN` |
| `RECOVERED` | `HEALTHY`, `FAILING` |

`OPEN` — запросы к ресурсу не идут; по истечении паузы — одна проба
(`HALF_OPEN`). Так один упавший ресурс не создаёт лавину повторов.

## 3. ClaudeRunner

Единый интерфейс сессии (ТЗ, §12):

| Операция | Смысл |
|---|---|
| `start` | запуск с контекстом, аккаунтом, worktree, бюджетом |
| `send` | следующее сообщение в работающую сессию (stream-json вход) |
| `stream` | поток событий провайдера, переведённых в события оркестратора |
| `pause` | приостановка с сохранением возможности продолжить |
| `cancel` | отмена текущего хода |
| `resume` | возобновление по `providerSessionId` |
| `inspect` | состояние процесса и сессии без изменения |
| `terminate` | полная остановка с уборкой процессов |

Ход — один процесс `claude -p --verbose --output-format stream-json`
под агентом target (D-32): `start` — `--session-id` с UUID, назначенным
оркестратором; `send` и `resume` — следующий ход с `--resume`; `pause` и
`cancel` — остановка процесса хода; prompt — на стандартный вход.
`stream` — опрос канала запуска: события агента переводятся в
`RunnerUpdate` (начало хода, событие провайдера, stderr, heartbeat, итог
хода `ClaudeTurn`). Где работает target — local, Termux, SSH — решает
`ProcessHost` агента, поэтому отдельные `RemoteRunner` и `TermuxRunner`
не нужны.

Реализации: `ClaudeCodeCliRunner` и `MockRunner`. `MockRunner` (ТЗ, §12,
§89) — тот же `ClaudeCodeCliRunner` поверх `MockAgentHost`, у которого
«процесс» пишет поток по сценарию в записанном формате: успех, отказ,
тайм-аут, падение, медленный вывод, повтор и пропуск событий, rate
limit, ошибка аутентификации, частичное завершение.

## 4. Структурированный вывод

Состояние берётся из машинно читаемых событий, а не из текста модели
(ТЗ, §53): stream-json CLI, `--json-schema` для итогового ответа
(`structured_output`). Текст «готово» — заявление (D-23): без события
`result` ход не завершён. Испорченный, неполный и повторный вывод —
диагностика (`docs/RECOVERY.md`), не догадка.

| Итог хода (`ClaudeTurn`) | Когда |
|---|---|
| `COMPLETED` | `result` с `is_error: false` |
| `FAILED` | ошибка входа (`CLAUDE_AUTH_REQUIRED`), предел (`CLAUDE_RATE_LIMITED`), ошибка API или итога, чужой `session_id`, поток без итога после выхода процесса |
| `INCOMPLETE` | тайм-аут или остановка без итога, `error_max_turns`, предел бюджета — ход можно продолжить `resume` |

`rate_limit_event` несёт загрузку окна пяти часов и недели и время сброса
(`docs/research/CLAUDE_CODE_CLI.md`, §8) — это факт для пределов подписки
(D-30). Стоимость при подписке — расчёт по прейскуранту: `Measure` с
источником `ESTIMATED` (D-13).

## 5. Процессы

Запуск процессов — только в модуле `execution` (D-03): `ProcessHost` —
`LocalProcessHost`, `SshProcessHost`, `MockAgentHost`. Каждый процесс
привязан к сессии; тайм-аут, остановка и обрыв связи не оставляют
сирот (ТЗ, §119): остановка снимает список потомков до сигнала корню
(`ProcessTree`) — после смерти корня связь «родитель — потомок» у ОС
теряется, — сначала мягко, через паузу принудительно; пережившие —
`PROCESS_ORPHANS_LEFT`, а не тишина. Аргументы командной строки не
содержат секретов: секрет — в окружении процесса (D-14).

На POSIX JVM передаёт аргументы в кодировке локали: при локали без UTF-8
(`POSIX`, `C` — контейнер, процесс по SSH без `LANG`) кириллица в
сообщении коммита или `--append-system-prompt` стала бы `?`. Такой
аргумент — отказ запуска `PROCESS_ARGUMENT_UNENCODABLE` (D-05), а не
искажённая команда (`ArgumentEncoding`); агенту на Linux-target нужна
локаль UTF-8 (`LANG=C.UTF-8`). Промпт идёт через вход процесса в UTF-8 и
от локали не зависит.

## 6. Android

Длительная работа на устройстве — только WorkManager и foreground
service по правилам Android 14–16 (ТЗ, §96, §97). Процесс приложения
может умереть в любой момент; надёжность — в сохранённом состоянии и
сверке (`docs/RECOVERY.md`), а не в живучести процесса.
