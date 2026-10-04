---
schema: variant/v1
item: CI-0001
platform: telegram
lang: ru
format: photo_post
version: 1
status: ready
trust: validated
media:
- exports/A-ru-telegram_post.jpg
generation:
  provider: session
  note: перенесено из copy/ миграцией v1→v2; имя — по Д20
note: 'Закреплённая запись-вход канала-дневника (IC-09), RU, с изображением telegram_post. Шаблон — templates/telegram_devlog.md;
  промпт — prompts/text/post.v1.md. facts: AU-HANDLE, AU-ROLE, AU-METHOD, AU-MEMORY, GH-NAME, GH-GENRE,
  GH-TECH, GH-ENGINE, GH-ANDROID, GH-LOC, GH-CHECKS, DP-WHAT, DP-STAGE'
claims:
- AU-NAME
- AU-HANDLE
- AU-ROLE
- AU-METHOD
- AU-MEMORY
- GH-NAME
- GH-GENRE
- GH-TECH
- GH-ENGINE
- GH-ANDROID
- GH-LOC
- GH-CHECKS
- DP-WHAT
- DP-STAGE
source_version: 1
link: null
deps:
  facts:
    AU-NAME: 5d083e3db9612d37
    AU-HANDLE: 0535a93f2279e87d
    AU-ROLE: 0d84826a46110966
    AU-METHOD: 95e9ba70d576890a
    AU-MEMORY: 823f4b5a22945aae
    GH-NAME: 18e9e8bc34f1c9f0
    GH-GENRE: 87840cd38160c9f1
    GH-TECH: 885bae9eed2b037d
    GH-ENGINE: 3ae43184e26a50af
    GH-ANDROID: 3ae50de8a7427f5c
    GH-LOC: ac29b63c2833e291
    GH-CHECKS: 5b1aac0836af0098
    DP-WHAT: 091c2d23907bb1fb
    DP-STAGE: 0d8aced210be861e
  links: {}
  releases: {}
  canonical:
    version: 1
    sha256: 4645db6c929c320b4da7da3a7a683295be6243520e53268973610d20b57acee5
  media:
    exports/A-ru-telegram_post.jpg: f5d778d09184b31aac3c19a9b2256e78fabbec96e3d0ce3df9c05e6e0a262c16
---
Я — Борис Давыдов (dpsmpx), независимый разработчик: разрабатываю игру, движок и язык программирования.

Galdhold — воксельная action-RPG с открытым процедурным миром. C++20 и Vulkan, собственный движок, отделённый от игры. Игра проверялась на Android-телефонах (Redmi Note 13, Poco X7 Pro 5G). Больше 150 тысяч строк C++ и больше 5 000 автоматических проверок (октябрь 2026).

DPlang — мой язык программирования. Ранняя стадия: выбран синтаксис, начат компилятор.

Как работаю: ИИ пишет большую часть кода, архитектуру и решения определяю я, каждое изменение принимаю сам. ИИ может забывать сессию — проект не обязан забывать: память проекта — в Git, решениях и проверках.

Здесь — дневник разработки Galdhold: что сделано, почему так и что дальше. Видео — https://www.youtube.com/@dpsmpx
