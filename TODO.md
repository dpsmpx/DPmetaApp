# TODO

Только незаконченное (CLAUDE.md, «TODO.md»). Метка — `М`, `С`, `Т`, `К`
(`ROADMAP/ROADMAP.md`, «Сложность»).

## За автором

- [ ] `М` Ветка `claude/dreamy-bardeen-xe4k6r` — по решению автора 9
      октября 2026 остаётся невлитой; в ней вся работа PR #1
      (`claude/eager-wozniak-krzsx4`) и сверх неё. Пока она не влита,
      исходник схемы на сайте есть только в невлитых ветках.
- [ ] `М` Выгрузка сайта с разделом «Экосистема» — в SocialMediaImage, по его
      правилам и после «да» автора; перед выгрузкой — свежий срез и
      комплект (D-18, `docs/OPERATIONS.md`, раздел 4). После выгрузки —
      CSP `/ecosystem/` с `script-src 'self'`.

## Вне репозитория — правят сессии тех репозиториев

- [ ] `М` **DPmeta.** В ветке `claude/dreamy-bardeen-xe4k6r` — DPmetaApp,
      DPvisualisation и DP_AI_Orcestrator в реестре, контракты C-11
      («DPmeta → DPvisualisation») и C-12 («DPvisualisation → сайт»),
      классификация автора. Когда ветка окажется в `main` DPmeta — здесь:
      `contracts` у потоков `vis.dpmeta.dpvisualisation` и
      `vis.dpvisualisation.smi`, иначе сбор покажет `CONTRACT_UNCOVERED`.
- [ ] `М` **DPmetaApp.** ТЗ, решения и TODO приложения — в его ветке
      `claude/dreamy-bardeen-xe4k6r` (взяты из `claude/vigilant-franklin-61x8kq`);
      в `main` их нет. Ссылки модели `DPmetaApp:docs/direction/TECHNICAL_TASK.md`,
      `DPmetaApp:D-23`…`DPmetaApp:D-26` ведут туда (пометки `note` у
      `dpmetaapp`, `ntfy` и потока `vis.dpvisualisation.dpmetaapp`). Живой
      режим схемы — его задача (D-18, фаза B; с его стороны — `DPmetaApp:D-26`).
- [ ] `М` **DP_AI_Orcestrator.** Строка состояния README («идут этапы 0 и
      1…») расходится с разделом «Прогресс» (`DPmeta:SQ-26` в ветке
      DPmeta).

## Долг

- [ ] `М` Срез — на 10.10.2026; следующий — перед выгрузкой сайта (D-18)
      или по `collect` (срез старше суток просмотрщик помечает «устарел»,
      F-31).
