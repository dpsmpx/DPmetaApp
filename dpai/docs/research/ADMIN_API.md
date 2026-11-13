# Admin API Claude: расход, стоимость и лимиты — только чтение

Датированные факты об отчётах Admin API, на которые опираются
`AdminClient` (модуль `providers`) и решения `DPmetaApp:D-44` (Admin-ключ
— только чтение) и `DPmetaApp:D-81` (свой HTTP, без SDK). Изменился
контракт — новая строка с новой датой, старая остаётся.

## 1. Источник и дата

Проверено **9 ноября 2026** по документации Claude Platform:
`https://platform.claude.com/docs/en/manage-claude/usage-cost-analytics-api`,
`https://platform.claude.com/docs/en/manage-claude/rate-limits-api`,
`https://platform.claude.com/docs/en/manage-claude/usage-cost-api`.

## 2. Ключ и доступ

- Admin API недоступен индивидуальным аккаунтам: нужна организация в
  Claude Console.
- Ключ — Admin API key (`sk-ant-admin01-…`) заголовком `x-api-key`;
  ключ workspace не подходит. Плюс `anthropic-version` (в примерах —
  `2026-10-21`).

## 3. Расход и стоимость — Usage and Cost Analytics API (бета)

| Что | Значение |
|---|---|
| Расход | `GET https://api.anthropic.com/v1/organizations/analytics/usage` |
| Стоимость | `GET https://api.anthropic.com/v1/organizations/analytics/cost` |
| Бета | `anthropic-beta: analytics-usage-cost-2026-08-24` — в каждом запросе |
| Диапазон | `starting_at` и `ending_at` (RFC 3339, UTC); не раньше 1 января 2026, в пределах 365 дней, не больше 31 дня за запрос; с `time_bucket_width` `ending_at` обязателен |
| Корзины | `time_bucket_width` — `1m`, `1h` или `1d` (по UTC); без него — итог за весь диапазон |
| Группы | `group_by[]` — до пяти измерений (`model`, `workspace_id`, `api_key_id`, `service_tier`…) |
| Порядок | `order_by` — для расхода `total_tokens` (по умолчанию), `output_tokens`, `uncached_input_tokens`, `requests`, `time`; для стоимости — `amount`, `list_amount`, `time`; `order=asc` |
| Страницы | `limit` 1…1000 строк (по умолчанию 20), `page` = `next_page` прошлого ответа, `has_more` |

Строка ответа: `starting_at`, `ending_at`, `group` (измерения; не
сгруппированное — `null`) и `usage` — `uncached_input_tokens`,
`cache_creation.ephemeral_1h_input_tokens`,
`cache_creation.ephemeral_5m_input_tokens`, `cache_read_input_tokens`,
`output_tokens`, `total_tokens`, `requests`, `server_tool_use` — или
`cost` — `amount` и `list_amount` (десятичные строки в центах, шесть
знаков после точки: `"41280.000000"` — 412,80 USD), `currency` (`USD`),
`requests`. В ответе — `data_refreshed_at`: данные приходят с задержкой
обычно до 4 часов, до 24 часов; значения могут уточняться до ~7 дней после
конца месяца.

Прежний Usage & Cost API (`/v1/organizations/usage_report/messages`,
`/v1/organizations/cost_report`) — устаревший, но поддерживается.

## 4. Лимиты скорости — Rate Limits API

- `GET https://api.anthropic.com/v1/organizations/rate_limits` (по
  желанию `?model=<id>`), без `limit` — все записи одной страницей.
- Запись: `id` (`rlt_…`), `group` (`type`: `model_group`, `batch`…,
  `display_name`), `models`, `limits` — `type`
  (`requests_per_minute`, `input_tokens_per_minute`,
  `output_tokens_per_minute`) и `value`.
- Изменение лимитов workspace — `POST …/workspaces/{id}/rate_limits/{rlt}`:
  изменяющий вызов, в приложении его нет (`DPmetaApp:D-44`).
