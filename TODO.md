# TODO

Только незаконченное (CLAUDE.md, «TODO.md»). Метка — `М`, `С`, `Т`, `К`
(`ROADMAP/ROADMAP.md`, «Сложность»).

## За автором

- [ ] `М` Выгрузка сайта с разделом «Экосистема» — в SocialMediaImage, по его
      правилам и после «да» автора; перед выгрузкой — свежий срез и
      комплект (D-18, `docs/OPERATIONS.md`, раздел 4). После выгрузки —
      CSP `/ecosystem/` с `script-src 'self'`.

## Вне репозитория — правят сессии тех репозиториев

- [ ] `М` **DPmeta.** В ветке `claude/dreamy-bardeen-xe4k6r` — DPmetaApp и
      DPvisualisation в реестре, контракты C-11 («DPmeta → DPvisualisation»)
      и C-12 («DPvisualisation → сайт»), классификация автора; решения
      `DPmeta:M-12` (слой синхронизации) и `DPmeta:M-13` (правило
      вливания) — на них ссылаются D-21 и модель. В реестре той ветки
      DP_AI_Orcestrator ещё с `place = "core"`, а он заморожен
      (`DPmetaApp:D-28`; здесь — в `[registry] exclude`, D-22): правка —
      сессией DPmeta. Когда ветка окажется в `main` DPmeta — здесь:
      `contracts` у потоков `vis.dpmeta.dpvisualisation`,
      `vis.dpvisualisation.smi` и `orc.dpmeta.dpmetaapp` (C-13 — манифест
      `dpai.ecosystem`, `DPmeta:M-11`), иначе сбор покажет
      `CONTRACT_UNCOVERED`.
- [ ] `М` **DPmetaApp.** Живой режим схемы — его задача (D-18, фаза B; с
      его стороны — `DPmetaApp:D-26`).

## Долг

- [ ] `М` Срез — на 12.10.2026, 14:12 UTC; следующий — перед выгрузкой сайта (D-18)
      или по `collect` (срез старше суток просмотрщик помечает «устарел»,
      F-31).
