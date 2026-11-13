# Происхождение

Всё чужое в репозитории — с записью: что, откуда, под какой лицензией и
зачем (`DPmeta:E-11`). Код неизвестного происхождения запрещён: «нашёл в
интернете», «сгенерировалось похожим на проект X» — не принимается
(`DPpass:CLAUDE.md`, «Происхождение»).

## Код

Чужого кода нет. Проверки `tools/check/` написаны здесь; идеи взяты из
DP-Verify и других репозиториев автора — источник каждой идеи в
`docs/PROCESS.md`, раздел 1. Код закрытых репозиториев сюда не
копировался (`DPmeta:C-05`) — кроме подсистемы «AI» по решению автора:

| Что | Откуда | Лицензия | Куда | Решение |
|---|---|---|---|---|
| Подсистема «AI»: код, документы, проверки и вся история git | `dpsmpx/DP_AI_Orcestrator`, ветка PR #4 `claude/nice-gates-flj2u8` (`463415c`) вместе с `main` (`5108df4`); слияние поддерева `b66079a` | «все права защищены» (`dpai/LICENSE`, `DP_AI_Orcestrator:D-101`) — та же, что в корне (D-47) | `dpai/` | D-28 |

Происхождение зависимостей и идей самой подсистемы — `dpai/docs/PROVENANCE.md`.

## Зависимости

Модули `:core:*` (кроме `:core:git` — JGit, ниже), `:cli` и `:tests:corpus` сторонних зависимостей не имеют —
только ядро `dpai/` (`core`, `storage`) и JDK (`java.net.http` — транспорт
`dpmeta-cli`; TLS — системными корнями).
Каждая новая зависимость — строка ниже и запись в каталоге версий;
кандидаты и их лицензии — ТЗ, §6.1.
Зависимости подсистемы «AI» и ядра (Kotlin, JUnit, Android Gradle Plugin,
Compose, JSch; wrapper Gradle со сверкой SHA-256) — `dpai/docs/PROVENANCE.md`
и `dpai/gradle/libs.versions.toml` (D-28).

| Компонент | Версия | Лицензия | Зачем | Модуль | Решение |
|---|---|---|---|---|---|
| Eclipse JGit (`org.eclipse.jgit:org.eclipse.jgit`, каталог `jgit`; с ним JavaEWAH 1.1.13 — Apache-2.0, SLF4J API 1.7.30 — MIT) | 5.13.5.202508271544-r | EDL-1.0 (BSD-3-Clause) | клоны репозиториев: клонирование, обновление, ветки и файлы (ветка 5.13 — последняя для Java 8, на Android без недостающих API JDK 11+; CVE-2023-4759 закрыта) | `:core:git` | D-65 |
| AndroidX Core (`androidx.core:core`, каталог `androidx-core`) | 1.18.0 | Apache-2.0 | `FileProvider` — «Поделиться» экспортом истории (F-36) | `:app` | D-67 |

## Данные

Корпус тестов — синтетические образцы форматов, написанные здесь; копий
файлов других репозиториев нет. Двоичных файлов вне `dpai/` нет; в
`dpai/` — `gradle-wrapper.jar` со сверкой SHA-256
(`dpai/docs/PROVENANCE.md`) и корпуса подсистемы (`dpai/tests/data/`).

JGit обновлён 11 ноября 2026 после аудита K-04: версия 5.13.5 сохраняет
Java 8 и отключает внешние сущности/DOCTYPE в ManifestParser (CVE-2025-4949).
Официальный исходник тега: https://github.com/eclipse-jgit/jgit/blob/v5.13.5.202508271544-r/org.eclipse.jgit/src/org/eclipse/jgit/gitrepo/ManifestParser.java ,
blob `726c7b430e0c221ab6734dc363d8c589b32b1086`. Глобальный реестр
транспортов JGit не изменён; URL сетевого клона проверяется до запуска.

