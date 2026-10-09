# Карта экосистемы

Какие репозитории есть, за что каждый отвечает, чего не делает и как они
связаны. О себе прав сам репозиторий (M-02): у каждого строка ссылается
на его документы; при расхождении исправляется эта карта. Состояние — на
5 октября 2026, третий проход (коммиты — в срезе
`docs/reports/2026-10-05-3-xref.md`); DPmetaApp, DPvisualisation,
DP_AI_Orcestrator и их связи — четвёртый, частичный проход 9 октября 2026
(`docs/reports/2026-10-09-ECOSYSTEM_HEALTH.md`).

## 1. Ядро экосистемы

Восемь репозиториев, которые ведутся одним способом (`docs/PRINCIPLES.md`):
журнал решений, `CLAUDE.md`, `TODO.md`, своя команда проверок, сессии ИИ
под правилами автора. Все закрытые (private).

| репозиторий | отвечает за | не делает | стадия | проверки | вход |
|---|---|---|---|---|---|
| **Galdhold** | воксельная action-RPG и её движок (`voxel_engine`) с полигоном в одном репозитории (`Galdhold:Р1`); Android и Windows (`Galdhold:Р2`) | не зависит от DPlang (`Galdhold:Р18`); движок не зависит от игры и полигона (`Galdhold:Р1`); GitHub Actions не использует (`Galdhold:Р12`) | этапы 1–4 закрыты, идёт этап 5 — версия 1 и выпуск (`Galdhold:README.md`) | `tools/hostcheck/run.sh` (полный — около 12 минут) | `Galdhold:CLAUDE.md`, `Galdhold:docs/direction/DECISIONS.md` |
| **DPlang** | системный язык общего назначения, компилятор в C11 и toolchain (`DPlang:Р27`, `DPlang:Р16`) | не DSL и не скриптовый слой (`DPlang:Р27`); решений за Galdhold не принимает (`DPlang:Р22`); онлайн программы пользователя не исполняет (`DPlang:Р49`) | v0.0 сделана; v0.1 — шаги 1–8 из 13, C-бэкенд готов (`DPlang:README.md`, `DPlang:ROADMAP/IMPLEMENTATION.md`); строится онлайн-компилятор для страницы на dpsmpx.com, запуск выключен (`DPlang:Р49`, C-09) | `tools/check/run.sh` | `DPlang:docs/README.md` |
| **DP-Verify** | общий каркас инженерных проверок и process-kit для репозиториев автора (`DP-Verify:README.md`) | предметное — командами проекта, в ядро — только нужное двум репозиториям (`DP-Verify:V-04`); код проверяемых репозиториев не правит (`DP-Verify:docs/audits/STAGE2_TRIAL.md`) | 0.3.0: этапы 0–3 сделаны, выпуск 1.0 — этап 8; потребителей ещё нет (`DP-Verify:ROADMAP/ROADMAP.md`) | `./dpv run full` | `DP-Verify:README.md`, `DP-Verify:docs/direction/TECHNICAL_TASK.md` |
| **DPpass** | клиент прокси/VPN для Android: своя модель конфигурации, маршрутизации и подписок; ядро Xray в процессе (`DPpass:D-02`, `DPpass:D-24`, `DPpass:D-25`) | без телеметрии (`DPpass:D-12`); не обещает «обход» (`DPpass:D-13`); код других клиентов не берёт (`DPpass:D-20`) | этап 1 из 5 выполнен, идёт этап 2 (`DPpass:README.md`, «Прогресс») | `tools/hostcheck/run.sh` (`--quick`, `--full`) | `DPpass:CLAUDE.md` |
| **DPprofile** | проверяемая история пути автора и его проектов: источники, факты, хронология, выводы, структура будущей книги (`DPprofile:D-01`) | книгу не пишет до шлюза `DPprofile:D-02`; решений о проектах не принимает; код и большие фрагменты не копирует (`DPprofile:D-05`) | этап 4 из 6 (`DPprofile:README.md`) | `tools/verify/run.sh --quick`; полный — с клонами источников | `DPprofile:CLAUDE.md` |
| **DPlearn** | персональная система обучения: статус «освоено» вычисляется из доказательств (`DPlearn:D-01`) | сессия разработки не трогает данные учащегося `learner/` (`DPlearn:CLAUDE.md`) | 0.1.0 (`DPlearn:README.md`) | `tools/check/run.sh full` | `DPlearn:README.md` |
| **SocialMediaImage** | производство, проверка и публикация контента автора; сайт `dpsmpx.com` и его страница «Прогресс экосистемы» (`SocialMediaImage:README.md`, `SocialMediaImage:Д38`) | решения закрытых проектов не переписывает; их код не переносит; не публикует без одобрения автора (`SocialMediaImage:CLAUDE.md`) | сайт выпущен — выгрузка разрешена 5 октября 2026, первая штатная прошла (`SocialMediaImage:Д34`, `SocialMediaImage:TODO.md`); фаза «Сайт MVP» продолжается; `CI-0001` и страница прогресса `CI-0002` ждут одобрения автора (`SocialMediaImage:ROADMAP/ROADMAP.md`) | `tools/check.sh` | `SocialMediaImage:README.md` |
| **DP_game_market_analytic_system** | воспроизводимый анализ рынка игр с явной неопределённостью: наблюдения → факты → метрики → гипотезы → решения (`DP_game_market_analytic_system:docs/ARCHITECTURE.md`) | не скрейпер, не агрегатор новостей, не обёртка над LLM (там же) | 0.1.0; фазы 0–4 с тестами, 5–6 без тестов, CLI нет (`DP_game_market_analytic_system:docs/HANDOFF.md`) | `python3 -m pytest` | `DP_game_market_analytic_system:docs/ARCHITECTURE.md` |

