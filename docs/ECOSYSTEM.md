# Карта экосистемы

Какие репозитории есть, за что каждый отвечает, чего не делает и как они
связаны. О себе прав сам репозиторий (M-02): у каждого строка ссылается
на его документы; при расхождении исправляется эта карта. Состояние — на
5 октября 2026, третий проход (коммиты — в срезе
`docs/reports/2026-10-05-3-xref.md`); DPmetaApp, DPvisualisation,
DP_AI_Orcestrator и их связи — четвёртый, частичный проход 9 октября 2026
(`docs/reports/2026-10-09-ECOSYSTEM_HEALTH.md`). 11 октября 2026 сверены
строки репозиториев, изменившихся после 10 октября, — Galdhold, DPlang,
DP-Verify, DP_game_market_analytic_system, DP_AI_Orcestrator, DPmetaApp —
и связи C-03, C-13 (`docs/reports/2026-10-11-ECOSYSTEM_HEALTH.md`).
Деление на ядро, управление, поддерживающий слой и архив — слово автора
9 октября 2026 (M-10); место каждого проекта — поле `place` реестра
`tools/ecosystem.toml`.

## 1. Ядро экосистемы

Девять репозиториев (`place = "core"`, M-10): восемь, с которых начался
общий способ ведения (`docs/PRINCIPLES.md`), и DP_AI_Orcestrator — по
слову автора «один из основных инструментов системы». У всех, кроме
DP_game_market_analytic_system, — журнал решений, `CLAUDE.md`, `TODO.md`,
своя команда проверок, сессии ИИ под правилами автора; у него нет
`CLAUDE.md`, а `TODO.md` лежит в `docs/` (RISK-05). Все закрытые
(private); DP_AI_Orcestrator по ответу автора остаётся закрытым и
ведётся по правилам публичного (`DP_AI_Orcestrator:D-16`,
`DP_AI_Orcestrator:TODO.md`). С 10 октября 2026 DP_AI_Orcestrator — в
составе DPmetaApp: код, документы и история — подсистема «AI» в
`DPmetaApp:dpai/`, сам репозиторий заморожен (`DPmetaApp:D-28`, слово
автора «всё в одном»). Место в реестре пока прежнее — `core`; каким оно
станет, решает автор (RISK-11).

