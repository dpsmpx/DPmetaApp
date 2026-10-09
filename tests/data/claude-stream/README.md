# Корпус `stream-json` Claude Code

Образцы вывода `claude -p --verbose --output-format stream-json` для
разбора `ClaudeStreamParser` (этап 4; `docs/TESTING.md`, §6). Новый баг
разбора — новый файл здесь.

Записано **6 октября 2026**, Claude Code **2.1.291**, Windows 11. Перед
записью в репозиторий из образцов убраны пути, имена подключений, списки
команд и подписи; идентификаторы сессий и событий заменены
(`docs/research/CLAUDE_CODE_CLI.md`, §7).

| Файл | Откуда | Что |
|---|---|---|
| `success-structured.jsonl` | записан: `--json-schema`, `--tools ""`, модель Haiku 4.5 | `system/init`, `system/thinking_tokens`, ответ через `StructuredOutput`, `rate_limit_event` с окнами пяти часов и недели, `result` с `structured_output` и стоимостью |
| `auth-failed.jsonl` | записан: пустой `CLAUDE_CONFIG_DIR` | `assistant` с `error: authentication_failed`, `result` с `is_error: true` при `subtype: success`; код выхода 1 |
| `truncated-result.jsonl` | построен из записанного | последняя строка оборвана посередине |
| `garbage-line.jsonl` | построен | строка не JSON посреди потока |
| `duplicate-event.jsonl` | построен | то же событие (тот же `uuid`) дважды |
| `no-result.jsonl` | построен | поток кончился без `result` — процесс упал |
| `session-mismatch.jsonl` | построен | `session_id` в `init` не тот, что назначил оркестратор |
| `rate-limited.jsonl` | построен по полям записанных `rate_limit_event` и ошибки входа | `rate_limit_event` со `status: rejected`, `assistant` с `error: rate_limit` |
| `usage-local.jsonl` | записан 9 октября 2026, Claude Code 2.1.294, Termux на Redmi Note 13: `echo /usage \| claude -p --verbose --output-format stream-json`; без `system`-строк, id заменены | локальная команда `/usage`: `assistant` с моделью `<synthetic>` и нулевым `usage`, поле `usage_report.rate_limits.limits` (вид окна, процент, время сброса), `result` с `total_cost_usd: 0` — к модели запроса нет (D-48) |
| `tool-calls.jsonl` | построен по форме `tool_use` и `tool_result` записанного `success-structured.jsonl` (D-41) | чтение, поиск, правка, команда с ошибкой, повторное чтение до и после правки, субагент со своим вызовом (`parent_tool_use_id`), `content` строкой и блоками `text` |

Построенные по догадке `rate-limited.jsonl` и `tool-calls.jsonl` — не
факт о Claude Code: как на деле выглядит отказ по пределу подписки и
поток хода с инструментами разработки, не проверено
(`docs/research/CLAUDE_CODE_CLI.md`, §7, §11). Появится настоящий
образец — он ляжет рядом, а построенный файл уйдёт.
