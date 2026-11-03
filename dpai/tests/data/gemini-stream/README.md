# Корпус `gemini --output-format stream-json`

Образцы потока Gemini CLI для перевода в нормальную форму хода
(`GeminiNormalizer`, D-100; `docs/research/GEMINI_CLI.md`, §3). Новый баг
перевода — новый файл здесь.

| Файл | Откуда | Что |
|---|---|---|
| `success.jsonl` | построен по формату 0.66.0 | `init`, куски ответа, чтение и правка файла, команда, `result` со статистикой |
| `quota.jsonl` | построен | `result` с ошибкой квоты (`RESOURCE_EXHAUSTED`) |
| `auth-stderr.txt` | записан 3 ноября 2026, 0.66.0, без входа; пути заменены | stderr «FatalAuthenticationError» — событий в stdout нет |
