# Корпус `codex exec --json`

Образцы потока Codex CLI для перевода в нормальную форму хода
(`CodexNormalizer`, D-100; `docs/research/CODEX_CLI.md`, §3). Новый баг
перевода — новый файл здесь.

| Файл | Откуда | Что |
|---|---|---|
| `success.jsonl` | построен по формату 0.166.0 | `thread.started`, рассуждение, команда, правка файла, ответ, `turn.completed` с расходом и кешем |
| `auth-failed.jsonl` | записан 3 ноября 2026, 0.166.0, пустой `CODEX_HOME`; адреса, `cf-ray` и id запросов заменены | серия `error` 401, `item.completed` с `type: error`, `turn.failed` |
| `usage-limit.jsonl` | построен | `turn.failed` с «You've hit your usage limit» |
| `no-result.jsonl` | построен | поток кончился после команды — процесс упал |
