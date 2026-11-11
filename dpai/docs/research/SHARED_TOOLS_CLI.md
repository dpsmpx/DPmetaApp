# Дополнительная папка инструментов исполнителей — 11 ноября 2026

D-122. Проверены официальные источники; приватный код проектов в запросы
не передавался. Это сведения о CLI, не доказательство приёмки телефона.

| Исполнитель | Механизм | Источник |
|---|---|---|
| Claude Code | `--add-dir` уже поддерживается нашим адаптером | `docs/research/CLAUDE_CODE_CLI.md` |
| Codex | настройка `sandbox_workspace_write.writable_roots`, массив абсолютных путей; `-c` поддерживается и exec, и resume | [официальная схема](https://github.com/openai/codex/blob/main/codex-rs/core/config.schema.json), blob `14e34b3f4658c8a6d1d2a2ad8b64d185686a2cf8`, определение SandboxWorkspaceWrite; `docs/research/CODEX_CLI.md` |
| Gemini CLI | `--include-directories`, массив путей | [официальный разбор аргументов](https://github.com/google-gemini/gemini-cli/blob/main/packages/cli/src/config/config.ts), blob `23d679a5236b348c7be5b424d4be7cea6c2fb3c1`, строки 469–476 |

Для Codex дополнительная настройка не меняет sandbox_mode: read-only
остаётся read-only. Путь сериализуется как JSON/TOML-строка внутри
одного argv, shell не используется. У Gemini сохраняется plan для ревью.
Исходное поручение дополняется контекстом инструментов, исходные
параметры слота, cwd и stdin сохраняются.
