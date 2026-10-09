# TODO

Только незаконченное (CLAUDE.md, «TODO.md»). Метка — `М`, `С`, `Т`, `К`
(`ROADMAP/ROADMAP.md`, «Сложность»).

## За автором

- [ ] `С` **Влить версию 1.0.0** — ветка `claude/dreamy-bardeen-xe4k6r`
      (в ней вся работа PR #1 из `claude/eager-wozniak-krzsx4` и сверх неё:
      проверки, живой режим, D-16, срез 09.10.2026). Пока она не влита,
      исходник схемы на сайте есть только в невлитых ветках. PR #1 после
      этого можно закрыть.
- [ ] `М` Вопросы ТЗ, §18: Q-01 (цель — та модель для DPmetaApp?), Q-02
      (Apache-2.0 при закрытом репозитории — как `DPmeta:RISK-06`), Q-05
      (где работает живой режим), Q-06 (какие страницы сайта пробовать),
      Q-07 (показывать ли ранние проекты по умолчанию). Q-04 — решает
      DPmetaApp.
- [ ] `М` Выгрузка сайта с разделом «Экосистема» — в SocialMediaImage
      после вливания его ветки (`SocialMediaImage:TODO.md`, «За автором»);
      после выгрузки проверить CSP `/ecosystem/` — `script-src 'self'`.

## Вне репозитория — правят сессии тех репозиториев

- [ ] `М` **DPmeta (Q-03).** Ветка `claude/dreamy-bardeen-xe4k6r` DPmeta:
      DPmetaApp, DPvisualisation и DP_AI_Orcestrator в реестре и карте,
      контракты «DPmeta → DPvisualisation» (C-11) и «DPvisualisation →
      сайт» (C-12). После её вливания — здесь: `contracts` у потоков
      `vis.dpmeta.dpvisualisation` и `vis.dpvisualisation.smi`, иначе сбор
      покажет `CONTRACT_UNCOVERED`.
- [ ] `М` **SocialMediaImage.** Ветка `claude/dreamy-bardeen-xe4k6r`:
      комплект схемы со срезом 09.10.2026 и просмотрщиком D-16, роль и
      стадия DP_AI_Orcestrator из фактов `ORC-WHAT`, `ORC-STAGE`.
- [ ] `М` **DPmetaApp.** ТЗ, решения и TODO приложения — только в невлитой
      ветке `claude/vigilant-franklin-61x8kq`; ссылки модели
      `DPmetaApp:docs/direction/TECHNICAL_TASK.md` и `DPmetaApp:D-24` ведут
      туда (пометки `note` у `dpmetaapp` и `ntfy`). После вливания или
      замены той ветки — сверить узлы `dpmetaapp`, `ntfy` и потоки `app.*`.
- [ ] `М` **DP_AI_Orcestrator.** В его `README.md` строка состояния
      («идут этапы 0 и 1… запускать Claude пока не умеет») расходится с
      разделом «Прогресс» (выполнено 15 из 21); модель взяла «Прогресс».

## Долг

- [ ] `М` Срез — на 09.10.2026; следующий — `collect` по клонам (срез
      старше суток просмотрщик помечает «устарел», F-31).
