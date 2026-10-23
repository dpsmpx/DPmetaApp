# Среда Linux внутри приложения Android

Датированные факты для варианта B своего терминала (`TODO.md`, «Свой
терминал в приложении»): Alpine под proot, запущенный самим приложением,
без Termux. Решения здесь нет: оно — после пробы на устройстве (D-03
требует доказанной стабильности на целевых устройствах). Факт без даты и
источника сюда не пишется; изменилось — новая запись с новой датой.

## 1. Запуск исполняемых файлов при targetSdk 29+

По источникам, **18 октября 2026**:

- С targetSdk 29 политика SELinux запрещает приложению `execve()` файлов
  из своего каталога данных (`/data/data/<пакет>`): это нарушение W^X.
  Исключение оставлено только доменам `untrusted_app_25` и `_27`, то есть
  приложениям с targetSdk до 28 (коммит `0dd738d8` AOSP `system/sepolicy`,
  «Enforce execve() restrictions for API > 28»; пересказ — вики Termux
  «Termux execution environment», правка 30 октября 2024).
- Тот же коммит, по пересказу, не убирает загрузку кода другими путями:
  `dlopen()` и `mmap(PROT_EXEC)` файла из каталога данных работают. На
  этом держится обход termux-exec: вместо `execve(elf)` — `execve
  /system/bin/linker64 elf`, загрузчик системы отображает файл сам.
- Файлы из каталога нативных библиотек приложения (`nativeLibraryDir`)
  запускать можно: исполняемый файл кладётся в APK как `lib<имя>.so`, а
  распаковку библиотек включает `android:extractNativeLibs="true"` (в
  Gradle — `packaging.jniLibs.useLegacyPackaging = true`). Так сделал
  Briar для Tor при переходе на targetSdk 29 (merge request 1279).
- Termux держит targetSdk 28 и сам называет это временной мерой; его
  сборка для Google Play — экспериментальная, «с обширными
  переделками под требования политики», и объявлена устаревшей (README
  `termux/termux-app`).
- Android 14 (targetSdk 34+) требует, чтобы динамически загружаемые файлы
  были только для чтения. Официальная страница изменений говорит о DEX,
  JAR и APK через загрузчики классов; про `System.load()` и запуск
  процессов — ничего.