DPmeta — девятый, мета-уровень (M-01): `README.md`.

## 2. Периферия

Самостоятельные проекты вне общего процесса. Связаны с ядром только как
источники фактов (DPprofile, SocialMediaImage) и чисел страницы прогресса
(C-10) — кроме одной пары. Первые четыре строки — новые репозитории (5–6
октября 2026), чьё место ещё не определено: три последних из них уже
ведутся общим процессом (журнал решений, `CLAUDE.md`, `TODO.md`, своя
команда проверок).

| репозиторий | что это | связи |
|---|---|---|
| `dpsmpx/DPmonetization` (закрытый) | с 5 октября 2026: «Strategic Project Intelligence» — анализ портфеля проектов: доказательства, стадии и шлюзы готовности, ценность, монетизация, решения (`DPmonetization:tools/dpmon/__init__.py`). В `main` — прерванный каркас ядра без тестов; README из одного заголовка, нет `CLAUDE.md` и журнала решений (SQ-22) | связей пока нет. **План** (описание PR #1 DPmonetization): реестр проектов по фактам DPmeta, зависимости по C-01, C-03, C-08, C-09, адаптеры к `report.json` DP-Verify и к `DecisionReport` DP_game_market_analytic_system, экспорт для сайта только одобренного автором. Ядро это или периферия — вопрос автору |
| `dpsmpx/DPmetaApp` (закрытый) | мета-приложение автора для Android над законченными системами экосистемы; в `main` — мастер-ТЗ автора: сначала довести проекты до законченных систем (волна 1 — DPmeta), затем строить приложение (`DPmetaApp:MESTER_TECHNICAL_TASK_ROADMAP.md`); ТЗ и решения самого приложения — в невлитой ветке (SQ-27) | по плану — читатель всего: модели DPvisualisation, ядра DP_AI_Orcestrator, данных DPmeta; сейчас связей нет |
| `dpsmpx/DPvisualisation` (закрытый) | интерактивная модель экосистемы: узлы, потоки и активность по истории git клонов; публичная выжимка — раздел «Экосистема» сайта (`DPvisualisation:D-01`, `DPvisualisation:D-15`); 1.0.0 — в невлитой ветке (SQ-25) | читает реестр и контракты DPmeta (C-11) и клоны всех репозиториев; отдаёт комплект схемы SocialMediaImage (C-12) |
| `dpsmpx/DP_AI_Orcestrator` (закрытый) | управляющий слой для параллельных сессий Claude Code: задачи, контекст и handoff, проверка результата; управление — из Android-приложения (`DP_AI_Orcestrator:D-01`); V1 в работе | ждёт от DPmeta манифест `dpai.ecosystem` (RISK-10); по плану — подсистема DPmetaApp (`DP_AI_Orcestrator:docs/INTEGRATION_CONTRACT.md`) |
| `dpsmpx/dpsmpx` (публичный) | README страницы профиля GitHub | читают DPprofile (`DPprofile:docs/sources/REGISTRY.md`, S-04) и SocialMediaImage (`SocialMediaImage:IC-04`…`SocialMediaImage:IC-06`); правит только автор (C-07) |
| `dpsmpx/Andors-Love` (публичный) | пошаговая RPG на C++ (терминал и SDL) | владелец формата карт `data/maps/*.map` (C-08) |
| `dpsmpx/TrueJointTileMaker` (публичный) | редактор пиксельной графики и карт на PascalABC.NET; с 5 октября 2026 переносится на C++ и SDL2, оригинал — эталон в `pascal/` (`TrueJointTileMaker:docs/porting.md`) | читает и пишет карты Andors-Love, берёт имена его тайлов; тесты переноса — на клоне игры (C-08) |
| DP_Ecosystem, DP_Repeated_Tasks, PixRogue и другие публичные | ранние и малые проекты | источники DPprofile (`DPprofile:D-13`) |
| форки (`andors-trail-load`, `gravitydefied`, `CreepyCodeCollection`, `pascalabcnet`) | чужие проекты | вне предмета, кроме коммитов автора (`DPprofile:D-13`) |
| репозитории 2023–2024 (`day_plan`, `rnd_day` и другие) | архив | не упоминаются (`SocialMediaImage:docs/identity/PROJECT_MAP.md`) |

## 3. Связи

```
Решения, повторённые с обеих сторон (C-01; расхождение — C-02)
  Galdhold ◄────────────────► DPlang

Поставка проверок копией в tools/dpv/ (C-03; план, поставок ещё нет)
  DP-Verify ──► DPpass          этап 6, пилот
            ──► Galdhold        этап 7
            ──► DPlang          версия 1.1
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

План, записан одной стороной (RISK-10)
  DPmeta ┄┄ манифест dpai.ecosystem ┄┄► DP_AI_Orcestrator ┄┄► DPmetaApp (подсистема после V1)

Без связей, кроме счёта прогресса (C-10): DP_game_market_analytic_system
Пока без связей (план — §2): DPmonetization, DPmetaApp
```

Наблюдения:

- **Зависимостей сборки и исполнения между репозиториями нет.**
  Запланированных две. Поставка DP-Verify копией в `tools/dpv/` (C-03):
  копия, а не ссылка на другой репозиторий, и только по решению
  принимающего репозитория. Онлайн-компилятор DPlang для страницы
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
  сайт строится и без DPvisualisation (C-11, C-12).
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
