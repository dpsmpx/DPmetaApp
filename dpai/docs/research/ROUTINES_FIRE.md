# Маршруты Claude Code: API-триггер `/fire`

Датированные факты о запуске маршрута (routine) Claude Code из своей
программы, на которые опираются `RoutineClient` (модуль `providers`) и
решение D-116. Изменился контракт — новая строка с новой датой, старая
остаётся.

## 1. Источник и дата

Проверено **9 ноября 2026** по документации Claude Code
`https://code.claude.com/docs/en/routines` (раздел об API-триггере) —
первичный источник. Раньше те же факты сверял DPmetaApp 5 октября 2026
(`DPmetaApp:docs/direction/TECHNICAL_TASK.md`, приложение В). Маршруты в
документации теперь называются «scheduled tasks (formerly routines)»;
адрес и поля прежние. Функция — исследовательская версия: контракт может
меняться.

## 2. Запрос

| Что | Значение |
|---|---|
| Метод и адрес | `POST https://api.anthropic.com/v1/claude_code/routines/<trig_…>/fire` |
| Ключ | `Authorization: Bearer <токен маршрута>`; токен — свой у каждого API-триггера, только запуск этого маршрута |
| Версия | `anthropic-version` обязателен (любая версия API не старше `2023-06-01`) |
| Бета | `anthropic-beta: experimental-cc-routine-2026-04-01` — необязателен |
| Тело | JSON; поле `text` необязательно, до 65 536 знаков |
| Повтор | ключа идемпотентности нет: каждый вызов открывает новую сессию |

## 3. Ответ

- `200`: `{"type": "routine_fire", "claude_code_session_id": "session_…",
  "claude_code_session_url": "https://claude.ai/code/session_…"}`.
- Отказы — JSON `{"type": "error", "error": {"type", "message"}}`:
  `400 invalid_request_error` (нет версии, длинный `text`, маршрут на
  паузе), `401 authentication_error`, `403 permission_error`,
  `404 not_found_error`, `429 rate_limit_error` с `Retry-After`,
  `500 api_error`, `503 overloaded_error`.

## 4. Пределы и смысл поля `text`

- 30 запусков в час на маршрут (вместе с «Run now»), 100 запусков через
  API в час на аккаунт.
- Планы: Pro, Max, Ultra, Team, Enterprise.
- `text` приходит в сессию блоком `<routine-fire-payload>` и считается
  недоверенными данными: действовать по нему Claude станет, только если
  сохранённый промпт маршрута это явно разрешает.
- Токен показывается один раз при создании триггера; отозвать или
  выпустить новый — в настройках маршрута на claude.ai.
