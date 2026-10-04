# Архитектура

Как устроены репозиторий и инструменты **сейчас**. Почему правила
такие — `docs/PROCESS.md`; что решено — `docs/direction/DECISIONS.md`.

## 1. Поток данных

```
docs/identity, docs/context, data/facts ──┐   проверенный контекст
docs/direction, docs/platforms, brand ────┤
                                          ▼
content/items/<id>/item.yaml ── spec/*.yaml ──▶ tools/cs/render.py ──▶ HTML ──▶ tools/render/render.js (Chromium)
   цель, утверждения,             (composition/v1)                                   │
   варианты, гейты                                                                   ▼
                                       renders/ и exports/: PNG/JPEG + *.layout.json (раскладка)
                                                                                     │
                                       provenance.yaml (хэши, входы, спецификация, промпт)
                                                                                     ▼
                                       tools/check.sh → python3 -m tools.cs check → гейты
```

Знание живёт в файлах; инструменты только читают его и проверяют
согласованность. Состояние объекта меняет сессия правкой `item.yaml`
(с записью в `history`), утверждение — только автор (Д12).

## 2. Данные и схемы

| Файл | Схема | Пишет | Читает |
|---|---|---|---|
| `data/facts/facts.yaml` | `facts` | сессия по первоисточнику | `claims`, `facts`, документы по id |
| `data/facts/forbidden.yaml` | `forbidden` | сессия | `claims` |
| `data/identity/links.yaml` | `links` | сессия (проверка без входа) | документы идентичности |
| `data/platforms/export_specs.yaml` | `export_specs` | сессия по официальным источникам (Д13) | `render`, `exports`, `visual` |
| `data/provenance/assets.yaml` | `assets` | сессия при добавлении материала | `provenance`, `render` |
| `assets/brand/tokens.json` | `brand_tokens` | изменение основ | `render`, `visual` |
| `content/items/<id>/item.yaml` | `content_item` | сессия; `render`/`export` дописывают рендеры и экспорт | `validate`, все проверки объекта |
| `content/items/<id>/spec/*.yaml` | `composition` | сессия по промпту | `render`, `claims` |
| `content/items/<id>/provenance.yaml` | `item_provenance` | только `render.py` | `provenance`, `visual` |
| `data/experiments/EXP-NNNN.yaml` | `experiment` | сессия до запуска | `metrics` |
| `data/metrics/<id>/<платформа>-<дата>.yaml` | `metrics` | `metrics import` | `metrics` |

Каталог объекта — `docs/content/CONTENT_MODEL.md`, §1.

## 3. Инструменты (`tools/cs/`)

| Модуль | Что делает |
|---|---|
| `common.py` | пути, YAML/JSON, хэши, размеры PNG/JPEG и метаданные по заголовкам; `Report`, `Finding`; загрузка объектов; файлы репозитория (`git ls-files`) |
| `schema.py` | проверка по JSON Schema (Draft 2020-12) с приведением дат YAML |
| `model.py` | состояния, переходы, гейты, оси оценок; `check_path`, `reached`, `effective_state` |
| `validate.py` | `schemas` и `items`: требования состояний, история, выбор, утверждение, блокеры, согласованность записанных гейтов с автоматическими находками |
| `facts.py` | целостность реестра, просроченные `recheck_by`, id фактов и правил в документах |
| `claims.py` | гейт Factual: ссылки объекта на реестр, запреты в публикуемом тексте, числа только из заявленных фактов |
| `provenance.py` | реестр ассетов (хэши, покрытие `assets/`, метаданные фото) и выходы объектов |
| `visual.py` | гейты Visual/Platform/Legal по раскладке: размер текста, контраст по фону, группы, наложения, переполнение, «человек крупнее текста», безопасная зона, шрифты, внешние запросы |
| `exports.py` | гейты Platform/Safety по файлам экспорта: тип, размеры, вес, хэш, метаданные |
| `links.py` | пути в документах существуют |
| `secrets.py` | секреты и личные данные (`docs/SECURITY.md`) |
| `metrics.py` | импорт CSV (Д15) и проверка метрик и экспериментов |
| `newitem.py` | новый объект в `IDEA` |
| `render.py` | макеты (professional, technical, cinematic, minimal, шапка) → HTML; запуск рендера; запись происхождения |
| `__main__.py` | командная строка |

