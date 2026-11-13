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
| `jgit` (Eclipse JGit; с ним JavaEWAH 1.1.13 — Apache-2.0, SLF4J API 1.7.30 — MIT) | 5.13.5.202508271544-r | EDL-1.0 (BSD-3-Clause) | пульт DPmetaApp (`:core:git` → приложение) во время выполнения — клоны репозиториев (`DPmetaApp:D-65`); ветка 5.13 — последняя для Java 8, на Android без недостающих API JDK 11+ | Maven Central, `org.eclipse.jgit` | 4 ноября 2026 |
| `jsch` (JSch, форк mwiede) | 2.28.7 | BSD-3-Clause, ISC | модуль `jsch` → приложение Android во время выполнения | Maven Central, `com.github.mwiede` | 7 октября 2026 |
| `sora` (Sora Editor и language-textmate) | 0.24.7 | LGPL-2.1-or-later; tm4e — EPL-2.0, Joni/JCodings — MIT, Gson — Apache-2.0, SnakeYAML Engine — Apache-2.0 | только Android: нативное поле и локальная подсветка (D-136); исходные грамматики проекта, без внешнего кода | Maven Central, `io.github.rosemoe`; исходники Rosemoe/sora-editor | 12 ноября 2026 |
| `apksig` (Android apksig) | 9.4.1 | Apache-2.0 | только Android во время выполнения: подпись APK ключом автора из Android Keystore и проверка подписи (D-157); та же версия уже приходит с `agp` в сборку и проверена в `verification-metadata.xml` | Google Maven, `com.android.tools.build` | 13 ноября 2026 |
| `robolectric` (Robolectric) | 4.17 | MIT; транзитивные — как у DPpass (`DPpass:docs/PROVENANCE.md`) | только тесты `:shell` корня DPmetaApp и только по свойству `dpmeta.screenshots` — снимки экранов на JVM (`DPmetaApp:docs/SCREENSHOTS.md`); в APK и обычную сборку не попадает | Maven Central, `org.robolectric` | 13 ноября 2026 |
| `junit4` (JUnit 4) | 4.13.2 | EPL-1.0 | там же: API запускателя Robolectric; запуск — `junit-vintage-engine` из JUnit BOM | Maven Central, `junit` | 13 ноября 2026 |
| `robolectric-android-all` (образ «Android целиком», SDK 36) | 16-robolectric-13921718-i7 | Apache-2.0 | там же: образ, который ждёт Robolectric 4.17; берётся Gradle со сверкой суммы в `verification-metadata.xml` | Maven Central, `org.robolectric` | 13 ноября 2026 |
<!-- provenance: end -->

Предпросмотр на ПК (D-169) кладёт в домашний каталог ПК init-скрипт, который
на один запуск добавляет тестам модуля проекта Robolectric 4.17 и JUnit 4.13.2
(версии закреплены в ресурсе `ui/src/main/resources/com/dpsmpx/dpai/ui/preview/dpai-preview.init.gradle.txt`).
Скачивает их Gradle на ПК из репозиториев самого проекта; суммы сверяет только
`verification-metadata.xml` проекта, если он есть, — приложение их не проверяет
(угроза T27). В APK DPmetaApp они не попадают.

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

Загружаемые сборкой приложения для Linux-среды (D-75, D-115; прежде —
пробника B2, D-80) — в репозитории их нет: задача `linuxFiles` в
`android/build.gradle.kts` загружает файлы в `~/.gradle/caches/dpai-linux`
и сверяет по суммам ниже, иначе сборка падает (`docs/EXECUTION_MODEL.md`,
§8). minirootfs — в APK без изменений; proot — собран из исходников форка
Termux скриптом `tools/linux/build-proot.sh` (D-115): код proot не изменён,
к каждой единице подключён `tools/linux/proot-musl-compat.h`. Текст GPL-2.0 —
`android/linux/GPL-2.0.txt`, ссылки на исходники и лицензии вшитого —
`android/linux/NOTICE.txt`.

