# DPmeta

Мета-репозиторий экосистемы проектов dpsmpx: знание, которое касается
**нескольких** репозиториев сразу и поэтому не принадлежит ни одному из
них — карта репозиториев и их границ, контракты между ними, общие имена,
общие инженерные принципы, рисковые решения на суд автора и очередь
согласований.

DPmeta — **не зависимость**: ни один репозиторий не собирается, не
проверяется и не запускается с его помощью, и ни один не обязан его
читать, чтобы оставаться понятным (M-01). О себе каждый репозиторий —
сам себе источник истины; DPmeta описывает только то, что между ними
(M-02).

## Что где

| вопрос | документ |
|---|---|
| какие есть репозитории, за что каждый отвечает и чего не делает | [`docs/ECOSYSTEM.md`](docs/ECOSYSTEM.md) |
| как называются проекты, их прежние имена, номера решений | [`docs/NAMING.md`](docs/NAMING.md) |
| что репозитории ждут друг от друга (форматы, границы, поставка) | [`docs/CONTRACTS.md`](docs/CONTRACTS.md) |
| общие инженерные принципы — что уже одинаково и где различие оправдано | [`docs/PRINCIPLES.md`](docs/PRINCIPLES.md) |
| решения, которые нельзя принять автоматически, — на суд автора | [`docs/RISKS.md`](docs/RISKS.md) |
| найденные расхождения по репозиториям и что с ними сделано | [`docs/SYNC_QUEUE.md`](docs/SYNC_QUEUE.md) |
| решения о самом DPmeta | [`docs/DECISIONS.md`](docs/DECISIONS.md) |
| как идёт мета-проход и как он не мешает разработке | [`docs/PROCESS.md`](docs/PROCESS.md) |
| аудит согласованности документации проектов — задача для периодического вызова | [`docs/doc-audit/TASK.md`](docs/doc-audit/TASK.md) |
| срезы состояния экосистемы на дату | [`docs/reports/`](docs/reports/) |
| правила для сессий в этом репозитории | [`CLAUDE.md`](CLAUDE.md) |

## Проверки

```sh
tools/check.sh                       # самопроверка DPmeta: ссылки, реестр, тесты
python3 tools/activity.py --root ..  # кто работает: свежие коммиты, невлитые ветки
python3 tools/xref.py --root ..      # сверка ссылок между клонами экосистемы
python3 tools/manifest.py --root ..  # манифест dpai.ecosystem для DP_AI_Orcestrator (M-11, C-13)
```

Инструменты читают клоны репозиториев, лежащие рядом с DPmeta
(каталоги — `tools/ecosystem.toml`, или по имени репозитория), и ничего в
них не пишут. Нужен только Python ≥ 3.11.

## Состояние

Первый проход консолидации — 5 октября 2026
([`docs/reports/2026-10-05-ECOSYSTEM_HEALTH.md`](docs/reports/2026-10-05-ECOSYSTEM_HEALTH.md));
повторный — в тот же день, по работе, влитой за час между проходами
([`docs/reports/2026-10-05-2-ECOSYSTEM_HEALTH.md`](docs/reports/2026-10-05-2-ECOSYSTEM_HEALTH.md));
третий — частичный, по изменившимся SocialMediaImage, DPpass и новому
DPmonetization
([`docs/reports/2026-10-05-3-ECOSYSTEM_HEALTH.md`](docs/reports/2026-10-05-3-ECOSYSTEM_HEALTH.md));
четвёртый — частичный, 9 октября 2026: DPmetaApp, DPvisualisation,
DP_AI_Orcestrator и связи схемы сайта
([`docs/reports/2026-10-09-ECOSYSTEM_HEALTH.md`](docs/reports/2026-10-09-ECOSYSTEM_HEALTH.md));
в тот же день — места проектов по слову автора (M-10) и манифест
`dpai.ecosystem` (M-11)
([`docs/reports/2026-10-09-2-ECOSYSTEM_HEALTH.md`](docs/reports/2026-10-09-2-ECOSYSTEM_HEALTH.md));
10 октября — проверка всего прохода отдельным проходом и исправления
([`docs/reports/2026-10-10-ECOSYSTEM_HEALTH.md`](docs/reports/2026-10-10-ECOSYSTEM_HEALTH.md))
и второй круг проверки
([`docs/reports/2026-10-10-2-ECOSYSTEM_HEALTH.md`](docs/reports/2026-10-10-2-ECOSYSTEM_HEALTH.md));
11 октября — сводка третьего круга, сверка после того, как
DP_AI_Orcestrator вошёл в DPmetaApp, и после пилотов DP-Verify
([`docs/reports/2026-10-11-ECOSYSTEM_HEALTH.md`](docs/reports/2026-10-11-ECOSYSTEM_HEALTH.md));
12 октября — проверка отдельным проходом всех веток прохода и исправления
([`docs/reports/2026-10-12-ECOSYSTEM_HEALTH.md`](docs/reports/2026-10-12-ECOSYSTEM_HEALTH.md)),
второй круг проверки
([`docs/reports/2026-10-12-2-ECOSYSTEM_HEALTH.md`](docs/reports/2026-10-12-2-ECOSYSTEM_HEALTH.md))
и третий
([`docs/reports/2026-10-12-3-ECOSYSTEM_HEALTH.md`](docs/reports/2026-10-12-3-ECOSYSTEM_HEALTH.md)).
Аудит согласованности документации (M-09) ещё не запускался.
