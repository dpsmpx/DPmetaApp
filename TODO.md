# TODO

Только незавершённое. Решения — `docs/direction/DECISIONS.md`; что
дальше по фазам — `ROADMAP/ROADMAP.md`; где остановилась сессия —
`docs/session/HANDOFF.md`.

## За автором

- [ ] **HTTPS для `dpsmpx.com` — срочно (П2-01).** Домен зарегистрирован
  (RDAP: 05.10.2026, 03:41:54 UTC, регистратор Beget, до 05.10.2028; NS
  Beget) и по HTTP отдаёт сайт. HTTPS не отвечает: соединение обрывается
  (03:46 UTC), а HTTPS к `beget.com` из той же сессии работает, —
  сертификата для домена, вероятно, нет. Выпустить бесплатный сертификат
  для `dpsmpx.com` и `www.dpsmpx.com` в панели Beget (где именно — не
  проверено). До этого ссылки сайта (canonical, фиды, sitemap) ведут на
  `https://` и не открываются, а проверка после выгрузки покажет «?».
  Ссылки на сайт в профилях — после HTTPS. Затем — перенаправление
  HTTP → HTTPS (П2-06).
- [ ] **Инцидент 05.10.2026 — закрыть** (разбор и команды —
  `docs/audits/2026-10-05-SITE_LEAK_INCIDENT.md`, §6, §8–§10). Сделано: улики
  сохранены, второй ярлык убран. Сервер проверен снаружи (§9) и с
  телефона (§10): сборка без имени, список файлов совпадает со сборкой
  (39), `--dry-run` на `main` — одно изменение, `.htaccess`. Осталось:
  1. **Второй ключ в `authorized_keys`** — `dpsmpx.com-termux` рядом с
     `dpsmpx deploy termux` (§10). Сверить отпечатки с ключами телефона
     (`CREDENTIALS.md`, «Проверка сервера»). Свой и не нужен — удалить
     строку на сервере и закрытый ключ на телефоне; чужой — удалить
     строку сразу, сменить пароль панели, сообщить сессии.
  2. Окно доступности. По имени `dpsmpx.com` утечка не открывалась:
     домен зарегистрирован после восстановления (§9). Остаются
     технический домен Beget и обращения по IP: домены сайта в панели,
     журнал доступа за 04–05.10 (или тикет). Итог — сюда и в срез.
  3. Переписка с ChatGPT: если там закрытый ключ или пароль панели —
     сменить (`CREDENTIALS.md`, §3).
  4. Адрес — в `~/.config/dpsmpx/deploy.env`; `deploy.conf` удалить;
     ярлык — по шаблону из `CREDENTIALS.md`, «Ярлык Termux:Widget».
- [ ] **Первая штатная выгрузка сайта (Д35, П2-02)** — `site deploy`
  (`--dry-run` → «да» → выгрузка) с записью в `build/logs/deploy.jsonl`;
  обе выгрузки 05.10.2026 прошли в обход системы. На `main` (`f360d8a`)
  пробный прогон даёт одно изменение — `.htaccess` (`Options -Indexes`).
  Ради него одного не выгружать (`Options -Indexes` — на решение
  автора); уйдёт с первым изменением сайта. До сертификата проверка
  страниц после выгрузки покажет «?».
- [ ] **Ссылки на YouTube в профилях — срочно (ОР-3).** Telegram
  `@dpsmpx`: `@9_6ox` → `https://www.youtube.com/@dpsmpx` — прежний
  handle держится 14 дней, потом его может занять другой канал (IC-01).
  TikTok `@dpsmpx`: `youtube.com/dpsmpx` уже даёт 404 → тот же адрес
  (IC-10).
- [ ] **Telegram-бот для публикации (П4-01…03).** Бот у @BotFather,
  закрытый тестовый канал, бот — админ с публикацией; числовые id канала
  и бота → `data/platforms/destinations.yaml`; токены — только у себя
  (`docs/operations/CREDENTIALS.md`, «Telegram»). Затем `--safe` в
  тестовый канал.
- [ ] **CI-0001 — презентационное изображение.** После ревизии подписи
  (сессия, К1-01): утвердить вариант (гейт Brand) и решить О-1; права
  фотографа A-001 (гейт Legal); одобрить варианты площадок
  (`python3 -m tools.cs approve CI-0001 <вариант>` — в своём терминале).
  Затем — финальный экспорт и кампания CMP-0001.