| Файл | SHA-256 | Лицензия | Откуда и как проверено |
|---|---|---|---|
| `alpine-minirootfs-3.24.3-aarch64.tar.gz` | `4d3f80afaebd447c5591a63a73bde380a149fe8b2d85488cafabb2ff97551f50` | пакеты Alpine (busybox GPL-2.0-only, musl MIT, apk-tools GPL-2.0-only и др.) | `dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64`; сумма совпала с `latest-releases.yaml` и `.sha256` выпуска (подписи GPG у minirootfs нет), 23 октября 2026 |
| исходники proot: `github.com/termux/proot`, коммит `52d65a4dc140ca554d241a1438ddd4f6b00c9813` (тег `v5.1.107.102`) | — (закрепляет коммит git) | GPL-2.0-or-later | версия, которую выпускает пакет proot Termux (`termux-packages:packages/proot/build.sh`), 9 ноября 2026; загружается `git fetch` по коммиту, правленое дерево — отказ сборки |

Sysroot сборки proot — пакеты Alpine 3.24 `main` (в APK из них — только то,
что статически вшито в proot: `libc.a` musl и `libtalloc.a`). Суммы — по
загрузке `apk fetch` 9 ноября 2026: подпись `APKINDEX` ключом Alpine своей
архитектуры и контрольную сумму пакета в индексе проверил apk.

| Пакет | SHA-256 aarch64 (APK) | SHA-256 x86_64 (проверки на хосте) | Лицензия |
|---|---|---|---|
| `musl-dev-1.2.6-r3` | `45afe0a9f78de3402b6b5d11f5153ed955bf59d59b45344d9c3fed9f4bbf2f66` | `310b51cff12e1d5ce5f135c558a82e2550427da5465bbd16c9ff9b05e63ee3d9` | MIT |
| `linux-headers-7.0.0-r1` | `d61656e81116040ebac0bc8d9046a3ba97f01a00ee085809103576e8a437468f` | `c4535f3f0dc6fd4d80efa7ff2a7a72a2d3f8dae31e2587c0785cc3243889cdf2` | GPL-2.0-only (заголовки — только для сборки) |
| `talloc-dev-2.4.4-r1` | `e1127751ace6a05ee40cde15e5001fc035a89feccf6a32338bb2d4642c182438` | `d499f12946434f96802b8a9b155b589b4fb9535aae1d83f342eb29536d38459e` | LGPL-3.0-or-later |
| `talloc-static-2.4.4-r1` | `c5b29fe708de96cbe045764d1bcabb3a934dc04fa4f5992ed2d479c39f794c38` | `12c35cb6f63d97f2bdd773cfd4290e39e6fbbd13026c485c6ab0497864927cd9` | LGPL-3.0-or-later |
| `bsd-compat-headers-0.7.2-r6` | `16b7f8b09b067649dfcfb25e366ffcf781fffb9a60e96fc14001f88035b10389` | `185af20c670894721f8b5bc9be1294bd42cc698b4ab6b782880ff5cedbf98b98` | BSD-2-Clause AND BSD-3-Clause (`sys/queue.h` — только для сборки) |

Компилятор — clang из NDK (`android/build.gradle.kts`, `ndkVersion`); его
`libclang_rt.builtins` вшиты в proot (Apache-2.0 WITH LLVM-exception).
Сборка воспроизводима: пути машины сборки в итог не попадают
(`-ffile-prefix-map`), и две сборки из разных каталогов совпали байт в байт.
Итог с NDK 28.2.13676358 на Linux x86_64, 9 ноября 2026: `libproot.so` —
`c046291a578e5f0a8b6af66b13fd2b848364f15a9bba4be9ef67288a23f05195`,
`libproot-loader.so` — `54cf685e738b8c362badf98a4c01fb67c0a7b292de5d0dad91735fced80abd50`
(сверка, а не замок сборки: другой clang даёт другие байты).

Для проверок на машине сборки — те же выпуски для x86_64; в APK и в
репозиторий не идут: `tools/linux/host-env.sh` загружает их в каталог
среды, сверяет по суммам ниже и собирает proot для x86_64 тем же скриптом
(`docs/TESTING.md`, «Слои»). Суммы — по загрузке по HTTPS 9 ноября 2026,
цепочка подписей Alpine для minirootfs не сверялась: среда — только для тестов.
Списки фильтра seccomp приложений Android (Apache-2.0, AOSP) читает
`tools/linux/android-seccomp.py` — имитация фильтра телефона для проверок.

