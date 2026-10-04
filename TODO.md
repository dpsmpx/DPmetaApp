# TODO

Только незавершённое. Решения — `docs/direction/DECISIONS.md`; что
дальше по фазам — `ROADMAP/ROADMAP.md`; где остановилась сессия —
`docs/session/HANDOFF.md`.

## За автором

- [ ] **Ссылки на YouTube в профилях — срочно (ОР-3).** Telegram
  `@dpsmpx`: `@9_6ox` → `https://www.youtube.com/@dpsmpx` — прежний
  handle держится 14 дней, потом его может занять другой канал (IC-01).
  TikTok `@dpsmpx`: `youtube.com/dpsmpx` уже даёт 404 → тот же адрес
  (IC-10).
- [ ] **CI-0001 — презентационное изображение.** Рекомендован вариант A
  (`docs/content/products/personal_presentation/DECISION.md`). Нужно:
  утвердить или выбрать другой (гейт Brand); решить ОР-1 (только
  `dpsmpx` или и имя); подтвердить права фотографа снимка A-001 (гейт
  Legal). После этого — финальный экспорт и публикация автором.
- [ ] **Аватар TikTok (ОР-10).** Сейчас — обрезка пробного рендера A:
  текст срезан, читаются обои (IC-11). Оставить, заменить после
  утверждения CI-0001 или поручить отдельный квадратный аватар.
- [ ] **README профиля GitHub (ОР-4)** — черновик правок ниже.
- [ ] ОР-5 (handle канала-дневника), ОР-6 (Discord), ОР-7 (английский
  для игровых площадок), ОР-8 (границы личного), ОР-9 (пост № 67) —
  `DECISIONS.md`, §3.
- [ ] Происхождение встроенного шрифта 5×7 Galdhold (GH-ASSETS).

## За сессией

- [ ] Перепроверить числа с `recheck_by`: DP-STAGE и GH-READINESS — до
  18.10.2026; GH-LOC, GH-CHECKS, GH-COMMITS, GH-STAGES, GH-WINDOWS,
  GH-PERF-2026-09-30, счётчики площадок — до 04.11.2026.
- [ ] VK: официальные требования к размерам изображений (`vk_post`
  сейчас `verified: false`).
- [ ] TikTok: безопасная зона 9:16 по официальному шаблону, когда будет
  доступ (`tiktok_vertical` без `safe_area`).
- [ ] Discord: требования онбординга — при настройке сервера (после ОР-6).

## Черновик: README профиля GitHub (ОР-4)

Правки к README `dpsmpx/dpsmpx` (коммит `433f627`, аудит 04.10.2026,
§2). Формулировки — из `allowed_wording` реестра; править репозиторий —
только по согласию автора.

1. **VoxelRPG → Galdhold** (IC-04). Заголовок раздела:
   `Galdhold (formerly VoxelRPG)`; текст: «a voxel action-RPG with an
   open procedural world» (GH-GENRE), «C++20 and Vulkan» (GH-TECH),
   «my own engine, kept separate from the game» (GH-ENGINE), «graphics,
   sound and music are generated procedurally» (GH-ASSETS), «tested on
   Android phones (Redmi Note 13, Poco X7 Pro 5G)» (GH-ANDROID), «over
   150,000 lines of C++ (October 2026)» (GH-LOC), «over 5,000 automated
   checks» (GH-CHECKS), «planned for Android and Windows (Steam)»
   (GH-RELEASE-PLAN), «a Windows version is in progress» (GH-WINDOWS).
2. **Лицензия** (IC-05): заменить строку о «proprietary source-available»
   на «Closed source. Progress is shared through the dev log and videos.»
   (GH-PRIVATE).
3. **Ссылки** (IC-04): убрать ссылки на закрытые репозитории
   (`3D_Voxel_OpenWorld_RPG`, `DPlang` — 404 для посетителя); вместо них
   — дневник `https://t.me/gh_3D_Voxel_OpenWorld_RPG`, видео
   `https://youtu.be/mMDi7HjTBkM`, канал `https://www.youtube.com/@dpsmpx`;
   по желанию — `https://www.tiktok.com/@dpsmpx` (Д19).
4. **Стек без стрелки зависимости** (IC-06): вместо
   `DPlang → Engine → VoxelRPG → future native products` — проекты рядом:
   «Galdhold — the game and its engine» и отдельно «DPlang — I'm
   designing my own programming language» (DP-WHAT), «early stage:
   syntax chosen, compiler work started» (DP-STAGE, перепроверить до
   18.10.2026), «Galdhold does not depend on DPlang» (DP-INDEPENDENT).
5. **Метод** — оставить смысл, сверить с AU-METHOD: «AI agents write most
   of the code; architecture and final decisions stay with me»; можно
   добавить строку автора «the AI can forget the session; the project
   does not have to» (AU-MEMORY).
6. **Баннер** — экспорт `github_social` CI-0001 (EN) после утверждения.
