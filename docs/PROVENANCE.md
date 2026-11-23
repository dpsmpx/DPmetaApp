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

Модули `:core:*` (кроме `:core:git` — JGit и `:core:model` — RE2/J, ниже) и `:tests:corpus` сторонних зависимостей не имеют —
только ядро `dpai/` (`core`, `storage`) и JDK (`java.net.http` — транспорт
тестов на JVM; TLS — системными корнями).
Каждая новая зависимость — строка ниже и запись в каталоге версий;
кандидаты и их лицензии — ТЗ, §6.1.
Зависимости подсистемы «AI» и ядра (Kotlin, JUnit, Android Gradle Plugin,
Compose, JSch; wrapper Gradle со сверкой SHA-256) — `dpai/docs/PROVENANCE.md`
и `dpai/gradle/libs.versions.toml` (D-28).

| Компонент | Версия | Лицензия | Зачем | Модуль | Решение |
|---|---|---|---|---|---|
| RE2/J (`com.google.re2j:re2j`, каталог `re2j`) | 1.8 | BSD-3-Clause (Go License) | линейный поиск по пользовательским выражениям правил, без backtracking; без дополнительных runtime-зависимостей | `:core:model` | D-141 |
| Eclipse JGit (`org.eclipse.jgit:org.eclipse.jgit`, каталог `jgit`; с ним JavaEWAH 1.1.13 — Apache-2.0, SLF4J API 1.7.30 — MIT) | 5.13.5.202508271544-r | EDL-1.0 (BSD-3-Clause) | клоны репозиториев: клонирование, обновление, ветки и файлы (ветка 5.13 — последняя для Java 8, на Android без недостающих API JDK 11+; CVE-2023-4759 закрыта) | `:core:git` | D-65 |
| AndroidX Core (`androidx.core:core`, каталог `androidx-core`) | 1.18.0 | Apache-2.0 | `FileProvider` — «Поделиться» экспортом истории (F-36) | `:app` | D-67 |
| Robolectric (`org.robolectric:robolectric`, каталог `robolectric`; с ним JUnit 4 `junit:junit` 4.13.2 — EPL-1.0 и `junit-vintage-engine` из JUnit BOM — EPL-2.0) | 4.17 | MIT (транзитивные — как у DPpass, `dppass/docs/PROVENANCE.md`) | снимки экранов на JVM (`docs/SCREENSHOTS.md`): только тестам `:shell` и только по свойству `dpmeta.screenshots`; в APK и обычную сборку не попадает | `:shell` (тест) | те же версии, что у тестов экранов DPpass (`DPpass:D-86`) |
| Образ «Android целиком» (`org.robolectric:android-all-instrumented`, каталог `robolectric-android-all`) | 16-robolectric-13921718-i7 | Apache-2.0 | образ SDK 36 для Robolectric 4.17; берётся Gradle со сверкой суммы, тест ничего не скачивает | `:shell` (тест) | `DPpass:D-86`; SHA-256 — `gradle/verification-metadata.xml` |

Образ Robolectric API 30 (`org.robolectric:android-all-instrumented`
11-robolectric-6757853-i7, каталог `robolectric-android30`, Apache-2.0) —
только тестам `:shell` при `dpmeta.test.sdk=30`. Версия сверена с
DefaultSdkProvider Robolectric 4.17; JAR/POM SHA-256 получены из Maven Central
и закреплены в `gradle/verification-metadata.xml`. В APK не включается.

Предпросмотр на ПК (`DP_AI_Orcestrator:D-169`) на один запуск добавляет тестам
модуля проекта на ПК Robolectric 4.17 и JUnit 4.13.2 — версии закреплены в
ресурсе init-скрипта (`dpai/docs/PROVENANCE.md`); скачивает их Gradle ПК, суммы
сверяет только `verification-metadata.xml` проекта, в APK DPmetaApp они не
попадают.

