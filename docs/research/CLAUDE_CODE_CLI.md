# Claude Code CLI как интерфейс исполнителя

Датированные факты о неинтерактивном интерфейсе Claude Code, на которые
опирается `ClaudeCodeCliRunner` (этап 4) и решение D-04. Факт без даты
и версии сюда не пишется; изменился интерфейс — новая строка с новой
датой, старая остаётся.

## 1. Источник и дата

Проверено **6 октября 2026** по `claude --help` установленного Claude
Code **2.1.291** (среда облачной сессии). Это первичный источник для
флагов; поведение, которое из справки не следует, отмечено как
непроверенное.

## 2. Флаги

| Флаг | Что даёт оркестратору | Справка |
|---|---|---|
| `-p`, `--print` | неинтерактивный запуск: ответ и выход | «Print response and exit (useful for pipes)» |
| `--output-format text\|json\|stream-json` | машинно читаемый итог или поток событий (ТЗ, §53) | «only works with --print» |
| `--input-format text\|stream-json` | поток входных сообщений (`send` у `ClaudeRunner`) | «realtime streaming input» |
| `--include-partial-messages` | частичные сообщения в потоке | только с `--print` и `stream-json` |
| `--replay-user-messages` | эхо входных сообщений — подтверждение приёма | только со `stream-json` на входе и выходе |
| `-r`, `--resume <id>` | возобновление сессии провайдера по id (ТЗ, §28) | «Resume a conversation by session ID» |
| `--session-id <uuid>` | оркестратор сам назначает id сессии провайдера до запуска | «must be a valid UUID» |
| `--fork-session` | при возобновлении — новый id сессии | «use with --resume or --continue» |
| `--no-session-persistence` | сессия не сохраняется и не возобновляется | только с `--print` |
| `--max-budget-usd <сумма>` | предел расхода на вызовы API (ТЗ, §86) | «only works with --print» |
| `--json-schema <schema>` | структурированный итог с проверкой по схеме (ТЗ, §53) | пример схемы в справке |
| `--add-dir <каталоги>` | доп. рабочие каталоги (ТЗ, §1) | «Additional directories to allow tool access to» |
| `--model`, `--fallback-model` | модель и запасные модели (ТЗ, §50) | |
| `--permission-mode` | `acceptEdits`, `auto`, `bypassPermissions`, `manual`, `dontAsk`, `plan` | |
| `--allowedTools`, `--disallowedTools`, `--tools` | границы инструментов сессии (уровни операций, `docs/SECURITY_MODEL.md`, §3) | |
| `--bare` | минимальный режим: без хуков, автопамяти, чтения keychain; аутентификация Anthropic — строго `ANTHROPIC_API_KEY` или `apiKeyHelper` из `--settings` | важно для изоляции аккаунтов (ТЗ, §66) |
| `--setting-sources`, `--settings`, `--strict-mcp-config`, `--mcp-config` | явный набор настроек и MCP-серверов сессии | |
| `--restricted` | без встроенных инструментов, исполняющих команды и код | |

## 3. Выводы для архитектуры

- **Идентичность сессии.** `--session-id` позволяет записать
  `providerSessionId` в журнал до запуска процесса: после падения
  приложения известно, какую сессию провайдера проверять и возобновлять
  (`docs/RECOVERY.md`). `ProviderSessionId` у Claude Code — UUID
  (`docs/DATA_MODEL.md`, §1).
- **Поток событий.** `stream-json` на выходе — источник событий сессии;
  на входе — `send` без перезапуска процесса.
- **Бюджет.** `--max-budget-usd` — предел расхода там, где он
  поддерживается (только `--print`).
- **Изоляция учётных данных.** Для подписок — свой `CLAUDE_CONFIG_DIR`
  на слот (§4, D-27); `--bare` не подходит: он не читает вход по
  подписке.

## 4. Аутентификация и несколько аккаунтов

Проверено **6 октября 2026** по документации Claude Code
(`code.claude.com/docs/en/iam`, раздел «Authentication»):