- [ ] **VK (П5-01, П5-02):** личная страница или сообщество; просить ли
  у VK право `wall` для автоматической публикации или оставить ручной путь.
- [ ] **Аватар TikTok (ОР-10).** Сейчас — обрезка пробного рендера A:
  текст срезан, читаются обои (IC-11). Оставить, заменить после
  утверждения CI-0001 или поручить отдельный квадратный аватар.
- [ ] **README профиля GitHub (ОР-4)** — черновик правок ниже.
- [ ] ОР-5 (handle канала-дневника), ОР-6 (Discord), ОР-7 (английский
  для игровых площадок), ОР-8 (границы личного), ОР-9 (пост № 67) —
  `DECISIONS.md`, §3.
- [ ] Происхождение встроенного шрифта 5×7 Galdhold (GH-ASSETS).
- [ ] Снимки и видео Galdhold для страницы проекта на сайте (П2-04) —
  файлы и права, сессия запишет в реестр ассетов.

## За сессией

- [ ] **Сайт — в работе до слов автора «сайт готов» (Д34).** Каждая
  сессия берёт следующую открытую задачу списка «Сайт — в работе»
  (`ROADMAP/ROADMAP.md`, П2) без отдельного поручения; выгрузка от
  этих слов не зависит (Д34, 05.10.2026).
- [ ] **К1-01: ревизия подписи CI-0001 по Д20** — «Борис Давыдов /
  dpsmpx» на изображении (спецификация варианта A, рендер, критика
  отдельным проходом, экспорт-превью), alt-текст `variants/github.en.md`
  и `media_alt` вариантов `website.ru`/`website.en` (сейчас описывают
  подпись «dpsmpx»; после правки — `variant seal`). **Сайт — без
  имени (Д37):** варианты `website.*` и изображения сайта остаются с
  подписью «dpsmpx»; ревизия — только для других площадок.
- [ ] Перепроверить числа с `recheck_by`: DP-STAGE и GH-READINESS — до
  18.10.2026; GH-LOC, GH-CHECKS, GH-COMMITS, GH-STAGES, GH-WINDOWS,
  GH-PERF-2026-09-30, счётчики площадок — до 04.11.2026. Смена факта
  сделает зависимые варианты устаревшими (`python3 -m tools.cs stale`).
- [ ] VK: официальные требования к размерам изображений (`vk_post`
  сейчас `verified: false`).
- [ ] YouTube: `youtube_thumbnail.max_bytes` — API принимает обложки до
  50 МБ с 14.09.2026 (П7-04); запись 2 МБ — ограничение загрузки с телефона.
- [ ] TikTok: безопасная зона 9:16 по официальному шаблону, когда будет
  доступ (`tiktok_vertical` без `safe_area`).
- [ ] Discord: требования онбординга — при настройке сервера (после ОР-6).
- [ ] `Options -Indexes` на Beget — **на решение автора.** Заголовки
  `.htaccess` Beget к статике применяет (05.10.2026, 03:45 UTC: CSP,
  `nosniff`, `X-Frame-Options` и др. на `/`), а список файлов каталогов
  выключен и без этой строки: `/fonts/` и `/api/` — 403. Строка из PR с
  защитами здесь ничего не добавляет, а если Beget запрещает `Options` в
  `.htaccess`, весь сайт ответит 500. Оставить или убрать? Оставлена —
  сразу после выгрузки: `curl -I http://dpsmpx.com/` — 200, `/fonts/` —
  403; `/` — 500 — убрать строку в `tools/cs/site/build.py` и выгрузить
  снова.

## Черновик: README профиля GitHub (ОР-4)

Правки к README `dpsmpx/dpsmpx` (коммит `433f627`, аудит 04.10.2026,
§2). Формулировки — из `allowed_wording` реестра; править репозиторий —
только по согласию автора. С 04.10.2026 имя открыто (Д20): в шапке
можно «Boris Davydov (dpsmpx)» (AU-NAME); после запуска сайта —
ссылка на `https://dpsmpx.com` как точку входа (Д21).

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
