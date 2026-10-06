# Форматы данных

Контракт для читателей (DPmetaApp, сайт автора). В пределах версии поля
только добавляются; удаление или смена смысла — новая версия и строка в
истории ниже (D-07).

- `dpvis-model/1` — модель (`data/model.json`), схема —
  `schemas/dpvis-model-1.schema.json`.
- `dpvis-state/1` — срез (`data/state/latest.json`), схема —
  `schemas/dpvis-state-1.schema.json`.

Полное описание полей этих двух форматов — в схемах; разделы с
пояснениями — долг этапа 1 (`ROADMAP/ROADMAP.md`).

## `dpvis-public/1` — публичная выжимка для сайта (D-15)

Один объект `{schema, model, state}`. Строит
`python3 -m tools.dpvis build --site <каталог>` (файл `dpvis-data.json`) или
`build --public` (внутри одного HTML). Белый список полей — в
`tools/dpvis/public.py`; всё, чего в списке нет, в выжимку не попадает.

| Часть | Поля |
|---|---|
| `model` | `title`, `as_of`, `timezone`, `layers` (только непустые), `catalog` (`node_kinds`, `flow_kinds`, `drivers`, `statuses`, `visibility`, `groups` — только используемое), `nodes`, `flows`, `hidden` (`{undisclosed: N}`, если есть) |
| узел | `id`, `kind`, `layer`, `name`, `group`, `role`, `stage`, `status`, `visibility` |
| поток | `id`, `from`, `to`, `kind`, `driver`, `status`, `what`, `bidirectional` |
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
- времени точнее даты.

Каждая сборка проверяется по `FORBIDDEN`: находка — отказ.

## `dpvis-site-kit/1` — `MANIFEST.json` комплекта для сайта

`{schema, data_as_of, model_as_of, files: {имя: sha256}}` — по нему сайт
сверяет, что встроил тот самый комплект.

## История

| Дата | Что |
|---|---|
| 6 октября 2026 | `dpvis-public/1` и `dpvis-site-kit/1` (D-15) |