- **Виды входа:** подписка Claude Pro или Max (вход аккаунтом claude.ai
  в браузере), Claude for Teams или Enterprise, Claude Console (ключ API
  или вход без ключа), облачные провайдеры.
- **Несколько аккаунтов на одной машине:** у каждого аккаунта — свой
  каталог настроек `CLAUDE_CONFIG_DIR`; в нём свои настройки, история
  сессий и вход claude.ai. Пример из документации:
  `alias claude-work='CLAUDE_CONFIG_DIR=~/.claude-work claude'`.
- **Где лежит вход:** Linux — `~/.claude/.credentials.json` (права
  `0600`); Windows — `%USERPROFILE%\.claude\.credentials.json`; с
  `CLAUDE_CONFIG_DIR` — в этом каталоге.
- **Порядок выбора учётных данных:** облачный провайдер →
  `ANTHROPIC_AUTH_TOKEN` → `ANTHROPIC_API_KEY` (в `-p` используется
  всегда, если задан) → `apiKeyHelper` → `CLAUDE_CODE_OAUTH_TOKEN` →
  профили Anthropic → вход по подписке через `/login`. Заданный ключ API
  перекрывает подписку.
- **`claude setup-token`:** токен подписки на год для скриптов и CI;
  его печатают в терминал, хранит пользователь; `--bare` его не читает.
- **Истекающий вход** предупреждает за три дня; истёкший — каждый запрос
  к модели падает с «Login expired · Please run /login».

**`claude auth status`** — проверено **6 октября 2026**: справка Claude
Code 2.1.291 (`--json` — по умолчанию, `--text` — для человека); запуск
2.1.291 с пустым `CLAUDE_CONFIG_DIR`; код команды в пакете npm
`@anthropic-ai/claude-code` 2.1.42 (среда облачной сессии).

| Поле | Значение |
|---|---|
| `loggedIn` | есть ли учётные данные; код выхода — `0`, если да, иначе `1` |
| `authMethod` | `claude.ai` (вход формой Claude), `oauth_token` (токен из окружения или файла), `api_key_helper`, `api_key`, `third_party` (облачный провайдер), `none` |
| `apiProvider` | `firstParty` — Anthropic напрямую |
| `apiKeySource` | только при ключе: откуда он (`ANTHROPIC_API_KEY` и т. п.) |
| `email`, `orgId`, `orgName`, `subscriptionType` | только при `authMethod = "claude.ai"`; могут быть `null` |
| `configDirectory`, `projectsDirectory`, `analyticsDisabled` | есть в 2.1.291 |

Токенов в выводе нет. `AuthStatus` читает его терпимо: обязательны
`loggedIn` и `authMethod`, неизвестные поля пропускаются — новые версии
Claude Code добавляют поля (2.1.42 → 2.1.291). `email` и `orgId` сразу
превращаются в отпечаток аккаунта; email не хранится (D-30).

## 5. Платформы

Там же (`code.claude.com/docs/en/setup`, «System requirements»), 6
октября 2026: macOS 13+, Windows 10 1809+ и Server 2019+, Ubuntu 20.04+,
Debian 10+, Alpine 3.19+; x64 или ARM64; 4 ГБ памяти. Пакет npm ставит
тот же нативный двоичный файл для `linux-x64`, `linux-arm64`,
`linux-x64-musl`, `linux-arm64-musl`, `darwin-*`, `win32-*`. **Android
(Termux) в списке нет**: Termux использует свою libc, двоичный файл для
glibc или musl там может не запуститься — проверка на устройстве (D-26).
Отдельной строкой требований указано размещение: страны, где Anthropic
предоставляет услуги.

## 6. Условия использования и регионы

Проверено **6 октября 2026**:

- **Страны** (`anthropic.com/supported-countries`): списки отдельно для
  API и для Claude.ai; Россия не входит ни в один.
- **Какие условия действуют** (`code.claude.com/docs/en/legal-and-compliance`):
  Free, Pro, Max — Consumer Terms; Team, Enterprise и Claude API —
  Commercial Terms.
