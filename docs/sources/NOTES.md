# Заметки о наблюдениях

Датированные записи о том, что наблюдалось без постоянного адреса
(ответ API, слова автора в сессии), — с происхождением и способом
перепроверки (`docs/sources/CITATION.md`). Заметка не меняется после
коммита; уточнение — новая заметка. Форму проверяет
`tools/verify/check_registry.py`.

Поля: `source` — источник из реестра; `observed` — дата наблюдения;
`origin` — как получено; `content` — что получено, дословно для чисел и
дат; `verify` — как перепроверить.

## Заметки

### N-0001

- source: S-10
- observed: 2026-10-04
- origin: GitHub REST API, данные пользователя dpsmpx; получены инструментом сессии Claude Code (вызов get_me) при создании этого репозитория
- content: login dpsmpx; created_at 2023-03-03T23:22:34Z; public_repos 20
- verify: https://api.github.com/users/dpsmpx — поля created_at и public_repos (public_repos меняется со временем)
