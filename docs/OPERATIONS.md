# Как пользоваться

Нужны Python ≥ 3.11 и git; для тестов просмотрщика — Node.js. Все
команды — из корня репозитория. Устройство — `docs/ARCHITECTURE.md`.

## 1. Посмотреть схему

Без сети и без клонов — по модели и срезу, которые лежат в репозитории:

```sh
python3 -m tools.dpvis build                 # → dist/dpvisualisation.html, открыть в браузере
python3 -m tools.dpvis build --public        # то же, только публичная выжимка (D-15)
```

Файл открывается из `file://`, в браузере телефона и в WebView.
`dist/` не коммитится (D-08). Режим «показ» (`?show=1` или кнопка «Показ»)
скрывает имя проекта без публичного имени и имена веток — для показа
экрана другому.

## 2. Собрать свежий срез

Клоны всех репозиториев модели — в одном каталоге (`Galdhold`,
`galdhold` или `dpsmpx/Galdhold` — подходит любое). Нужна полная история:
неполный клон даёт `CLONE_SHALLOW`. Клон без содержимого файлов
(`git clone --filter=blob:none`) подходит и занимает мало места.

```sh
python3 -m tools.dpvis collect --root ~/clones            # → data/state/latest.json
python3 -m tools.dpvis collect --root ~/clones --fetch    # сначала git fetch каждого клона
python3 -m tools.dpvis collect --root ~/clones --online   # ещё и пробы сайта (только GET)
python3 -m tools.dpvis collect --root ~/clones --now 2026-10-09T12:00:00Z --out -   # для проверки
```

Итог печатается: кто работает и какие коды диагностики. Расхождения с
DPmeta (`REGISTRY_DRIFT`, `CONTRACT_UNCOVERED`, `CONTRACT_NOT_FOUND`) —
повод поправить модель (`model/ecosystem.toml`, затем `model build`), а не
срез. Новый срез — коммит «Срез …» (D-13).

## 3. Живой режим

```sh
python3 -m tools.dpvis serve --root ~/clones                 # срез раз в 15 мин, http://127.0.0.1:8765/
python3 -m tools.dpvis serve --root ~/clones --every 5 --fetch
python3 -m tools.dpvis serve --no-collect                    # только отдавать файлы репозитория
```

Сервер слушает только `127.0.0.1` (другой адрес — `--host`, с
предупреждением в журнале) и отдаёт только `viewer/` и `data/`. Просмотрщик
опрашивает срез раз в 60 с (`?poll=<с>`, `0` — выключить) и показывает
«⟳ обновлено …», не сбрасывая масштаб, выбор и фильтры. Неудачный сбор
пишется в журнал, прежний срез остаётся.

На телефоне — так же в Termux (`pkg install python git`); клоны закрытых
репозиториев — учётными данными git автора, в репозитории их нет.

## 4. Обновить схему на сайте

Сайт собирает и выгружает только SocialMediaImage (D-14, `SocialMediaImage:Д43`):

1. Здесь: свежий срез (раздел 2) и проверки `tools/check/run.sh full`.
2. Здесь: `python3 -m tools.dpvis build --site dist/site` — комплект
   `dpvis.js`, `dpvis.css`, `dpvis-data.json`, `MANIFEST.json`; закрытое в
   выжимке — отказ сборки.
3. В SocialMediaImage: `python3 -m tools.cs site ecosystem-import <этот репозиторий>/dist/site`,
   затем `SocialMediaImage:tools/check.sh` и выгрузка по его правилам (`SocialMediaImage:Д35`,
   `SocialMediaImage:Д36`) — после «да» автора.

## 5. Проверки

```sh
tools/check/run.sh                  # static — после любой правки
tools/check/run.sh full             # перед вливанием; итог — в описание PR
tools/check/run.sh --write-status   # блок состояния README.md из ROADMAP
```

Код 3 — проверка не запускалась (нет Node.js для тестов просмотрщика):
это сбой окружения, его называют в PR. Нет Chromium — дым сборки
пропускается с пометкой «пропущено» (путь к браузеру — `DPVIS_CHROMIUM`).