- **Вход по подписке** «предназначен для обычного использования Claude
  Code»; объявленные пределы Pro и Max «предполагают обычное
  индивидуальное использование Claude Code и Agent SDK». Сторонним
  разработчикам нельзя предлагать вход claude.ai в своих приложениях,
  направлять запросы через учётные данные Free, Pro или Max от имени
  пользователей, собирать, хранить или передавать учётные данные и
  токены claude.ai; вход — только через поток Anthropic. Ограничение не
  мешает пользователю входить своей подпиской в немодифицированный
  Claude Code. Anthropic оставляет за собой меры принуждения без
  предупреждения.
- **Consumer Terms** (действуют с 8 октября 2025): доступ
  автоматическими средствами (бот, скрипт) запрещён, кроме доступа по
  ключу API или там, где Anthropic явно разрешает; передавать вход и
  ключи другим нельзя. Claude Code в режиме `-p` с подпиской разрешён
  явно: документация описывает `setup-token` «для CI и скриптов».
- **Правила использования агентов** (`support.claude.com`, статья
  12005017): запрещено создавать или вести несколько аккаунтов, чтобы
  уйти от обнаружения или обойти защитные механизмы платформы.
- **Пределы подписки** (`support.claude.com`, статья 11145838): окно
  сессии сбрасывается каждые пять часов, плюс недельный предел; Claude и
  Claude Code расходуют один общий запас.
- **Agent SDK и `claude -p` на подписке** (`support.claude.com`, статья
  15036540, обновлена 16 июня 2026): объявленный перевод этого
  использования на отдельный ежемесячный кредит **приостановлен** 15
  июня 2026; сейчас `claude -p`, Agent SDK и сторонние приложения
  расходуют обычные пределы подписки. Anthropic обещает предупредить
  заранее, если что-то изменится. Для оркестратора это риск: экономика
  работы через `claude -p` на подписке может измениться
  (`docs/ACCOUNT_MODEL.md`, §6).

## 7. Не проверено

- ~~Формат событий `stream-json`~~ — проверен 6 октября 2026, §8.
- Коды выхода CLI при rate limit, ошибке аутентификации, превышении
  бюджета.
- Ведут ли себя два `CLAUDE_CONFIG_DIR` с разными подписками на одном
  target независимо при параллельных сессиях (замер на этапе 5).
- Как выглядит в stream-json достижение предела подписки и истёкший
  вход — нужен образец для `RATE_LIMITED` и `AUTH_REQUIRED`.
- ~~Запуск Claude Code в Termux~~ — проверен 7 октября 2026, §9 (на Realme C21Y; Redmi Note 13 — нет).
- Claude Agent SDK: интерфейс запуска с JVM (решение этапа 4).

## 8. Проверено запуском

**6 октября 2026**, Claude Code **2.1.291**, Windows 11; образцы — корпус
`tests/data/claude-stream/` (пути и имена подключений убраны).

| Флаг | Что даёт оркестратору | Проверено |
|---|---|---|
| `--verbose` | обязателен вместе с `-p --output-format stream-json`: без него CLI выходит с кодом 1 и «Error: When using --print, --output-format=stream-json requires --verbose» | запуск без `--verbose` |
| `--append-system-prompt` | дополнение к системному prompt хода (правила оркестратора) | справка 2.1.291 |

События `stream-json` (поля, на которых строится состояние):

- `system`/`init` — `session_id` (совпадает с `--session-id`), `model`,
  `claude_code_version`, `apiKeySource` (`none` при входе формой Claude).
- `system`/`thinking_tokens` — оценка токенов размышления.
- `assistant` — `message.content[]` (`text`, `thinking`, `tool_use`);
  ошибка API оформлена сообщением: `error: "authentication_failed"`,
  `is_api_error_message: true`, текст «Not logged in · Please run /login».
