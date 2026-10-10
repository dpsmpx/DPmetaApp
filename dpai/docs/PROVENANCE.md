# Происхождение

Откуда взято всё, что в репозитории написано не здесь: зависимости,
инструменты сборки, двоичные файлы, идеи из других проектов (D-16,
D-17). `tools/verify/check_dependencies.py` сверяет таблицу §2 с
`gradle/libs.versions.toml`.

## 1. Правила

- Код неизвестного происхождения не принимается.
- Зависимость — с версией, лицензией, источником и датой проверки.
- Двоичный файл — только записанный здесь с SHA-256.
- Идея из другого репозитория экосистемы — со ссылкой `Проект:номер` или
  `Проект:путь`; код закрытых репозиториев сюда не переносится (D-16).

## 2. Зависимости

<!-- provenance: begin -->
| Компонент | Версия | Лицензия | Куда попадает | Источник | Проверено |
|---|---|---|---|---|---|
| `kotlin` (Kotlin Gradle Plugin, stdlib, kotlin-test) | 2.4.20 | Apache-2.0 | stdlib — во время выполнения; остальное — сборка и тесты | Maven Central, `org.jetbrains.kotlin` | 6 октября 2026 |
| `junit` (JUnit BOM, Jupiter, Platform Launcher) | 6.1.3 | EPL-2.0 | только тесты | Maven Central, `org.junit` | 6 октября 2026 |
| `agp` (Android Gradle Plugin) | 9.4.1 | Apache-2.0 | только сборка модуля `android` | Google Maven, `com.android.tools.build` | 7 октября 2026 |
| `compose-bom` (Compose BOM: Material 3, UI, Foundation, Runtime) | 2026.09.00 | Apache-2.0 | приложение Android во время выполнения | Google Maven, `androidx.compose` | 7 октября 2026 |
| `activity-compose` (AndroidX Activity Compose) | 1.13.0 | Apache-2.0 | приложение Android во время выполнения | Google Maven, `androidx.activity` | 7 октября 2026 |
| `jsch` (JSch, форк mwiede) | 2.28.7 | BSD-3-Clause, ISC | модуль `jsch` → приложение Android во время выполнения | Maven Central, `com.github.mwiede` | 7 октября 2026 |
<!-- provenance: end -->

Версии совпадают с теми, что закреплены в `DPpass:gradle/libs.versions.toml`
на 6 октября 2026: одна связка Kotlin и JUnit на экосистему проще в
сопровождении.

Зависимости модуля `android` (D-34) — последние стабильные на 7 октября
2026; ядро от них не зависит. Compose BOM 2026.09.00 требует compileSdk 37
(платформа `android-37.0`); targetSdk остаётся 36.

## 3. Gradle

| Что | Значение |
|---|---|
| Версия | 9.8.0 |
| Дистрибутив | `gradle-9.8.0-bin.zip`, SHA-256 `bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c` (по `services.gradle.org`; совпадает с `DPpass:gradle/wrapper/gradle-wrapper.properties`) |
| `gradle/wrapper/gradle-wrapper.jar` | SHA-256 `238e777fcddd7e34f9708186085def2abd6e08e658505b38718d79d74c21abd5` (по `gradle-9.8.0-wrapper.jar.sha256`) |

## 4. Двоичные файлы

| Файл | SHA-256 | Откуда |
|---|---|---|
| `gradle/wrapper/gradle-wrapper.jar` | `238e777fcddd7e34f9708186085def2abd6e08e658505b38718d79d74c21abd5` | Gradle 9.8.0, §3 |

## 5. Идеи из экосистемы

ТЗ, §5 велит взять проверенные идеи других репозиториев автора. Здесь —
что взято и откуда; код не переносился, написан заново.

| Идея | Откуда | Где здесь |
|---|---|---|
| `Outcome` и `Diagnostic` с причинами, коды — публичный контракт | `DPpass:docs/direction/DECISIONS.md`, D-22 | `core`, D-05 |
| `Secret` и `Redactor` по умолчанию | `DPpass:docs/direction/DECISIONS.md`, D-11 | `core`, D-14 |
| `Clock` и тесты без сна | `DPpass:docs/direction/DECISIONS.md`, D-21 | `core`, D-07 |
| Журнал решений с проверкой у каждого, план с критериями выхода, прогресс в README строит скрипт | `DPpass:docs/direction/` | `docs/direction/`, `tools/verify/readme_progress.py` |
| Таблица модулей как источник направлений зависимостей | `DPpass:docs/ARCHITECTURE.md`, §2 | `docs/ARCHITECTURE.md`, §2 |
| Уровни проверки static/affected/full, plan, report | `DP-Verify:README.md` | `tools/verify/`, D-16 |
| Ссылки `Проект:номер`, общие принципы E-01…E-13 | `DPmeta:docs/NAMING.md`, `DPmeta:docs/PRINCIPLES.md` | `CLAUDE.md`, D-18 |
| Хук начала сессии с ветками невлитой работы | `DPlang:.claude/hooks/session-start.sh` | `.claude/hooks/session-start.sh` |