| Файл | SHA-256 | Лицензия | Откуда |
|---|---|---|---|
| `alpine-minirootfs-3.24.3-x86_64.tar.gz` | `f8e1cafba9b1b283b5d30d06f9885c8d2bc161ffe9083ade8e2e157813a53407` | как у aarch64 выше | `…/v3.24/releases/x86_64` |
| `SYSCALLS.TXT` | `c3c72838cf1d4e8832941fe50ff520eae5d34829e20d2fbf5d3368bdd3209216` | Apache-2.0 | `android.googlesource.com/platform/bionic`, `libc/`, коммит `361ba86734fb2821a6adcfdf775db8abd04e0de0` (ветка `android15-release`) |
| `SECCOMP_ALLOWLIST_COMMON.TXT` | `e7a43bd655a012562770d84891e1f2671321f19a1395eb2df924a6f9262e234c` | Apache-2.0 | там же |
| `SECCOMP_ALLOWLIST_APP.TXT` | `399da338f268dd52d9fe5ad66a023317158f28b4889d7d110c3c54f3a3d44f43` | Apache-2.0 | там же |
| `SECCOMP_BLOCKLIST_COMMON.TXT` | `41861b21a4471444a9987386726f93bac546921dd1b3ec912f20cdc53b2ac761` | Apache-2.0 | там же |
| `SECCOMP_BLOCKLIST_APP.TXT` | `e263498e629301339a121703960458bf305ec6e559907bf6ae12d4b56c275d96` | Apache-2.0 | там же |
| `SECCOMP_PRIORITY.TXT` | `69719591740fe41a2d9662579d93a7874364fea9d341f348d0cba71ade5411da` | Apache-2.0 | там же |

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

## Закреплённые npm-установщики (аудит A-05, 11 ноября 2026)

Кнопка установки использует точные версии из публичного npm registry;
существующие установки автоматически не обновляются. Лицензии: Codex —
Apache-2.0, Gemini CLI — Apache-2.0, Claude Code — собственная лицензия
Anthropic. Метаданные `dist.integrity` (SHA-512) опубликованных пакетов:

| Пакет | Версия | dist.integrity |
|---|---|---|
| @openai/codex | 0.167.1 | sha512-39uApTRT9bkhE8tMXTIfZxaZ27oi7hPqWoF2Coyp+iQFAD3NVHgH838IYVUfJaX85leMSP2s4JOmVw0mMSCN5Q== |
| @google/gemini-cli | 0.68.0 | sha512-eq7AWY+YCijNGKVwyiRjN+GuOdXU/djuOZhX18p+HDmNyUqFdqH7C5V6vDJLl1uffhRPhGijePV+YvY8lymrbQ== |
| @anthropic-ai/claude-code | 2.1.322 | sha512-oP7oW4vM5DREWfL1jn4JSGlmLcrJ5QmppgADrVNJBUPiC1mQ5YvRrke0on+YZ/3QhKIaCJtdr6jPvehcAQIHLQ== |

Источники: https://registry.npmjs.org/@openai/codex/0.167.1 ,
https://registry.npmjs.org/@google/gemini-cli/0.68.0 ,
https://registry.npmjs.org/@anthropic-ai/claude-code/2.1.322 .
Это закрепление пакетов верхнего уровня, не полный lock всех транзитивных
зависимостей npm. Скрипты установки не отключены: Claude устанавливает
нативный исполнитель через install.cjs. Проверка APK на телефоне требуется.

## Поле редактора Sora

12 ноября 2026: выбран стабильный 0.24.7 (Maven metadata, обновление
17 октября 2026). Исходники изучены по sources.jar того же выпуска;
лицензия LGPL-2.1-or-later, уведомление и текст лицензии включаются в APK.
Нативное поле подключается как библиотека, исходники Sora не изменены.
Грамматики/темы в android/src/main/assets/editor написаны в этом проекте.

| Артефакт | SHA-256 |
|---|---|
| editor-0.24.7.aar | cdfdacdb3ac8605b190768d93f504935400c9aa37b773dc6f6c840311faf2b4c |
| language-textmate-0.24.7.aar | eca50e81595cd4cf1f677cca12c5fa2203d6051eaf87dabc7e7489413dd098f1 |