- `user` — результаты инструментов.
- `rate_limit_event` — `rate_limit_info`: `status` (`allowed`),
  `rateLimitType` (`five_hour`), `resetsAt` (секунды эпохи),
  `unifiedWindows.five_hour` и `.seven_day` с `utilization` (0…1) и
  `resetsAt`. Это загрузка пределов подписки прямо из потока (D-30).
- `result` — `is_error`, `subtype`, `terminal_reason` (`completed`,
  `api_error`), `result` (текст), `structured_output` (при
  `--json-schema`), `total_cost_usd`, `usage`, `modelUsage` с
  `costBasis: "list"` — стоимость по прейскуранту, а не списание.
  **При ошибке входа `subtype` — `success`, а `is_error` — `true`**: судить
  по `subtype` нельзя. Код выхода при ошибке входа — 1, при успехе — 0.
- `--json-schema` даёт ответ через инструмент `StructuredOutput`; итог —
  в `structured_output`.

Prompt без аргумента читается со стандартного входа: проверено
запуском с текстом на входе (поток дошёл до ответа); с пустым входом —
«Error: Input must be provided either through stdin or as a prompt
argument when using --print».

Не проверено по-прежнему: отказ по пределу подписки (`status` не
`allowed`) и истёкший вход — образец `rate-limited.jsonl` построен, а не
записан.

Дополнено **8 октября 2026**: в сведениях о пределах сессий Claude Code
(облачные сессии автора, поле `rate_limit_info.status` в метаданных
сессии) встречаются три значения — `allowed`, `allowed_warning` (предел
близко, запрос прошёл) и `rejected`. Что то же поле `rate_limit_event`
потока `stream-json` принимает те же значения, — предположение до
записанного образца; разбор считает отказом только `rejected`, а
неизвестное значение отмечает диагностикой (`ClaudeStreamTest`).

## 9. Claude Code в Termux

Проверено **7 октября 2026** на Realme C21Y (RMX3263, Android 11, arm64),
Termux 0.118 (F-Droid, `com.termux`, код версии 1002), Node.js 24.18,
Claude Code **2.1.292**:

- `npm i -g @anthropic-ai/claude-code` ставит пакет, но без нативного
  двоичного файла: для платформы `android` его нет, `claude --version` —
  «Error: claude native binary not installed».
- Сборка `@anthropic-ai/claude-code-linux-arm64-musl` напрямую в Termux не
  запускается: «cannot execute: required file not found» — в Android нет
  загрузчика musl.
- Та же сборка внутри `proot-distro` с Alpine (`proot-distro login alpine
  -- …/claude --version`) отвечает «2.1.292 (Claude Code)».

Вывод для D-26: на телефоне Claude Code работает только в окружении Linux
поверх Termux (proot), нативно — нет. Вход формой Claude и ход сессии в
proot не проверены; на Redmi Note 13 (HyperOS 2.0) проверка через adb
невозможна — ввод и настройки закрыты переключателем «Отладка по USB
(Настройки безопасности)».

## 10. Ход Claude Code в proot на телефоне

Проверено **7 октября 2026** на том же Realme C21Y: Claude Code 2.1.292
(musl-сборка) в `proot-distro` Alpine через обёртку
`tools/termux/claude-proot.sh`; вход подпиской сделал автор в Termux
(`claude`, D-27). `RealClaudeTest` с ПК по SSH (`adb forward`) через
агента `dpai` в Termux (JDK 21): ход «Ответь ровно одним словом: готово»
с `--session-id` и второй ход с `--resume` той же сессии — оба
`COMPLETED`, второй ответ помнит первый; `duration_ms` в `result` — 4671
и 2449 мс, весь тест с запуском JVM агента и proot — 38,7 с. Поток
stream-json из proot разбирается тем же `ClaudeStreamParser`.

