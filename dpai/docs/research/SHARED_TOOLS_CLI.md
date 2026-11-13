# Дополнительная папка инструментов исполнителей — 11 ноября 2026

D-122, D-123. Проверены официальные источники; приватный код проектов в запросы
не передавался. Это сведения о CLI, не доказательство приёмки телефона.

| Исполнитель | Механизм | Источник |
|---|---|---|
| Claude Code | `--add-dir` уже поддерживается нашим адаптером | `docs/research/CLAUDE_CODE_CLI.md` |
| Codex | `--add-dir` до `resume`, добавляет путь, сохраняя прежние writable_roots | [общие опции](https://github.com/openai/codex/blob/main/codex-rs/utils/cli/src/shared_options.rs), blob `5e525662d49ce0697ff6965473be178378443776`; [exec CLI](https://github.com/openai/codex/blob/main/codex-rs/exec/src/cli.rs), blob `5ada863143608639c9f538c7ada72b531561b474`; `docs/research/CODEX_CLI.md` |
| Gemini CLI | `--include-directories`, массив путей | [официальный разбор аргументов](https://github.com/google-gemini/gemini-cli/blob/main/packages/cli/src/config/config.ts), blob `23d679a5236b348c7be5b424d4be7cea6c2fb3c1`, строки 469–476 |

Для Codex дополнительный каталог не меняет sandbox_mode: read-only
остаётся read-only. Путь передаётся отдельным argv, shell не используется.
У Gemini сохраняется plan для ревью.
Исходное поручение дополняется контекстом инструментов, исходные
параметры слота, cwd и stdin сохраняются.
