# Codex CLI как интерфейс исполнителя

Датированные факты о неинтерактивном интерфейсе OpenAI Codex CLI, на
которые опирается исполнитель Codex (D-100). Факт без даты и версии сюда
не пишется; изменился интерфейс — новая строка с новой датой, старая
остаётся.

## 1. Источник и дата

Проверено **3 ноября 2026** по `codex --help`, `codex exec --help`,
`codex exec resume --help` и `codex login --help` пакета npm
`@openai/codex` **0.166.0** (`codex-cli 0.166.0`, двоичный файл
`x86_64-unknown-linux-musl`) в облачной сессии, а также запуском без
входа (§4). Пакет ставит двоичный файл под платформу:
`@openai/codex-linux-x64` и т. п.; сборки — musl, то есть годятся для
Alpine. Сборку под arm64 в Alpine приложения не проверяли (§6).

## 2. Флаги

| Флаг | Что даёт исполнителю | Справка |
|---|---|---|
| `codex exec [PROMPT]` | неинтерактивный ход | «Run Codex non-interactively» |
| `-` вместо PROMPT | задание — со стандартного ввода | «If not provided as an argument (or if `-` is used), instructions are read from stdin» |
| `--json` | поток событий JSONL на stdout | «Print events to stdout as JSONL» |
| `codex exec resume [SESSION_ID] [PROMPT]` | продолжение сессии | «Resume a previous session by id or pick the most recent with --last» |
| `--last` (у `resume`) | последняя сессия этого рабочего каталога | список отфильтрован по cwd; `--all` — без фильтра |
| `--ephemeral` | сессия не пишется на диск | «Run without persisting session files to disk» |
| `-m`, `--model` | модель | у `exec` и `resume` |
| `-c key=value` | настройка из `config.toml`; значение — TOML | у `exec` и `resume` |
| `-c sandbox_mode="…"` | песочница команд: `read-only`, `workspace-write`, `danger-full-access` | у `exec` есть и `-s`, у `resume` — только через `-c` |
| `--skip-git-repo-check` | ход вне репозитория Git | «Allow running Codex outside a Git repository» |
| `--output-schema <FILE>` | схема итогового ответа — файлом | не используется: файл пришлось бы класть на target |
| `codex login --device-auth` | вход ChatGPT кодом устройства: без браузера на этом же устройстве | вариант `codex login` |
| `codex login status` | состояние входа; код выхода 0 — вход есть, 1 — нет | «Show login status» |
| `--with-api-key` (у `login`) | вход ключом API | исполнителю запрещён (D-100) |

Каталог настроек и входа — переменная окружения `CODEX_HOME`, по
умолчанию `~/.codex` (в двоичном файле — строка `CODEX_HOME`, предупреждение
«CODEX_HOME points to … but that path does not exist»). У каждого слота
свой `CODEX_HOME`, как `CLAUDE_CONFIG_DIR` у Claude.

## 3. Поток `--json`

Наблюдено 3 ноября 2026 (запуск без входа, §4) и по строкам двоичного
файла:

| Событие | Поля, которые читаются |
|---|---|
| `thread.started` | `thread_id` — id сессии Codex (назначает Codex, не вызывающий) |
| `turn.started` | — |
| `item.started`, `item.updated`, `item.completed` | `item.type`: `agent_message` (`text`), `reasoning` (`text`), `command_execution` (`command`, `aggregated_output`, `exit_code`, `status`), `file_change` (`changes[].path`, `kind`), `mcp_tool_call`, `web_search`, `todo_list`, `error` (`message`) |
| `turn.completed` | `usage.input_tokens`, `usage.cached_input_tokens`, `usage.output_tokens` |
| `turn.failed` | `error.message` |
| `error` | `message` — в том числе промежуточные «Reconnecting… n/5» |

Цены в потоке нет: стоимость хода Codex — неизвестна (D-13). Пределов
подписки (окна пяти часов и недели) в потоке `exec --json` нет; отказ по
пределу — текст «You've hit your usage limit…» в ошибке.

## 4. Проверено запуском

3 ноября 2026, 0.166.0, без входа, пустой `CODEX_HOME`:
`codex login status` — «Not logged in», код 1. `echo hi | codex exec --json
--skip-git-repo-check -c 'sandbox_mode="workspace-write"' -` —
`thread.started`, `turn.started`, серия `error` «Reconnecting… n/5
(unexpected status 401 Unauthorized …)», `item.completed` с `item.type =
"error"`, затем `turn.failed` с тем же 401; код выхода 1. Синтетические
образцы формата — `tests/data/codex-stream/`.

## 5. Продолжение сессии

Codex сам назначает id сессии (`thread_id`); `--session-id` у него нет.
Исполнитель (D-100) продолжает сессию `codex exec resume --last` в
каталоге задачи: у задачи своё рабочее дерево, у слота — свой
`CODEX_HOME`, поэтому последняя сессия этого каталога в этом
`CODEX_HOME` — её сессия. Ход ревью идёт с `--ephemeral` и в этот список
не попадает.

## 6. Не проверено

- Вход ChatGPT (`--device-auth`) и ход с настоящей подпиской — у
  облачной сессии нет аккаунта; проверка — `docs/DEVICE_CHECK.md`, §20.
- Песочница `workspace-write` под proot на Android: она опирается на
  Landlock и seccomp ядра; не работает — команды хода отказывают, а
  правка файлов — нет. Проверка — там же.
- Сборка `@openai/codex` для arm64 в Alpine приложения.