**`CLAUDE_CONFIG_DIR` после входа без него** — проверено **7 октября
2026** там же (2.1.292, proot): вход сделан без переменной (каталог
`/root/.claude`, файл `~/.claude.json`). `claude auth status --json` с
`CLAUDE_CONFIG_DIR=/root/.claude` — `loggedIn: true`, `claude.ai`, но
`email` и `orgId` — `null`, а в stderr — «Claude configuration file not
found at: /root/.claude/.claude.json»: файл настроек ищется в каталоге
конфигурации. Вывод: вход слота делается сразу с `CLAUDE_CONFIG_DIR`
слота (D-29), иначе отпечаток аккаунта неизвестен.


## 11. Расход и управление расходом

Проверено **8 октября 2026** по `claude --help` Claude Code **2.1.294**
(среда облачной сессии) и образцу `success-structured.jsonl` (2.1.291).
Для D-40 (`docs/EFFICIENCY.md`).

Флаги из справки 2.1.294:

| Флаг | Справка | Для оркестратора |
|---|---|---|
| `--effort <level>` | «Effort level for the current session (low, medium, high, xhigh, max)» | усилие по классу задачи |
| `--model <model>` | алиас последней модели («'fable', 'opus', or 'sonnet'») или полное имя | модель по классу задачи |
| `--fallback-model <model>` | запасные модели при перегрузке или недоступности, список через запятую | — |
| `--exclude-dynamic-system-prompt-sections` | каталог, окружение, пути памяти и `git status` уходят из системного prompt в первое сообщение: «Improves cross-user prompt-cache reuse»; игнорируется с `--system-prompt`, по умолчанию выключен | кеш prompt между сессиями разных рабочих деревьев |
| `--autocompact <auto\|tokens>` | размер окна автосжатия: `auto` или 100k–1M токенов | не используется |

Флага `--max-turns` в справке 2.1.294 нет: длину хода ограничивают
`--max-budget-usd` и тайм-аут хода.

Поля итога `result` (образец 2.1.291): `usage` — `input_tokens`,
`output_tokens`, `cache_creation_input_tokens`, `cache_read_input_tokens`,
`output_tokens_details.thinking_tokens`, `cache_creation` (запись на 5
минут и на час), `iterations` — по вызову API с тем же набором токенов;
`modelUsage` — по модели: `inputTokens`, `outputTokens`,
`cacheReadInputTokens`, `cacheCreationInputTokens`, `costUSD`,
`contextWindow` (200 000), `maxOutputTokens` (32 000), `costBasis`;
`num_turns`, `duration_ms`, `duration_api_ms`; `subagent_stats` — `spawned`,
отказы по глубине, параллельности и бюджету. В образце первый ход
записал в кеш 9 844 токена (`ephemeral_1h_input_tokens`) — системный
prompt и инструменты.

Неизвестный флаг Claude Code (commander.js) отвергает строкой «error:
unknown option '--…'» и кодом выхода 1 — до вызова API (поведение
commander.js; образца отказа с target нет). На это опирается отказ без
флага на старом Claude Code (`docs/EFFICIENCY.md`, §7).

Инструмент субагентов в Claude Code 2.1.294 называется `Agent` (список
инструментов этой облачной сессии Claude Code).

Вызовы инструментов в потоке (образец 2.1.291, D-41): блок `tool_use`
сообщения `assistant` — `id`, `name`, `input`; результат — сообщение
`user` с блоком `tool_result` (`tool_use_id`, `content` строкой; по
справке API — и массивом блоков `text`, `is_error` при ошибке) и полем
`tool_use_result` — сырой итог для клиента, в контекст модели он не
идёт. У сообщений субагента `parent_tool_use_id` — id вызова `Agent`.
Ход с инструментами разработки (`Read`, `Edit`, `Bash`) не записан —
`tool-calls.jsonl` построен по этой форме.

Не проверено (образцов нет): событие сжатия истории — `system` с
`subtype: "compact_boundary"` (описание `SDKCompactBoundaryMessage` в
Agent SDK); отказ по размеру prompt — итог с ошибкой и текстом «Prompt is
too long» (текст `invalid_request_error` API). Разбор узнаёт оба, строки в
тестах построены; записать образцы, когда случатся.
