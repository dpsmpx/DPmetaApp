# Gemini CLI как интерфейс исполнителя

Датированные факты о неинтерактивном интерфейсе Google Gemini CLI, на
которые опирается исполнитель Gemini (D-100). Факт без даты и версии сюда
не пишется; изменился интерфейс — новая строка с новой датой, старая
остаётся.

## 1. Источник и дата

Проверено **3 ноября 2026** по `gemini --help` пакета npm
`@google/gemini-cli` **0.66.0** (Node.js не ниже 20) в облачной сессии,
по исходному коду пакета (`bundle/`) и запуском без входа (§4).

## 2. Флаги

| Флаг | Что даёт исполнителю | Справка |
|---|---|---|
| `-p`, `--prompt` | неинтерактивный ход | «Run in non-interactive (headless) mode … Appended to input on stdin (if any)» |
| `-o`, `--output-format stream-json` | поток событий JSONL | варианты: `text`, `json`, `stream-json` |
| `--session-id <uuid>` | вызывающий сам назначает id сессии; занятый id — отказ | «Start a new session with a manually provided UUID» |
| `-r`, `--resume <id>` | продолжение: UUID, `latest` или номер | `SessionSelector.findSession` ищет сначала по UUID |
| `-m`, `--model` | модель | |
| `--approval-mode` | `default`, `auto_edit` (правки без вопроса), `yolo` (всё без вопроса), `plan` (только чтение) | |
| `--skip-trust` | доверить текущую папку на эту сессию | без него в «недоверенной» папке режим одобрений сбрасывается в `default` (§4) |

Задание идёт стандартным вводом: текст stdin ставится перед значением
`-p` через пустую строку (`injectStdinIntoArgs`). Исполнитель передаёт
`-p` с пробелом — к заданию добавляются только пробельные знаки.

Каталог настроек — `$GEMINI_CLI_HOME/.gemini` (функция `homedir()`: при
заданной `GEMINI_CLI_HOME` — она, иначе домашний каталог). У каждого
слота свой `GEMINI_CLI_HOME`. Способ входа — `security.auth.selectedType`
в `settings.json` (`oauth-personal` — вход аккаунтом Google); данные
входа — `oauth_creds.json` там же. Переменные, которые перекрывают вход
аккаунтом: `GEMINI_API_KEY`, `GOOGLE_API_KEY`, `GOOGLE_GENAI_USE_VERTEXAI`,
`GOOGLE_GENAI_USE_GCA`, `GOOGLE_APPLICATION_CREDENTIALS`,
`GOOGLE_CLOUD_PROJECT`. `NO_BROWSER=true` — вход без браузера на этом
устройстве: ссылка и ввод кода в терминале.

## 3. Поток `stream-json`

По исходному коду 0.66.0 (`JsonStreamEventType`):

| Событие | Поля |
|---|---|
| `init` | `session_id`, `model` |
| `message` | `role` (`user`, `assistant`), `content`, `delta: true` — кусок ответа |
| `tool_use` | `tool_name`, `tool_id`, `parameters` |
| `tool_result` | `tool_id`, `status` (`success`, `error`), `output`, `error` |
| `error` | `severity`, `message` |
| `result` | `status` (`success`, `error`), `error.type`, `error.message`, `stats` (`input_tokens`, `output_tokens`, `total_tokens`, `duration_ms`, `tool_calls`) |

Цены в потоке нет: стоимость хода — неизвестна (D-13). Предел квоты —
ошибка `429`/`RESOURCE_EXHAUSTED`/«Quota exceeded».

## 4. Проверено запуском

3 ноября 2026, 0.66.0, без входа:
- без `settings.json` — «Please set an Auth method …», код 41;
- в папке без доверия — «Approval mode overridden to "default" because the
  current folder is not trusted»;
- с `selectedType: oauth-personal`, без `oauth_creds.json`, с
  `--skip-trust` — в stderr «Error authenticating: FatalAuthenticationError:
  Manual authorization is required but the current session is
  non-interactive», событий в stdout нет.

Синтетические образцы формата — `tests/data/gemini-stream/`.

## 5. Не проверено

- Вход аккаунтом Google и ход с настоящей квотой — у облачной сессии нет
  аккаунта; проверка — `docs/DEVICE_CHECK.md`, §20.
- Gemini CLI под Node.js Alpine в приложении (proot).