`tools/render/render.js` — Chromium через Playwright: открывает HTML с
диска, запрещает сеть, снимает кадр и раскладку: рамки элементов и
самого текста, размер и насыщенность шрифта, токен цвета, переполнение
(по осям с заданным размером) и яркость фона под текстом — по второму
кадру со скрытым текстом (95-й перцентиль яркости рамки).

## 4. Команды

```sh
tools/check.sh                    # все проверки + тесты (с рендером)
tools/check.sh --fast             # без тестов рендера
tools/check.sh claims visual      # только названные проверки
python3 -m tools.cs status        # объекты: состояние, вариант, гейты, блокеры
python3 -m tools.cs render CI-0001 [--variant A] [--lang ru en] [--format telegram_post]
python3 -m tools.cs export CI-0001 --variant A [--format …] [--final]
python3 -m tools.cs new --type post --pillar RESULT --slug … --objective "…"
python3 -m tools.cs metrics import --item CI-0001 --platform youtube --file … --from … --to …
```

`render` пишет `renders/<вариант>-<язык>-<формат>.<тип>`, `export` —
`exports/…`; оба обновляют `provenance.yaml` и списки в `item.yaml`.
Экспорт — `preview` до утверждения; `--final` — только после
`APPROVED`. Языки экспорта по площадкам — `platform_languages` в
`export_specs.yaml` (Д8).

## 5. Проверки и гейты

| Проверка | Гейт |
|---|---|
| `schemas`, `items` | все: структура и состояния |
| `facts`, `claims` | Factual |
| `visual` | Visual; Platform (безопасная зона, размеры); Legal (шрифты, сеть) |
| `exports` | Platform (тип, размеры, вес); Safety (метаданные) |
| `provenance` | Provenance; Safety (метаданные фото) |
| `links`, `secrets`, `metrics` | целостность репозитория |

Brand и права фотографа — за автором; инструмент проверяет только то,
что выражается в данных (токены, шрифты, портрет из `REFERENCE_IMAGE`).
Гейт, записанный `pass` при ошибке соответствующей проверки, — ошибка
`items`. Находки по отклонённым вариантам — предупреждения: их рендеры
хранятся как история выбора.

## 6. Рендер

- Детерминированный (Д11): одна спецификация → тот же файл; тест
  `tests/test_render.py` сравнивает хэши двух рендеров.
- Каждый формат рендерится заново под свой размер; шапка YouTube —
  отдельный макет со смыслом в безопасной зоне.
- Портрет — только из записи `REFERENCE_IMAGE`; геометрия — масштаб и
  сдвиг, тон — CSS-фильтры, интерьер — виньетка и маски.
- HTML лежит в `build/` (не в Git), раскладка — рядом с файлом без путей
  машины.

## 7. Окружение

- Python 3.10+ с PyYAML и jsonschema (`tools/requirements.txt`).
- Рендер: Node.js и Playwright с Chromium. Playwright ищется как
  модуль `playwright`, в `NODE_PATH` или в `/opt/node22/lib/node_modules`;
  другой Chromium — `CS_CHROMIUM=<путь>`.
- `CS_TODAY=YYYY-MM-DD` — дата для проверок сроков в тестах;
  `CS_SKIP_RENDER=1` — пропуск тестов рендера (`--fast`).
- Хук начала сессии `.claude/hooks/session-start.sh` (`.claude/settings.json`):
  ставит зависимости в облачной сессии, печатает указатель на
  `HANDOFF.md` и ветки с невлитой работой.

## 8. Как расширять

- **Проверка:** функция `check() -> Report` в модуле и строка в `CHECKS`
  (`__main__.py`); тест в `tests/`.
- **Формат площадки:** запись в `export_specs.yaml` с источником и датой
  (Д13); если у формата `safe_area` — макет должен уметь его
  (`layout_banner` — пример).
- **Макет:** функция `layout_<имя>` в `render.py`, имя — в `LAYOUTS` и в
  `composition.schema.json`; элементы с `data-role`, `data-group`,
  `data-fg` для проверок.
- **Схема:** `schemas/<имя>.schema.json` и строка в `SCHEMA_MAP`
  (`validate.py`).

## 9. Ограничения проверок

- Контраст считается по 95-му перцентилю яркости фона в рамке текста —
  тонкие светлые детали фона могут остаться незамеченными; критика
  смотрит глазами.
- Проверка чисел различает числа и даты по шаблонам; числа меньше 10 не
  проверяются.
- Проверка ссылок ищет пути в обратных кавычках и ссылках Markdown;
  пути закрытых проектов («Galdhold `docs/…`») пропускает.
- `secrets` знает известные формы ключей — не заменяет осторожности.