| репозиторий | отвечает за | не делает | стадия | проверки | вход |
|---|---|---|---|---|---|
| **Galdhold** | воксельная action-RPG и её движок (`voxel_engine`) с полигоном в одном репозитории (`Galdhold:Р1`); Android и Windows (`Galdhold:Р2`) | не зависит от DPlang (`Galdhold:Р18`); движок не зависит от игры и полигона (`Galdhold:Р1`); GitHub Actions не использует (`Galdhold:Р12`) | этапы 1–4 закрыты, идёт этап 5 — версия 1 и выпуск (`Galdhold:README.md`) | `tools/hostcheck/run.sh` — вход через DP Verify (`Galdhold:Р21`); полный — 23–50 минут в контейнере сессии (`DP-Verify:docs/audits/STAGE7_PILOT_VOXELRPG.md`) | `Galdhold:CLAUDE.md`, `Galdhold:docs/direction/DECISIONS.md` |
| **DPlang** | системный язык общего назначения, компилятор в C11 и toolchain (`DPlang:Р27`, `DPlang:Р16`) | не DSL и не скриптовый слой (`DPlang:Р27`); решений за Galdhold не принимает (`DPlang:Р22`); онлайн программы пользователя не исполняет (`DPlang:Р49`) | v0.0 сделана; v0.1 закрыта с оговоркой 10 октября 2026 (`DPlang:docs/audits/2026-10-10-v0.1-audit.md`); идёт v0.2 — native-срез (`DPlang:ROADMAP/ROADMAP.md`; в `DPlang:README.md` — «не начата»); онлайн-компилятор для страницы на dpsmpx.com — запуск выключен (`DPlang:Р49`, C-09) | `tools/check/run.sh` | `DPlang:docs/README.md` |
| **DP-Verify** | общий каркас инженерных проверок и process-kit для репозиториев автора (`DP-Verify:README.md`) | предметное — командами проекта, в ядро — только нужное двум репозиториям (`DP-Verify:V-04`); код проверяемых репозиториев не правит (`DP-Verify:docs/audits/STAGE2_TRIAL.md`) | 1.0.0-rc2: этапы 0–7 сделаны — пилоты в DPpass (`DPpass:D-50`) и Galdhold (`Galdhold:Р21`); выпуск 1.0.0 — этап 8 (`DP-Verify:ROADMAP/ROADMAP.md`) | `./dpv run full --ci` | `DP-Verify:README.md`, `DP-Verify:docs/direction/TECHNICAL_TASK.md` |
| **DPpass** | клиент прокси/VPN для Android: своя модель конфигурации, маршрутизации и подписок; ядро Xray в процессе (`DPpass:D-02`, `DPpass:D-24`, `DPpass:D-25`) | без телеметрии (`DPpass:D-12`); не обещает «обход» (`DPpass:D-13`); код других клиентов не берёт (`DPpass:D-20`) | этап 1 из 5 выполнен, идёт этап 2 (`DPpass:README.md`, «Прогресс») | `tools/hostcheck/run.sh` (`--quick`, `--full`) | `DPpass:CLAUDE.md` |
| **DPprofile** | проверяемая история пути автора и его проектов: источники, факты, хронология, выводы, структура будущей книги (`DPprofile:D-01`) | книгу не пишет до шлюза `DPprofile:D-02`; решений о проектах не принимает; код и большие фрагменты не копирует (`DPprofile:D-05`) | этап 4 из 6 (`DPprofile:README.md`) | `tools/verify/run.sh --quick`; полный — с клонами источников | `DPprofile:CLAUDE.md` |
| **DPlearn** | персональная система обучения: статус «освоено» вычисляется из доказательств (`DPlearn:D-01`) | сессия разработки не трогает данные учащегося `learner/` (`DPlearn:CLAUDE.md`) | 0.1.0 (`DPlearn:README.md`) | `tools/check/run.sh full` | `DPlearn:README.md` |
| **SocialMediaImage** | производство, проверка и публикация контента автора; сайт `dpsmpx.com` и его страница «Прогресс экосистемы» (`SocialMediaImage:README.md`, `SocialMediaImage:Д38`) | решения закрытых проектов не переписывает; их код не переносит; не публикует без одобрения автора (`SocialMediaImage:CLAUDE.md`) | сайт выпущен — выгрузка разрешена 5 октября 2026, первая штатная прошла (`SocialMediaImage:Д34`, `SocialMediaImage:TODO.md`); фаза «Сайт MVP» продолжается; `CI-0001` и страница прогресса `CI-0002` ждут одобрения автора (`SocialMediaImage:ROADMAP/ROADMAP.md`) | `tools/check.sh` | `SocialMediaImage:README.md` |
| **DP_game_market_analytic_system** | воспроизводимый анализ рынка игр с явной неопределённостью: наблюдения → факты → метрики → гипотезы → решения (`DP_game_market_analytic_system:docs/ARCHITECTURE.md`) | не скрейпер, не агрегатор новостей, не обёртка над LLM (там же) | 0.1.0 (pre-release): фазы 0–8, CLI `dpm`, 294 теста (`DP_game_market_analytic_system:docs/HANDOFF.md`, PR #2 — 11 октября 2026) | `python3 -m pytest` | `DP_game_market_analytic_system:docs/ARCHITECTURE.md` |

| **DP_AI_Orcestrator** | управляющий слой для параллельных сессий Claude Code и Agent SDK: задачи и зависимости, аккаунты, execution targets, контекст и handoff, проверка результата (`DP_AI_Orcestrator:D-01`). **С 10 октября 2026 заморожен:** код, документы и история — подсистема «AI» DPmetaApp в `DPmetaApp:dpai/`, её Android-оболочка — первая форма приложения DPmetaApp (`DPmetaApp:D-28`); архивировать репозиторий — автор (SQ-30) | код закрытых репозиториев не переносит — только идеи (`DP_AI_Orcestrator:D-16`); кода DPmeta не содержит — читает его манифест (C-13); в самом репозитории работа не начинается (`DPmetaApp:D-28`) | в `DPmetaApp:dpai/README.md`: выполнено этапов 15 из 21, в работе — 5, 11, 12, 17, впереди — 19 и 20 (V1 не заморожена) | `DPmetaApp:dpai/tools/verify/run.sh` (`full`) | `DPmetaApp:dpai/CLAUDE.md` |

DPmeta — **слой синхронизации** (M-01, M-12, `place = "sync"`): карта,
контракты, имена, очередь согласований, манифест; не ядро и не
зависимость. `README.md`.

## 2. Управление, поддерживающий слой, профиль и архив

Места — слово автора 9 октября 2026 (M-10). Управление и поддерживающий
слой — часть текущей системы наравне с ядром; архив — вне текущей
системы: ранние проекты до репозитория игры (первый коммит Galdhold —
11 сентября 2026) и те, что автор убрал. В схеме DPvisualisation, на
странице прогресса сайта и в DPmetaApp архива нет (`DPvisualisation:D-20`
и `SocialMediaImage:Д38` — в невлитой ветке `claude/dreamy-bardeen-xe4k6r`
своих репозиториев, `DPmetaApp:D-26` — в `main` с 11 октября 2026; в
`main` SocialMediaImage архив пока в счёте как «ранние и малые
проекты»). Говорить о ранних проектах на сайте можно
открыто — их репозитории открыты, а о закрытых активных — осторожнее
(слово автора 10 октября 2026): станция «2023» раздела «Путь» и
упоминание TrueJointTileMaker в статье CI-0003 остаются, на странице
прогресса архив назван по именам, но не в счёте. В реестре DPmeta
архив остаётся ради контракта C-08 и сверки ссылок, в манифест (M-11) не
входит. Профиль `dpsmpx/dpsmpx` — не проект: места у него нет (M-10, C-07).

### Управление (`control`)

| репозиторий | что это | связи |
|---|---|---|
| `dpsmpx/DPmetaApp` (закрытый) | управление экосистемой — одно Android-приложение автора, единая среда систем активных репозиториев (`DPmetaApp:D-27`); мастер-ТЗ — `DPmetaApp:MESTER_TECHNICAL_TASK_ROADMAP.md`. ТЗ, решения и процесс — в `main` с 11 октября 2026 (PR #2, SQ-27). С 10 октября в нём — подсистема «AI» `dpai/` (прежде DP_AI_Orcestrator) с историей: её ядро — ядро приложения, её Android-оболочка — первая форма DPmetaApp (`DPmetaApp:D-28`). Фаза B — сейчас, по одной функции: идёт этап 2, команда `dpmeta-cli` (`DPmetaApp:D-29`) | данные — локально, GitHub — синхронизация, а не запрос на каждое действие (`DPmetaApp:D-27`); схема — из DPvisualisation (`DPmetaApp:D-26`, `DPvisualisation:D-18`); манифест DPmeta читает подсистема «AI» (C-13); реестр DPmeta — по плану, корпус с `place` (`DPmetaApp:D-05`) |

### Поддерживающий слой (`support`)

| репозиторий | что это | связи |
|---|---|---|
| `dpsmpx/DPvisualisation` (закрытый) | обеспечение системы: модель экосистемы — узлы, потоки и активность по истории git клонов (`DPvisualisation:D-01`); собирает данные в схему и ничем не управляет, к сайту не обращается (`DPvisualisation:D-19`); лицензия — «все права защищены» (`DPvisualisation:D-17`, в невлитой ветке; в `main` пока Apache-2.0 начального коммита); 1.0.0 — в невлитой ветке (SQ-25) | читает реестр и контракты DPmeta (C-11) и клоны; отдаёт комплект схемы SocialMediaImage — на сайте полустатичная схема, обновляется при каждой выгрузке (C-12, `DPvisualisation:D-18`); по плану — слой визуализации DPmetaApp |
| `dpsmpx/DPmonetization` (закрытый) | с 5 октября 2026: «Strategic Project Intelligence» — анализ портфеля проектов: доказательства, стадии и шлюзы готовности, ценность, монетизация, решения (`DPmonetization:tools/dpmon/__init__.py`). В `main` — прерванный каркас ядра без тестов; README из одного заголовка, нет `CLAUDE.md` и журнала решений (SQ-22) | связей пока нет. **План** (описание PR #1 DPmonetization): реестр проектов по фактам DPmeta, зависимости по C-01, C-03, C-08, C-09, адаптеры к `report.json` DP-Verify и к `DecisionReport` DP_game_market_analytic_system, экспорт для сайта только одобренного автором |

### Профиль и архив

| репозиторий | место | что это | связи |
|---|---|---|---|
| `dpsmpx/dpsmpx` (публичный) | — (не проект, C-07) | README страницы профиля GitHub | читают DPprofile (`DPprofile:docs/sources/REGISTRY.md`, S-04) и SocialMediaImage (`SocialMediaImage:IC-04`…`SocialMediaImage:IC-06`); правит только автор (C-07) |
| `dpsmpx/Andors-Love` (публичный) | архив | пошаговая RPG на C++ (терминал и SDL), с 2023 года | владелец формата карт `data/maps/*.map` (C-08) |
| `dpsmpx/TrueJointTileMaker` (публичный) | архив | редактор пиксельной графики и карт на PascalABC.NET; с 5 октября 2026 переносится на C++ и SDL2 (`TrueJointTileMaker:docs/porting.md`) — по слову автора всё же вне текущей системы | читает и пишет карты Andors-Love, берёт имена его тайлов; тесты переноса — на клоне игры (C-08) |
| DP_Ecosystem, DP_Repeated_Tasks, PixRogue, tanks_blitz_stat_chat и другие публичные | архив | ранние и малые проекты; DP_Ecosystem и DP_Repeated_Tasks начаты после Galdhold, но автор их убрал | источники DPprofile (`DPprofile:D-13`) |
| форки (`andors-trail-load`, `gravitydefied`, `CreepyCodeCollection`, `pascalabcnet`) | — | чужие проекты | вне предмета, кроме коммитов автора (`DPprofile:D-13`) |
| репозитории 2023–2024 (`day_plan`, `rnd_day` и другие) | архив | старые проекты | не упоминаются (`SocialMediaImage:docs/identity/PROJECT_MAP.md`) |

## 3. Связи

```
Решения, повторённые с обеих сторон (C-01; расхождение — C-02)
  Galdhold ◄────────────────► DPlang

Поставка проверок копией в tools/dpv/ (C-03; 1.0.0-rc2)
  DP-Verify ──► DPpass          этап 6, пилот — поставлено 6 октября 2026 (DPpass:D-50)
            ──► Galdhold        этап 7 — влито 10 октября 2026 (Galdhold:Р21)
            ┄┄► DPlang          версия 1.1
            ┄┄► DPlearn         пока — образец процесса; поставка — после DP-Verify 1.0

Сервис по сети (C-09; план DPlang, запуск выключен; вторая сторона не знает — RISK-09)
  DPlang ── онлайн-компилятор, HTTP ──► страница SocialMediaImage на dpsmpx.com

Факты о проектах со ссылкой на коммит, без кода (C-04, C-05)
  Galdhold, DPlang, DPpass, профиль, публичные ──► DPprofile          история
  Galdhold, DPlang, профиль ─────────────────────► SocialMediaImage   публичное ──► dpsmpx.com, площадки
                                                   (правки профиля и площадок — только автор, C-07)

Формат данных (C-08)
  Andors-Love ── data/maps/*.map, имена тайлов ──► TrueJointTileMaker

Числа истории и документов (C-10; при сборе набора, сайт — из сохранённого)
  ядро, DPmeta, публичные ──► SocialMediaImage  страница «Прогресс экосистемы» ──► dpsmpx.com
                              (DPpass — без имени, только в суммах)

Модель экосистемы (C-11, C-12; чтение и копия, без зависимости сборки)
  DPmeta ── реестр, заголовки C-NN ──► DPvisualisation ── комплект схемы ──► SocialMediaImage ──► dpsmpx.com /ecosystem/
  клоны всех репозиториев ── история git ──►┘

Манифест экосистемы (C-13, M-11; копия на устройстве автора, без зависимости)
  DPmeta ── tools/manifest.py: проекты, места, открытые SQ ──► DPmetaApp: подсистема «AI» dpai/ (прежде DP_AI_Orcestrator)

Без связей, кроме счёта прогресса (C-10): DP_game_market_analytic_system
Пока без связей (план — §2): DPmonetization
```

Наблюдения:

- **Зависимостей сборки и исполнения между репозиториями нет.**
  Поставка DP-Verify копией в `tools/dpv/` (C-03) — в DPpass с 6 октября
  2026, в Galdhold с 10 октября: копия, а не ссылка на другой репозиторий,
  и только по решению принимающего репозитория (`DPpass:D-50`,
  `Galdhold:Р21`).
  Запланирована одна. Онлайн-компилятор DPlang для страницы
  сайта (C-09): страница SocialMediaImage будет обращаться к сервису
  DPlang по сети; запуск выключен, а записана связь только в DPlang
  (RISK-09).
- **Зависимостей проверки две, обе необязательные:** полная проверка
  DPprofile сверяет ссылки с клонами источников, быстрая обходится без
  них (C-04); тесты переноса TrueJointTileMaker читают карты из клона
  Andors-Love и без него пропускаются (C-08).
- **Зависимостей сбора две:** новый набор страницы прогресса
  SocialMediaImage собирается только при клонах всех репозиториев из его
  списка, включая DPmeta; сайт строится из сохранённого набора и без них
  (C-10). Срез DPvisualisation тоже собирается по клонам, но без любого
  клона не падает — пишет диагностику; комплект схемы на сайте — копия,
  сайт строится и без DPvisualisation (C-11, C-12). Манифест для
  подсистемы «AI» DPmetaApp (C-13) строится по клонам так же, а без клона
  проект попадает в него без состояния ветки; подсистема работает и без
  манифеста.
- **Остальные связи — знание:** решения, которые повторяют друг друга
  (C-01), факты о проектах (C-04), правила обращения с закрытым кодом
  (C-05), общий способ ведения проектов (`docs/PRINCIPLES.md`).
- **Связность по ссылкам** (сколько файлов называют другой проект,
  `tools/xref.py`): больше всего о других пишут DPprofile (о Galdhold —
  59 файлов) и SocialMediaImage (73), что совпадает с их назначением; о
  DPlang пишет Galdhold (6), о Galdhold — DPlang (38) и DP-Verify (19).
  К повторному проходу DPlang впервые назвал SocialMediaImage (2 файла —
  `DPlang:Р49` и ROADMAP). К третьему SocialMediaImage называет все
  репозитории ядра и впервые — DPmeta (10 файлов): страница прогресса
  (C-10). DP_game_market_analytic_system не называет ни одного проекта;
  называет его только SocialMediaImage (4 файла). DPmonetization пока не
  называет никого, и его — никто, кроме DPmeta.

## 4. Происхождение общего процесса

Наблюдение: правила ведения репозитория сложились в Galdhold и
переносились копированием с указанием источника —

- DPlang: «инженерная культура VoxelRPG» (`DPlang:Р3`,
  `DPlang:docs/policy/ENGINEERING.md`);
- DP-Verify: обобщает правила DPpass, Galdhold и DPlang в process-kit
  (`DP-Verify:docs/direction/TECHNICAL_TASK.md`, §10);
- DPlearn: таблица «что взято из Galdhold и DP-Verify»
  (`DPlearn:docs/ARCHITECTURE.md`, `DPlearn:docs/PROCESS.md`);
- SocialMediaImage, DPprofile — те же правила своими словами.

Копирование без общего источника — причина мелких расхождений
(`docs/PRINCIPLES.md`, «Различия»). Общий источник, который уже
строится, — process-kit DP-Verify (этап 5); DPmeta его не дублирует.
