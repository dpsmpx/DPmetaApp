# Форматы данных

Контракт для читателей (DPmetaApp, сайт автора). В пределах версии поля
только добавляются; удаление или смена смысла — новая версия и строка в
истории ниже (D-07).

- `dpvis-model/1` — модель (`data/model.json`), схема —
  `schemas/dpvis-model-1.schema.json`.
- `dpvis-state/1` — срез (`data/state/latest.json`), схема —
  `schemas/dpvis-state-1.schema.json`.

Поля и типы — в схемах; ниже — смысл. Читатель игнорирует незнакомые
поля (D-07): так новые поля версии `/1` ничего у него не ломают.

## `dpvis-model/1` — модель

Один объект. Обязательные поля: `schema`, `title`, `as_of` (дата сверки
модели с источниками), `timezone` (`+03:00` — пояс дней), `source`
(`path` и `sha256` TOML-источника — по нему срез узнаёт «свою» модель),
`allow` (`hosts` — куда разрешены пробы), `catalog`, `layers`, `nodes`,
`flows`. Необязательные: `sources` (откуда сверена модель), `registry`
(где читать реестр и контракты DPmeta).

| Часть | Смысл |
|---|---|
| `catalog` | подписи каталогов (виды узлов и потоков, кто движет, состояния, транспорт, видимость, группы) — читатель не держит их у себя; у вида потока — `driver` по умолчанию |
| `layers` | слои по порядку: колонки схемы слева направо (на узком экране — полосы сверху вниз) |
| узел | `id`, `name`, `kind`, `layer`, `role`, `status` (`active`, `planned`, `blocked`, `off`), `visibility` (`public`, `private`, `undisclosed`), `refs` — источники `Проект:путь` или `Проект:номер`; у репозитория — `repo` (slug GitHub), `group`, необязательно `clone`; у сервиса — `probes`; ещё `stage`, `note`, `row`, `public_role`, `public_stage` |
| поток | `id`, `from`, `to`, `kind`, `driver` (`human`, `ai`, `check`, `data` — цвет), `status`, `what`, `transport`, `bidirectional`, `contracts` (`DPmeta:C-NN`), `refs`; ещё `trigger`, `format`, `note`, `via`, `public_what`, `signal` |
| `signal` | как поток наблюдается в данных: `git-commits` (`repo`, `who`, `scope`), `git-path` (`repo`, `paths`), `probe` (`node`); нет сигнала — поток «не наблюдается» |

## `dpvis-state/1` — срез

Один объект: `schema`, `as_of` (время среза, UTC), `generated_by` (`tool`,
`version`, `rules_version`), `model` (`sha256` и `as_of` модели, по
которой собран), `options` (`online`, `fetch`, `days`), `window` (`start`,
`end` — даты по `timezone`, `days`), `rules` (пороги), `nodes`, `flows`,
`registry`, `diagnostics`.

| Часть | Смысл |
|---|---|
| `nodes.<id>.activity` | `state` — `working`, `idle`, `up`, `down`, `planned`, `blocked`, `off`, `unknown`, `unobserved`; `last` — время последнего события; `reasons` — какое правило сработало (`main`, `branch`, `probe`…) |
| `nodes.<id>.git` | у репозитория с клоном: `ref`, `head`, `head_time`, `first_time`, `history` (`complete`, `shallow`, `partial`), `days` (`ai`, `human`, `merges` по дням окна основной ветки), `days_all` (`ai`, `human` по всем веткам), `window` (суммы), `branches` (`name`, `ahead`, `time`, `method`) |
| `nodes.<id>.days` | у участника: события по дням окна |
| `nodes.<id>.probe` | у сервиса при `--online`: `checked` и `results` (`url`, `status`, `ms`, `headers`, `error`) |
| `flows.<id>` | `state` — `flowing`, `quiet`, `planned`, `blocked`, `off`, `down`, `unknown`, `unobserved`; при сигнале — `signal`, `days`, `total`, `week`, `last`; при `unknown` — `reason` |
| `registry` | сверка с DPmeta: `checked`, `ref`, `registry_repos`, `missing_in_model`, `not_in_registry`, `contracts`, `contracts_covered` |
| `diagnostics` | `{code, subject, message}`; коды — ТЗ §6.7, только добавляются |

«Неизвестно» (`unknown`) и «не наблюдается» (`unobserved`) — не ноль:
первое — сигнал есть, наблюдение не удалось; второе — сигнала нет по
устройству (G-06).

## `dpvis-public/1` — публичная выжимка для сайта (D-15)

Один объект `{schema, model, state}`. Строит
`python3 -m tools.dpvis build --site <каталог>` (файл `dpvis-data.json`) или
`build --public` (внутри одного HTML). Белый список полей — в
`tools/dpvis/public.py`; всё, чего в списке нет, в выжимку не попадает.

| Часть | Поля |
|---|---|
| `model` | `title`, `as_of`, `timezone`, `layers` (только непустые), `catalog` (`node_kinds`, `flow_kinds`, `drivers`, `statuses`, `visibility`, `groups` — только используемое), `nodes`, `flows`, `hidden` (`{undisclosed: N}`, если есть) |
| узел | `id`, `kind`, `layer`, `name`, `group`, `status`, `visibility`; `role` ← `public_role`, `stage` ← `public_stage` модели |
| поток | `id`, `from`, `to`, `kind`, `driver`, `status`, `bidirectional`; `what` ← `public_what` модели |
| `state` | `as_of`, `generated_by`, `window`, `rules` (`rate_days`, `flow_recent_hours`), `nodes`, `flows` |
| состояние узла | `activity` (`state`, `last` — дата; у участников `null`); у репозитория `git`: `days` (`ai`, `human` по дням окна), `first_date`, `head_date`, `window` (`ai`, `human`) |
| состояние потока | `state`, `days`, `week`, `total`, `last` (дата) |

Чего нет никогда:
- слоя ранних и малых открытых проектов;
- узлов `undisclosed`, устройств и личных каналов;
- веток, вливаний, хешей;
- путей передачи и триггеров;
- ссылок на источники, контрактов, сигналов, проб, адресов, реестра и диагностики;
- дневной активности участников;
- времени точнее даты;
- рабочих текстов модели (`role`, `stage`, `what`): только публичные
  формулировки `public_*`; нет формулировки — поле не выводится.

В модели (`dpvis-model/1`) `public_role`, `public_stage` и `public_what` —
необязательные непустые строки рядом с рабочими полями.

Каждая сборка проверяется по `FORBIDDEN`: находка — отказ.

## `dpvis-site-kit/1` — `MANIFEST.json` комплекта для сайта

`{schema, data_as_of, model_as_of, files: {имя: sha256}}` — по нему сайт
сверяет, что встроил тот самый комплект.

## История

| Дата | Что |
|---|---|
| 6 октября 2026 | `dpvis-public/1` и `dpvis-site-kit/1` (D-15) |
| 6 октября 2026 | модель: `public_role`, `public_stage`, `public_what`; тексты выжимки — только из них (D-15) |
| 9 октября 2026 | пояснения к `dpvis-model/1` и `dpvis-state/1`; полей не изменилось |
