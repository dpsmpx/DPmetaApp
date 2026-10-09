# Комплект визуализации экосистемы (Д43)

Файлы этого каталога собраны в репозитории DPvisualisation командой
`python3 -m tools.dpvis build --site <каталог>` (DPvisualisation D-15) и
скопированы сюда без правок. Сборка сайта сверяет их sha256 с
`MANIFEST.json`: несовпадение — ошибка сборки, руками файлы не править.

| Файл | Что |
|---|---|
| `dpvis.js` | просмотрщик — склейка модулей `viewer/src/*.js`, без зависимостей |
| `dpvis.css` | стили просмотрщика (всё внутри `.dpvis`) |
| `dpvis-data.json` | публичная выжимка `dpvis-public/1`: модель и срез без закрытых подробностей |
| `MANIFEST.json` | sha256 файлов и даты модели и среза |

Происхождение: DPvisualisation 1.0.0, ветка `claude/dreamy-bardeen-xe4k6r`,
коммит `ce0ada3` (в `main` DPvisualisation ещё не влит). Срез — 09.10.2026
(15:20 UTC), по клонам 20 репозиториев экосистемы. Схема без анимаций,
связи — прямые, по ширине экрана без прокрутки вбок (DPvisualisation D-16).

Обновить (сессия или автор):
1. В клоне DPvisualisation: `python3 -m tools.dpvis collect --root <клоны>`.
2. Там же: `python3 -m tools.dpvis build --site dist/site`.
3. Здесь: `python3 -m tools.cs site ecosystem-import <клон DPvisualisation>/dist/site` —
   команда проверяет хэши, копирует четыре файла и проверяет их ещё раз.
4. `tools/check.sh`, затем выгрузка по правилам Д35 и Д36.
