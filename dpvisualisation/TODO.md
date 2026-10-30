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

- [ ] `С` **Модель — под переезды `DPmetaApp:D-43`** (D-26, D-27, ответы
      автора 25 октября 2026: узлы остаются, оркестратор — тоже узел). У узла
      DPmetaApp — `history_exclude` корней SocialMediaImage `46b3afe…`,
      DPvisualisation `9769a94…`, DPmeta `686a27a…`, DPprofile `c86ba57…` и
      оркестратора `3ea2aea…`; узел `orchestrator` — вернуть, из
      `[registry] exclude` — снять; стадии — и по `DPmeta:SQ-33`; роли и
      стадии узлов — подсистемы DPmetaApp; ссылки `Проект:путь` на
      перенесённые файлы; реестр — из `dpmeta/` (`DPmeta:M-16`). Затем — свежий срез с полных клонов и комплект сайта.
- [ ] `М` **Контракты DPmeta у потоков.** Ветка DPmeta с C-11…C-13 влита
      вместе с переездом (`DPmeta:M-14`): `contracts` у потоков
      `vis.dpmeta.dpvisualisation`, `vis.dpvisualisation.smi` и
      `orc.dpmeta.dpmetaapp` (C-13 — манифест `dpai.ecosystem`,
      `DPmeta:M-11`) — вместе с моделью под переезды, иначе сбор покажет
      `CONTRACT_UNCOVERED`. Место оркестратора в реестре — по
      `DPmeta:M-16` (строка с каталогом `dpai/`), в модели — D-27.
- [ ] `М` **Живой режим схемы в приложении** — задача DPmetaApp (D-18,
      фаза B; с его стороны — `DPmetaApp:D-26`), теперь в том же
      репозитории.
- [ ] `М` **DPmeta.** AIM_Trainer_pas и DP_Clock — в реестр `DPmeta:tools/ecosystem.toml`
      (D-23: узлы `aimtrainer`, `dpclock` в модели есть, сбор пишет их в
      `not_in_registry`); Andors-Love и TrueJointTileMaker — из `archive` в
      новое место `periphery` (`DPmeta:M-16`). Правка — в `dpmeta/` по его
      правилам (`DPmeta:M-14`).

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
