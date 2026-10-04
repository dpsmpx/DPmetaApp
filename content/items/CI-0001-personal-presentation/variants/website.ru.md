---
schema: variant/v1
item: CI-0001
platform: website
lang: ru
format: page
source_version: 1
version: 1
status: ready
trust: validated
title: Обо мне
summary: Строю сложные нативные системы — игру, движок и язык — с ИИ как инструментом; архитектура и решения
  — мои.
media:
- exports/A-ru-telegram_post.jpg
media_alt:
- 'Карточка-презентация. Слева — фотография автора. Справа — dpsmpx, {{fact:AU-ROLE#2}}; схема работы
  от замысла через архитектуру, реализацию с ИИ и проверку к Git и продукту — «{{fact:AU-METHOD#1}}».
  Внизу — {{fact:GH-NAME}}: {{fact:GH-GENRE}}, {{fact:GH-TECH}}; {{fact:GH-LOC}}, {{fact:GH-CHECKS}}.'
link: null
generation:
  provider: session
  note: из канона v1 для страницы «Обо мне» и главной (summary — первая строка главной)
deps:
  facts:
    AU-NAME: 5d083e3db9612d37
    AU-ROLE: 0d84826a46110966
    GH-NAME: 18e9e8bc34f1c9f0
    GH-GENRE: 87840cd38160c9f1
    GH-TECH: 885bae9eed2b037d
    GH-ENGINE: 3ae43184e26a50af
    GH-ANDROID: 3ae50de8a7427f5c
    GH-LOC: ac29b63c2833e291
    GH-CHECKS: 5b1aac0836af0098
    DP-WHAT: 091c2d23907bb1fb
    DP-STAGE: 0d8aced210be861e
    AU-METHOD: 95e9ba70d576890a
    AU-MEMORY: 823f4b5a22945aae
  links: {}
  releases: {}
  canonical:
    version: 1
    sha256: 4645db6c929c320b4da7da3a7a683295be6243520e53268973610d20b57acee5
  media:
    exports/A-ru-telegram_post.jpg: f5d778d09184b31aac3c19a9b2256e78fabbec96e3d0ce3df9c05e6e0a262c16
---
Меня зовут {{fact:AU-NAME}}, в сети — dpsmpx. Я {{fact:AU-ROLE}}: {{fact:AU-ROLE#1}}.

## Galdhold

{{fact:GH-NAME#1}} — {{fact:GH-GENRE}}. {{fact:GH-TECH}}, {{fact:GH-ENGINE}}. Игра {{fact:GH-ANDROID}}. В проекте {{fact:GH-LOC}} и {{fact:GH-CHECKS}}. Подробнее — [страница проекта](/projects/galdhold/).

## DPlang

Я {{fact:DP-WHAT}}. Сейчас {{fact:DP-STAGE}}. Подробнее — [страница проекта](/projects/dplang/).

## Как я работаю

{{fact:AU-METHOD}}; {{fact:AU-METHOD#1}}. {{fact:AU-MEMORY}}: {{fact:AU-MEMORY#1}}.
