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
| `kotlin` (Kotlin Gradle Plugin, плагин компилятора Compose, stdlib, kotlin-test) | 2.4.20 | Apache-2.0 | stdlib — во время выполнения; остальное — сборка и тесты | Maven Central, `org.jetbrains.kotlin` | 6 октября 2026 |
| `junit` (JUnit BOM, Jupiter, Platform Launcher) | 6.1.3 | EPL-2.0 | только тесты | Maven Central, `org.junit` | 6 октября 2026 |
| `agp` (Android Gradle Plugin) | 9.4.1 | Apache-2.0 | только сборка: classpath `build-logic` (D-102), модули Android | Google Maven, `com.android.tools.build` | 7 октября 2026 |
| `compose-bom` (Compose BOM: Material 3, UI, Foundation, Runtime) | 2026.09.00 | Apache-2.0 | приложение Android во время выполнения | Google Maven, `androidx.compose` | 7 октября 2026 |
| `activity-compose` (AndroidX Activity Compose) | 1.13.0 | Apache-2.0 | приложение Android во время выполнения | Google Maven, `androidx.activity` | 7 октября 2026 |
| `androidx-core` (AndroidX Core: `FileProvider`) | 1.18.0 | Apache-2.0 | пульт DPmetaApp (`:app`) во время выполнения — «Поделиться» экспортом (`DPmetaApp:D-67`); та же версия прежде приходила транзитивно от `activity-compose` | Google Maven, `androidx.core` | 4 ноября 2026 |
| `jgit` (Eclipse JGit; с ним JavaEWAH 1.1.13 — Apache-2.0, SLF4J API 1.7.30 — MIT) | 5.13.3.202401111512-r | EDL-1.0 (BSD-3-Clause) | пульт DPmetaApp (`:core:git` → приложение) во время выполнения — клоны репозиториев (`DPmetaApp:D-65`); ветка 5.13 — последняя для Java 8, на Android без недостающих API JDK 11+ | Maven Central, `org.eclipse.jgit` | 4 ноября 2026 |
| `jsch` (JSch, форк mwiede) | 2.28.7 | BSD-3-Clause, ISC | модуль `jsch` → приложение Android во время выполнения | Maven Central, `com.github.mwiede` | 7 октября 2026 |
<!-- provenance: end -->

Версии совпадают с теми, что закреплены в `DPpass:gradle/libs.versions.toml`
на 6 октября 2026: одна связка Kotlin и JUnit на экосистему проще в
сопровождении.

Зависимости модуля `android` (D-34) — последние стабильные на 7 октября
2026; ядро от них не зависит. Compose BOM 2026.09.00 требует compileSdk 37
(платформа `android-37.0`); targetSdk остаётся 36.

Инструменты Android SDK для нативного кода модуля `android` (D-75, шаг 3;
D-17 — только в нём): NDK 28.2.13676358 и CMake 3.22.1 — версии, которые
AGP 9.4.1 берёт по умолчанию; Apache-2.0 и другие свободные лицензии
инструментов, в APK попадает только собранный `libdpai-native.so` из
своего кода (`android/src/main/cpp/`). Ставятся через `sdkmanager
"ndk;28.2.13676358" "cmake;3.22.1"` (23 октября 2026).

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

Загружаемые сборкой приложения для Linux-среды (D-75, D-82; прежде —
пробника B2, D-80) — в репозитории их нет: задача `linuxFiles` в
`android/build.gradle.kts` загружает файлы в `~/.gradle/caches/dpai-linux`
и сверяет по суммам ниже, иначе сборка падает. В APK — без изменений
(`docs/EXECUTION_MODEL.md`, §8); текст GPL-2.0 — `android/linux/GPL-2.0.txt`,
ссылки на исходники — `android/linux/NOTICE.txt`.

| Файл | SHA-256 | Лицензия | Откуда и как проверено |
|---|---|---|---|
| `alpine-minirootfs-3.24.3-aarch64.tar.gz` | `4d3f80afaebd447c5591a63a73bde380a149fe8b2d85488cafabb2ff97551f50` | пакеты Alpine (busybox GPL-2.0-only, musl MIT, apk-tools GPL-2.0-only и др.) | `dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64`; сумма совпала с `latest-releases.yaml` и `.sha256` выпуска (подписи GPG у minirootfs нет), 23 октября 2026 |
| `proot-static-5.4.0-r2.apk` | `31d0e21ee87435ed819112cef84b8ce7d004c2835d8f61eeabe422e122b4b6cf` | GPL-2.0-or-later | `…/v3.24/community/aarch64`; подпись `APKINDEX` и пакета — ключом Alpine `616ae350` из `/etc/apk/keys` minirootfs, контрольная сумма пакета (`C:`) — как в индексе, `datahash` — как у данных, 23 октября 2026 |
| `proot.static` из пакета → `libproot.so` | `05e07c2dca260a720b3c191bb1c85c80126bb28991ba9060115bf19c8ba0a9a0` | GPL-2.0-or-later | `usr/bin/proot.static` пакета выше |
| загрузчик proot → `libproot-loader.so` | `2ce2923d28939c1166e6e7b0676a4fcc3eb741d6fe1feb3e3dcd15819d58eef8` | GPL-2.0-or-later | второй ELF в `proot.static` (смещение 268 640, 131 896 байт), без изменений |

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
| Хук начала сессии с ветками невлитой работы | `DPlang:.claude/hooks/session-start.sh` | `DPmetaApp:.claude/hooks/session-start.sh` (D-55) |
| Подпись APK ключом автора на телефоне: пароли из `~/.gradle/gradle.properties` только в окружение, сверка сертификата | `~/dppass-build/build.sh` автора (вне репозитория, `DPmetaApp:D-31`), `DPpass:tools/release/sign_apks.py` | `tools/termux/resign-apk.sh` |

## 6. Идеи из внешних проектов

Открытые проекты вне экосистемы автора. Берётся идея, код и тексты
пишутся здесь заново; дата — когда проект прочитан.

| Идея | Откуда | Лицензия, дата | Где здесь |
|---|---|---|---|
| Роли ревью (код, безопасность) отдельно от исполнителя; оценка попыток pass@k | `https://github.com/affaan-m/everything-claude-code` (`agents/code-reviewer.md`, `agents/security-reviewer.md`, `skills/eval-harness/SKILL.md`); прочитан форк `WorldFlowAI/everything-claude-code`, коммит `432485b` | MIT (файл `LICENSE` — в исходном проекте, у форка его нет), 22 октября 2026 | D-73 |