Проверка целостности Gradle (11 ноября 2026, N-14/B-01): файлы
gradle/verification-metadata.xml и dpai/gradle/verification-metadata.xml
получены полными build из объявленных Maven Central/Google Maven.
Первичная запись доверяет TLS этих источников. 636 общих артефактов
обеих сборок и 363 записи прежнего DPpass совпали по SHA-256.
Проверка подписи PGP не заявляется. Намеренно неверный SHA-256 KGP
отклонён обычным Gradle strict в обеих сборках; файлы восстановлены.
Источник механизма: https://docs.gradle.org/current/userguide/dependency_verification.html .

## Среды Python и Kotlin IDE (D-109)

Среды скачиваются на target по подтверждению и не включаются в APK.
Python 3/venv/pip и JDK 21 — пакеты установленной Alpine, лицензии и
зависимости сохраняет apk. PyPI: https://pypi.org/simple/ ; известное
имя import сопоставляется с дистрибутивом, ручной ввод допускает точную
версию. Код проектов туда не отправляется, системный Python не меняется.

Kotlin/JVM 2.4.10: официальный архив JetBrains, Apache-2.0 и лицензии
дистрибутива, https://github.com/JetBrains/kotlin/releases/tag/v2.4.10 .
ZIP 87 077 443 байта, SHA-256
`473dd66c7a3ef4b182065b3da670466c1bf2773a9dbb0ed8b33a39fe9d4f876d`:
сверен с digest GitHub asset и официальным ZIP; проверяется до распаковки.
Установка staged, неудача сохраняет прежний компилятор. JVM-библиотеки
разрешает штатный Kotlin main-kts через Maven Central
https://repo.maven.apache.org/maven2/ , с POM и транзитивными зависимостями.
Их лицензии/артефакты остаются в кэше среды, не становятся зависимостями APK.

## Языковые службы Python и Kotlin (D-110)

Ставятся в Linux приложения только кнопкой человека и не входят в APK.
Сверено 12 ноября 2026.

- pylsp (python-lsp-server 1.14, MIT) и ruff 0.15.15 (MIT) — пакеты
  Alpine 3.24 `py3-lsp-server`, `ruff`; Jedi — зависимость пакета.
- pyright 1.1.414 (MIT): архив реестра npm
  https://registry.npmjs.org/pyright/-/pyright-1.1.414.tgz , SHA-256
  `bf5f473f6167c0d14175492c3263d783b4489a6956e1c06c18e15228e3a3fa42`;
  sha512 совпал с `dist.integrity` реестра. Работает на Node.js Alpine.
- kotlin-lsp (JetBrains, Apache-2.0 с закрытыми частями JetBrains Air и
  Fleet; https://github.com/Kotlin/kotlin-lsp ): сборки EAP, срок около
  30 дней, поэтому версия не закреплена — сборка со страницы выпусков,
  архив и его `.sha256` с download.jetbrains.com. Запуск без встроенной
  glibc-JVM: JDK 25 Alpine, gcompat для наблюдения за файлами,
  `--data-sharing=none`. Проверена сборка 263.7084.0: на Alpine x86_64
  (musl) в chroot и на хосте — импорт Gradle, диагностика, переходы,
  переименование.
- rocksdbjni 10.10.1 (Apache-2.0 / GPL-2.0): общий архив Maven Central
  https://repo1.maven.org/maven2/org/rocksdb/rocksdbjni/10.10.1/rocksdbjni-10.10.1.jar ,
  SHA-256 `3e3c9e439b89c2ac4aaa7512bc73052d44b3a6397daed6cb88168cefa6e431a9`
  (совпал с `.sha256` Maven Central). Из него берётся только musl-библиотека
  нужной архитектуры: набор JNI-символов (1525) совпал с библиотекой
  сборки kotlin-lsp полностью, для x86_64 и aarch64.
- Платформа Android SDK: архив из манифеста
  https://dl.google.com/android/repository/repository2-3.xml , сверка SHA-1
  манифеста, как у sdkmanager; ставится после принятия лицензии Google
  человеком, отметка — SHA-1 текста лицензии в `licenses/`.