RE2/J проверен 14 ноября 2026: [официальный README](https://github.com/google/re2j/blob/re2j-1.8/README.md),
[лицензия](https://github.com/google/re2j/blob/re2j-1.8/LICENSE), Maven Central.
JAR SHA-256 `7b52c72156dd7f98b3237a5b35c1d34fba381b21048c89208913ad80a45dfbd7`,
POM SHA-256 `df62699335cd46fc465f4c03b8c581d3d507792212d27b65adb923b4c27de4e7`.
Обе суммы закреплены в Gradle; полный текст лицензии поставляется в
`app/src/main/assets/licenses/re2j-1.8.txt`. Сопоставление выполняется
без учёта регистра. RE2 не поддерживает весь синтаксис Java Regex:
неподдержанное отклоняется явно, без запасного небезопасного движка.

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
Python 3/venv/pip — пакеты установленной Alpine, лицензии и
зависимости сохраняет apk. Для общего Linux возможен Alpine JDK 21,
но мобильный Android Gradle теперь использует отдельный OpenJDK 21
под Android/Bionic (D-191), поскольку Alpine HotSpot не запускается
на целевом телефоне даже при успешном RWX `mprotect` Python.
Источник мобильного JDK: архив
`jdk21-android-arm64.tar.gz` из
https://github.com/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE/releases/tag/jdk21-android-21.0.14,
SHA-256 `8746f96a348c34b1e21508128478cb613de05f9d0aa9befad2967f83a9542680`.
На машине разработки SHA совпала с GitHub release digest; ELF
`aarch64`, interpreter `/system/bin/linker64`, Bionic/Android API 29,
RUNPATH относительно бинарника. Это сторонняя сборка OpenJDK:
проверка суммы подтверждает артефакт, не гарантирует его безопасность.
Архив не включён в APK; скачивается по выбору автора, результат запуска
проверяется в приложении. OpenJDK лицензируется GPL-2.0 с Classpath
Exception; правовой статус дополнительных изменений автора релиза
перед распространением бинарника отдельно требует проверки.
PyPI: https://pypi.org/simple/ ; известное
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
  30 дней. По D-136 и DP_AI_Orcestrator:D-206 закреплена 271.2085.0; новые версии — с обновлением
  приложения. SHA-256 целых архивов проверены потоковым скачиванием
  21 ноября 2026: aarch64 —
  `877aee612a17dab48144466282a6414ec58ab428a10b97c7e13edd9b6b3dc96e`,
  x86_64 — `e398e799027f41749ea33faa57470307d13d40f910961858c7e30adbd778ae08`.
  Суммы зашиты в APK; файл `.sha256` сервера при установке не читается.
  Запуск без встроенной
  glibc-JVM: JDK 25 Alpine, gcompat для наблюдения за файлами,
  `--data-sharing=none`. Ранее проверена сборка 263.7084.0: на Alpine x86_64
  (musl) в chroot и на хосте — импорт Gradle, диагностика, переходы,
  переименование. Сборка 271.2085.0 проверена 21 ноября на хосте с
  JRE25/JDK21: живой Gradle JVM, диагностика, дополнение, переходы и
  переименование. Android и телефон проверяются отдельно.
- rocksdbjni 10.10.1 (Apache-2.0 / GPL-2.0): общий архив Maven Central
  https://repo1.maven.org/maven2/org/rocksdb/rocksdbjni/10.10.1/rocksdbjni-10.10.1.jar ,
  SHA-256 `3e3c9e439b89c2ac4aaa7512bc73052d44b3a6397daed6cb88168cefa6e431a9`
  (совпал с `.sha256` Maven Central). Из него берётся только musl-библиотека
  нужной архитектуры: набор JNI-символов (1525) совпал с библиотекой
  сборок kotlin-lsp полностью, для x86_64 и aarch64; для 271.2085.0
  повторно сверены все 1525 символов 21 ноября 2026.
- Платформа Android SDK: архив из манифеста
  https://dl.google.com/android/repository/repository2-3.xml , сверка SHA-1
  манифеста, как у sdkmanager; ставится после принятия лицензии Google
  человеком, отметка — SHA-1 текста лицензии в `licenses/`.