Источники: [вики Termux](https://github.com/termux/termux-packages/wiki/Termux-execution-environment),
[Briar MR 1279](https://code.briarproject.org/briar/briar/-/merge_requests/1279),
[README Termux](https://github.com/termux/termux-app),
[изменения Android 14](https://developer.android.com/about/versions/14/behavior-changes-14).

## 2. proot из Alpine

Проверено **18 октября 2026** в облачной сессии, без устройства: пакет
`proot-static` 5.4.0-r2 Alpine 3.24, `community/aarch64`, лицензия
GPL-2.0-or-later.

- `usr/bin/proot.static` — `static-pie`, без интерпретатора (`PT_INTERP`
  нет): ядро Android запустит его без загрузчика musl. В одном файле
  402 600 байт; со смещения 268 640 вшит второй ELF — загрузчик proot
  (точка входа `0x2000000000`).
- В строках файла — переменные `PROOT_LOADER`, `PROOT_TMP_DIR`,
  `PROOT_NO_SECCOMP` и ключ `--link2symlink`. Без `PROOT_LOADER` proot
  пишет вшитый загрузчик во временный каталог и запускает его оттуда
  (строки «can't write the loader», «can't change loader permissions
  (u+rx)», «Please set PROOT_TMP_DIR…»), а это запрет §1. Значит, загрузчик нужен отдельным файлом
  `lib…so` в `nativeLibraryDir`, и путь к нему — в `PROOT_LOADER`.
  Гостевые программы загрузчик отображает через `mmap`, а не запускает
  `execve`; по §1 это разрешено.
- Сегменты `LOAD` у `proot.static` и загрузчика выровнены на 64 КиБ.

## 3. Alpine и пакеты

Проверено **18 октября 2026**, `dl-cdn.alpinelinux.org`, Alpine 3.24,
`aarch64`:

- `alpine-minirootfs-3.24.2-aarch64.tar.gz` — 4 028 030 байт, выпуск
  17 сентября 2026, SHA-256
  `9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773`
  (совпала после загрузки); распакованный — 8,9 МиБ. У `busybox` и
  `ld-musl-aarch64.so.1` сегменты выровнены на 64 КиБ.
- Пакеты из `APKINDEX` (размер — после установки):

| Пакет | Версия | Лицензия | Размер |
|---|---|---|---|
| `dropbear` | 2026.91-r0 | MIT | 0,5 МБ |
| `openssh-server` | 10.3_p1-r1 | SSH-OpenSSH | 2,0 МБ |
| `openjdk17-jre-headless` | 17.0.20_p8-r0 | GPL-2.0 с исключением classpath | 180 МБ |
| `openjdk21-jre-headless` | 21.0.12_p8-r0 | GPL-2.0 с исключением classpath | 196 МБ |
| `git` | 2.54.0-r0 | GPL-2.0-only | 7,3 МБ |
| `talloc` | 2.4.4-r1 | LGPL-3.0-or-later | 0,07 МБ |

## 4. Claude Code для musl

Проверено **18 октября 2026**: пакет npm
`@anthropic-ai/claude-code-linux-arm64-musl` 2.1.302 — архив 127 МБ,
распакованный — 278 МБ. Двоичный файл `claude` — ELF aarch64 с
интерпретатором `/lib/ld-musl-aarch64.so.1` и единственной зависимостью
`libc.musl-aarch64.so.1`; сегменты выровнены на 64 КиБ. Что эта сборка
работает в Alpine под proot на телефоне, проверено раньше
(`docs/research/CLAUDE_CODE_CLI.md`, §9, §10, 2.1.292 через
`proot-distro` Termux).

## 5. Процессы приложения в фоне

По источникам, **18 октября 2026** (документы agnostic-apollo,
сопровождающего Termux, «Phantom, Cached And Empty Processes»):

- С Android 12 система следит за дочерними процессами приложений
  («фантомными»). Их предел — 32 на все приложения вместе, а не на одно.
  Сверх предела убиваются процессы приложений с худшим `oom_adj`. Кроме
  того, процесс, который много ест процессор, пока его приложение в фоне,
  убивается; проверка — раз в 5 мин.
- Отключить: на Android 12L и 13 — `adb shell "settings put global
  settings_enable_monitor_phantom_procs false"`; на Android 14+ —
  «Параметры разработчика» → «Disable child process restrictions»
  (возвращается, если выключить параметры разработчика).
- Явного исключения для приложения со службой переднего плана нет. Служба
  лишь улучшает `oom_adj` родителя, и его потомки убиваются позже.

Следствие для B: Claude Code, Node, Git и оболочки внутри proot — это
фантомные процессы приложения. Длинный ход с подпроцессами может быть
убит без предупреждения, если не отключить ограничение.

Источник: [Android-Docs, phantom processes](https://github.com/agnostic-apollo/Android-Docs/blob/master/en/docs/apps/processes/phantom-cached-and-empty-processes.md).

## 6. Google Play

По страницам справки Google Play, **18 октября 2026**:

- С 31 августа 2026 новые приложения и обновления должны целиться в
  Android 16 (API 36); отсрочка — до 1 ноября 2026. Путь «targetSdk 28,
  как Termux», закрыт и для Play, и решением D-34 (targetSdk 36).
- Политика «Device and Network Abuse»: приложение «не может загружать
  исполняемый код (dex, JAR, .so) не из Google Play». Исключение — код в
  виртуальной машине или интерпретаторе с косвенным доступом к API
  Android. Скачивание Alpine, его пакетов и Claude Code при работе, по
  букве правила, под него подпадает. Для сборок не через Play (GitHub,
  установка вручную) правило не действует.

Источники: [целевой API](https://support.google.com/googleplay/android-developer/answer/11926878),
[Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16559646).

## 7. ART и модуль `execution`

Проверено **18 октября 2026** по `android.jar` SDK 30 и 37.0: класса
`java.lang.ProcessHandle` нет. На нём стоит остановка дерева процессов
(`ProcessTree`) агента `dpai`. Значит, агент в процессе приложения без
переделки остановки не заработает; агент внутри Alpine на OpenJDK —
заработает как есть.

## 8. Не проверено — на устройстве

П. 1 проверен, п. 2, 3, 5 — частично (§9, 23 октября 2026).

1. `proot.static` и загрузчик как `lib…so` из `nativeLibraryDir` при
   targetSdk 36: разрешён ли `ptrace` своих потомков и `mmap(PROT_EXEC)`
   гостевых файлов из каталога данных; нужен ли `PROOT_NO_SECCOMP`.
2. Жёсткие ссылки в каталоге данных: `apk add` и `git` без
   `--link2symlink` или с ним.
3. Скорость под proot: JVM агента, `git status` на настоящем дереве, ход
   Claude Code — против того же в `proot-distro` Termux (§10
   `CLAUDE_CODE_CLI.md`: 38,7 с на тест с двумя ходами).
4. Ограничение фантомных процессов на HyperOS (Redmi Note 13): есть ли
   переключатель из §5 и убивается ли ход в фоне без него.
5. Устройство со страницами 16 КБ: выравнивание у всех найденных файлов —
   64 КиБ, но сам proot на таком ядре не проверен.

## 9. Проба на Redmi Note 13

Проверено **23 октября 2026** пробником B2 (модуль `probe`, D-66, сборка
versionCode 1) на телефоне автора: Xiaomi 23124RA7EO (Redmi Note 13),
Android 15 (SDK 35), ядро `5.15.194-android13`, страница памяти 4096
байт; источник — отчёт пробника, присланный автором.

- **Запуск (§8, п. 1).** `libproot.so` — `proot.static` 5.4.0 Alpine —
  из `nativeLibraryDir` при targetSdk 36 запускается: `--version` — код
  0 за 116 мс. Оболочка Alpine 3.24.3 под proot (`-0`, загрузчик —
  `libproot-loader.so` через `PROOT_LOADER`) работает: `uname`,
  `cat /etc/alpine-release`, `id -u` = 0, 105 мс. С `PROOT_NO_SECCOMP=1`
  — код 255 без вывода: режим без seccomp здесь не работает, с seccomp —
  работает. Распаковка minirootfs в каталог данных — 296 мс.
- **Жёсткие ссылки (§8, п. 2).** `ln` в каталоге данных — «Permission
  denied». `--link2symlink` proot 5.4.0 — «No such file or directory».
  Причина — по исходникам proot v5.4.0 (`src/extension/link2symlink/link2symlink.c`,
  `src/syscall/sysnums-arm64.h`, прочитано 23 октября 2026): расширение
  подменяет `linkat` вызовом `symlink`, а в таблице arm64 у proot его нет
  (только `symlinkat`, №36) — вызов заменяется пустым, ссылка не
  создаётся. В форке Termux (`termux/proot`, ветка `master`, прочитано
  23 октября 2026) расширение делает ссылку само и гасит вызов с
  результатом 0 — на arm64 это работает.
- **Нужны ли жёсткие ссылки.** Пакет `git` 2.54.0-r0 (Alpine 3.24,
  `main/aarch64`, проверено 23 октября 2026) жёстких ссылок не содержит:
  149 символьных, 45 файлов. Git при отказе `link()` переходит на
  `rename()` (`finalize_object_file`, `object-file.c` v2.54.0).
- **Скорость (§8, п. 3).** Цикл 100 000 итераций: `sh` Alpine (busybox)
  под proot — 406 мс, `/system/bin/sh` (mksh) без proot — 804 мс (разные
  оболочки, сравнение — не proot против голого ядра). Запуск proot с
  `/bin/true` — 102–115 мс, в среднем 103 мс. `find / -xdev` (518
  записей) — 102 мс.
- **Сеть.** `apk update` и `apk add` под proot (DNS в `resolv.conf` —
  1.1.1.1 и 8.8.8.8) не достали индекс: «Permission denied», «Operation
  not permitted», «DNS: transient error». Причина не установлена:
  пробник 2 разбирает её по шагам (сеть приложения, группа `inet`, DNS
  сети телефона, HTTP и HTTPS из Alpine).
- **Страницы (§8, п. 5).** 4096 байт — ядро с 16 КБ этим устройством не
  проверить. Фон (§8, п. 4) в этом отчёте не проверялся.
