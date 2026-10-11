# Этап 4 — Claude Runner

Состояние: выполнен (6 октября 2026).

## Цель

`ClaudeRunner` — единый интерфейс сессии Claude: `start`, `send`,
`stream`, `pause`, `cancel`, `resume`, `inspect`, `terminate` (ТЗ, §12,
§139, Phase 4).

## Объём

- Решение «Отложено»: запуск Claude Agent SDK с JVM.
- `ClaudeCodeCliRunner`: `--print --output-format stream-json`,
  `--session-id` и `--resume`, `--max-budget-usd`, `--json-schema`
  (`docs/research/CLAUDE_CODE_CLI.md`).
- Разбор потока событий провайдера в события оркестратора; испорченный,
  неполный, повторный вывод — диагностика, а не догадка (ТЗ, §106).
- `MockRunner` со сценариями ТЗ, §89: успех, отказ, тайм-аут, падение,
  медленный вывод, повтор и пропуск событий, rate limit, ошибка
  аутентификации, частичное завершение.
- `providerSessionId` хранится отдельно от `SessionId` (D-08).

## Критерии выхода

- [x] Синтетический сценарий Claude проходит от начала до конца на
      `MockRunner` (ТЗ, §139, Phase 4): `MockRunnerTest`.
- [x] Разбор потока CLI на записанных образцах вывода (корпус
      `tests/data/`), включая испорченные: `ClaudeStreamTest`.
- [x] Каждый сценарий `MockRunner` из ТЗ, §89 даёт ожидаемое состояние
      и диагностику: `MockRunnerTest`.
- [x] Адаптер вызывает только документированные флаги (D-04):
      `MockRunnerTest` сверяет с таблицами исследования.
- [x] Вывод Claude проходит `Redactor` до того, как становится событием
      журнала: ключ в выводе не попадает на диск (D-14, T8):
      `AgentRuntimeTest`.
