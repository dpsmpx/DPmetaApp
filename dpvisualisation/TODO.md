# TODO

Только незаконченное (CLAUDE.md, «TODO.md»). Метка — `М`, `С`, `Т`, `К`
(`ROADMAP/ROADMAP.md`, «Сложность»).

## За автором

- [ ] `М` Следующая выгрузка сайта (SocialMediaImage, по его правилам и после
      «да» автора) — перед ней свежий срез и комплект (D-18,
      `docs/OPERATIONS.md`, раздел 4). Выгрузка 12.10.2026 прошла с
      комплектом `99d05f6` (срез 12.10.2026, 14:12 UTC); CSP `/ecosystem/` —
      `script-src 'self'`, проверен снаружи в тот же день (SocialMediaImage,
      `TODO.md`).

## Подсистема DPmetaApp (D-25)

- [ ] `С` **Модель — под переезды `DPmetaApp:D-43` — вопрос автору.**
      SocialMediaImage и DPvisualisation с 25 октября 2026 — каталоги
      DPmetaApp, следом DPmeta и DPprofile; их репозитории заморожены. По
      D-22 и ТЗ F-01 замороженный репозиторий — не узел, а
      `[registry] exclude`, его работа — в счёте DPmetaApp (как у
      DP_AI_Orcestrator); узел с историей до слияния и `history_exclude`
      его корня у DPmetaApp — только исключением по слову автора, как у
      DPpass (D-24). Что из этого для каждой системы — решает автор; до
      ответа модель не менять. После ответа — роли и стадии узлов,
      ссылки `Проект:путь` на перенесённые файлы, реестр — из `dpmeta/`
      после переезда DPmeta (корни: SocialMediaImage `46b3afe…`,
      DPvisualisation `9769a94…`); затем свежий срез с полных клонов и
      комплект сайта.
- [ ] `М` **Живой режим схемы в приложении** — задача DPmetaApp (D-18,
      фаза B; с его стороны — `DPmetaApp:D-26`), теперь в том же
      репозитории.

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
- [ ] `М` **DPmeta.** AIM_Trainer_pas и DP_Clock — в реестр `DPmeta:tools/ecosystem.toml`
      (D-23: узлы `aimtrainer`, `dpclock` в модели есть, сбор пишет их в
      `not_in_registry`); Andors-Love и TrueJointTileMaker — из периферии в
      текущую систему (`DPmeta:docs/ECOSYSTEM.md`). Правка — сессией DPmeta.

## Долг

- [ ] `С` **`CLONE_SHALLOW` не всегда видит неполный клон** (было до D-24;
      независимая проверка 25.10.2026): `partial = shallow and first > since`
      (`tools/dpvis/collect.py`) смотрит на первый доступный коммит, а не на
      границу из `.git/shallow`. Клон AIM Trainer глубиной 50: граница 18.10 —
      внутри окна, первый коммит 05.07 — диагностики нет, в окне 173 коммита
      вместо 240. Предложение: граница — по датам коммитов из `.git/shallow`;
      образец, который правило ловит, и чистый (`tests/test_collect.py`). У
      узла с `history_exclude` неполный клон уже даёт `HISTORY_ROOT_NOT_FOUND`
      (D-24).
- [ ] `М` Срез — на 25.10.2026, 10:18 UTC, с полных клонов (неполный клон занижает числа узла, L-04); следующий — перед выгрузкой сайта (D-18)
      или по `collect` (срез старше суток просмотрщик помечает «устарел»,
      F-31).
