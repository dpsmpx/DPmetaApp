# Реестр источников

Всё, на что факт может сослаться (D-05). Источника нет в реестре — факт
на него не ссылается. Правила ссылок и дат — `docs/sources/CITATION.md`;
места внутри репозиториев — `docs/sources/MAP.md`; наблюдения без
постоянного адреса — `docs/sources/NOTES.md`. Форму проверяет
`tools/verify/check_registry.py`.

- **Вид:** `repo` — репозиторий автора на GitHub (ссылки — коммит, файл,
  релиз); `site` — публичный сайт (ссылки — адрес страницы с датой
  наблюдения); `self` — этот репозиторий.
- **Доступ:** `public` — проверит любой; `private` — только владелец и
  те, кому он дал доступ (на 2026-10-04).
- **Клон:** имя каталога в `build/sources/` для
  `tools/verify/fetch_sources.sh`.
- Номер источника не меняется и не переиспользуется; переименование
  репозитория — уточнение в «Что это», адрес — нынешний.

| Источник | Вид | Адрес | Доступ | Клон | Что это |
|---|---|---|---|---|---|
| S-00 | self | github.com/dpsmpx/DPprofile | private | — | этот репозиторий |
| S-01 | repo | github.com/dpsmpx/Galdhold | private | galdhold | репозиторий VoxelRPG и движка (D-03); до 2026-10-04 — `3D_Voxel_OpenWorld_RPG` (F-0030) |
| S-02 | repo | github.com/dpsmpx/DPlang | private | dplang | репозиторий языка DPlang; рабочее имя языка — «C-» (F-0038) |
| S-03 | repo | github.com/dpsmpx/DPpass | private | dppass | репозиторий DPpass |
| S-04 | repo | github.com/dpsmpx/dpsmpx | public | dpsmpx | репозиторий профиля автора (README на странице профиля GitHub) |
| S-05 | repo | github.com/dpsmpx/Andors-Love | public | andors-love | ранний публичный проект автора |
| S-06 | repo | github.com/dpsmpx/TrueJointTileMaker | public | truejointtilemaker | ранний публичный проект автора |
| S-07 | repo | github.com/dpsmpx/DP_Random_Cells | public | dp_random_cells | ранний публичный проект автора |
| S-08 | repo | github.com/dpsmpx/tanks_blitz_stat_chat | public | tanks_blitz_stat_chat | публичный проект автора |
| S-09 | repo | github.com/dpsmpx/AIM_Trainer_pas | public | aim_trainer_pas | публичный проект автора |
| S-10 | site | github.com/dpsmpx | public | — | аккаунт GitHub автора (данные аккаунта — заметка N-0001) |
| S-11 | site | store.steampowered.com | public | — | магазин Steam: поиск и страницы игр |
