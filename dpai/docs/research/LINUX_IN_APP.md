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

Проверено **9 ноября 2026** на машине сборки (x86_64, облачная сессия
DPmetaApp): тот же proot-static 5.4.0 и Alpine 3.24.3, что в APK, но
x86_64 (`tools/linux/host-env.sh`). `npm install -g
@anthropic-ai/claude-code` — версия **2.1.319**: обёртка ставит пакет
платформы (`claude-code-linux-x64-musl`), и её `postinstall` кладёт его
бинарник в `bin/claude.exe` — сначала жёсткой ссылкой, при отказе ссылки
(`EPERM`, `EXDEV` или повторный отказ после удаления заглушки) — копией.
Бинарник — 326 МБ, зависимость одна — `libc.musl-x86_64.so.1`; на Android
жёсткая ссылка запрещена (§9), так что на телефоне ждать копии — около
0,7 ГБ вместе с пакетом. `claude --version` — 2.1.319; ход без входа в
пустом `CLAUDE_CONFIG_DIR` — поток `stream-json` с
`error: authentication_failed` и кодом выхода 1, запроса к модели нет
(образец `tests/data/claude-stream/auth-failed-alpine.jsonl`). Пакеты
`libgcc`, `libstdc++`, `ripgrep` для этой сборки не понадобились.

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

П. 1, 2 и 4 проверены, п. 3 — кроме хода Claude Code, п. 5 — не на этом
устройстве (§9, 23 октября 2026).

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

Проверено **23 октября 2026** пробником B2 (модуль `probe`, D-80, сборка
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
  not permitted», «DNS: transient error». Сеть ни при чём — второй
  прогон ниже.
- **Страницы (§8, п. 5).** 4096 байт — ядро с 16 КБ этим устройством не
  проверить.

Второй прогон — тот же день, пробник 2 (versionCode 2):

- **Сеть работает.** У процесса приложения и у гостя под proot одни
  группы, среди них `inet` (3003); DNS приложения — 227 мс, TCP до
  `dl-cdn.alpinelinux.org:443` — 2 мс. Из Alpine `nslookup` через DNS
  сети телефона (172.19.0.2), HTTP и HTTPS (`wget` busybox) — работают.
- **Отказ `apk` — жёсткая ссылка, а не сеть.** apk-tools 3.0.8 (в
  minirootfs 3.24.3) создаёт файл через `openat(O_TMPFILE)` и затем
  `linkat(AT_FDCWD, "/proc/self/fd/N", …)`; на временный файл с
  `renameat` он переходит, только если `/proc/self/fd` недоступен или
  `O_TMPFILE` не открылся (`src/io.c` v3.0.8, зеркало на GitHub,
  прочитано 23 октября 2026). «Permission denied» — запрет `link()`
  Android, «Operation not permitted» с `--link2symlink` — расширение
  proot 5.4.0. В форке Termux есть отдельная обработка `linkat` из
  `/proc/self/fd` (`handle_linkat_from_proc_fd`).
- **Фон (§8, п. 4).** Процесс под proot при службе переднего плана
  пробника жил 32 мин с выключенным экраном: 192 отметки раз в 10 с,
  наибольший разрыв — 11 с; настройки HyperOS для пробника не менялись.

Третий прогон — тот же день, пробник 3 (versionCode 3):

- **`apk` без `/proc` у гостя работает.** С `/proc` — как прежде:
  `apk update` — «Permission denied»; `apk add git` распаковал пакеты, но
  базу не записал («failed to write database: Permission denied» —
  состояние неполное). Без `/proc` (`-b /proc` не задан): `apk update` —
  код 0 за 15,7 с, 28 570 пакетов; `apk add git` — 12 пакетов, 21,2 МиБ,
  39,4 с; `apk add openjdk17-jre-headless` — 8 пакетов, всего 195,2 МиБ,
  357 с.
- **Git (§8, п. 2) работает с `/proc`:** `git init`, коммит и `gc` — 606
  мс; дерево из 2000 файлов с коммитом — 5,0 с; `git status` на нём —
  205 мс трижды, с запуском proot (~100 мс).
- **JVM (§8, п. 3):** OpenJDK 17.0.20 Alpine под proot — `java -version`
  за 130, 205 и 102 мс.
- **Не проверено:** ход Claude Code под proot приложения против
  `proot-distro` Termux (§8, п. 3) — нужен вход в Claude, у пробника его
  нет; ядро со страницами 16 КБ (§8, п. 5).

## 10. Фильтр seccomp Android: код 159 у Node, bash, python и Go

9 ноября 2026, Xiaomi 23124RA7EO (Android 15, API 35), сборка 0.4.0-dev.13:
«Поставить Claude Code» — «Установка завершилась с кодом 159» через ~15 с
после `apk add` (журнал приложения, присланный автором). 159 = 128 + 31:
процесс убит сигналом 31, SIGSYS.

- **Фильтр приложений.** Android ставит процессу приложения фильтр seccomp;
  вызов не из списка — `SECCOMP_RET_TRAP`, то есть SIGSYS. Список для
  arm64 — по файлам bionic ветки `android15-release` (коммит `361ba867`,
  `libc/SYSCALLS.TXT` и `SECCOMP_*.TXT`, прочитано 9 ноября 2026; правило
  сборки — `libc/tools/genseccomp.py`: вызовы libc и белые списки минус
  чёрные). Нет в нём, среди прочего: `io_uring_setup`, `io_uring_enter`,
  `io_uring_register`, `faccessat2`, `openat2`, `rseq`, `set_robust_list`,
  `futex_waitv`, `landlock_*`; запрещены `setuid`/`setgid`/`setgroups` и
  родственные, `chroot`, `mount`, `sethostname` и др.
- **Кто их зовёт** (`strace` в Alpine 3.24 x86_64 на машине сборки, 9 ноября
  2026). `node` 24.22.1 и `npm` 11.12.1 — `io_uring_setup` при каждом
  запуске: libuv 1.52.1, вшитый в Node, открывает кольцо io_uring для
  пакетной `epoll_ctl` всегда; `UV_USE_IO_URING=0` выключает только кольцо
  файловых операций, а пропуск io_uring целиком — только в сборке под
  Android (`#if defined(__ANDROID_API__)`, `src/unix/linux.c` v1.52.1).
  `bash` 5 в `test -x` и `python3` в `os.access(…, effective_ids=True)` —
  `faccessat2` (musl 1.2.6 пробует его первым при флаге `AT_EACCESS`).
  `gh` 2.97.0 (Go) — `faccessat2` в `exec.LookPath` перед запуском `git`.
  `claude` 2.1.319 (`--version`, `doctor`) и `git` — ничего запрещённого.
- **Воспроизведено на машине сборки.** Тот же фильтр (списки bionic выше,
  arm64 → x86_64: старые вызовы x86_64 разрешены, если разрешён их
  arm64-аналог) поверх proot 5.4.0 Alpine: `node`, `npm` — «Bad system
  call», код 159; `bash -c 'test -x /bin/sh'` и `python3` с `os.access` —
  159; `gh pr list` в репозитории — «SIGSYS: bad system call» (паника Go);
  `sh`, `git`, `claude --version` — код 0.
- **proot 5.4.0 и форк Termux.** proot 5.4.0 (`proot-me/proot`) SIGSYS
  трассируемого не обрабатывает — сигнал доходит до процесса. Форк Termux
  (`termux/proot`, коммит `52d65a4d`, тег `v5.1.107.102`, прочитано 9
  ноября 2026) перехватывает SIGSYS от чужого фильтра
  (`src/tracee/seccomp.c`, `handle_seccomp_event`): часть вызовов
  заменяет разрешёнными (`open` → `openat` и др.) или изображает
  (`setgroups`, `mount` и др.), остальным отвечает `-ENOSYS`. Собранный
  статически под musl (D-115), под тем же фильтром: `node`, `npm`, `bash
  test -x`, `python3`, `claude --version`, `gh` в репозитории (дошёл до
  сети), `apk`, фоновый процесс с `--kill-on-exit` — код 0; установка
  `npm install -g @anthropic-ai/claude-code` — 9 с, `claude -p` без входа —
  обычный отказ входа, код 1.
- **Прокладка `LD_PRELOAD` в госте** (проба, в продукт не вошла):
  обработчик SIGSYS с ответом `-ENOSYS` чинит программы с musl (node, bash,
  python), но не Go — среда Go ставит свой обработчик SIGSYS.
- **Claude Code и оболочка.** Без bash или zsh Claude Code команды не
  выполняет: строка «No suitable shell found» и проверка «valid bash/zsh
  path» в `claude.exe` 2.1.319; в minirootfs Alpine — только `sh` busybox.
  Поиск — встроенный ripgrep: `claude doctor` — «Search: OK (bundled)» в
  Alpine x86_64.

## 11. Жёсткие ссылки пакетов и «N errors» у `apk`

13 ноября 2026, журнал приложения автора (Xiaomi 23124RA7EO, Android 15,
сборка dev.35) и отчёт Claude Code в Linux приложения: «Библиотеки IDE» —
`apk` код 3, «failed to extract usr/bin/ar: Permission denied» у binutils
2.45.1-r1, то же у gcc 15.2.0-r5 и g++; дальше любая команда пакетов
(«Обновить всё», установка Claude Code, Python и Git для DP-Verify)
кончалась «3 errors; 777.0 MiB in 94 packages» и кодом 3.

- **Причина — жёсткие ссылки внутри пакетов.** Все отказавшие файлы
  binutils, gcc и g++ в Alpine — жёсткие ссылки (`usr/bin/ar` и
  `usr/<триплет>/bin/ar` и т. п.); Android запрещает `link()` в данных
  приложения (§9). Машина сборки, Alpine 3.24 x86_64 под proot форка
  Termux (D-115, `tools/linux/host-env.sh`), 13 ноября 2026: `apk add
  binutils` без `--link2symlink` оставляет на хосте 20 файлов с числом
  ссылок больше 1 — на телефоне каждая из них — «Permission denied».
- **`--link2symlink` форка Termux работает.** С ключом: `ln a b` в госте —
  код 0, гость видит два имени с числом ссылок 2, на хосте оба имени —
  символьные ссылки на скрытый `.l2s.a0001` рядом, жёстких ссылок нет;
  `apk add binutils` — код 0, ни одной жёсткой ссылки на хосте, `ar
  --version` и `ld --version` в госте работают. С `/proc` у гостя
  (терминал) `apk add make` с ключом — тоже код 0. Git с ключом: три
  коммита, `git gc`, `git fsck` — без ошибок (в `.git` — символьные ссылки
  `.l2s.*`). Цели ссылок — абсолютные пути хоста: перенос каталога внутри
  корневой системы их рвёт.
- **Отметка повреждения и её починка.** Пакет, распакованный не до конца,
  apk-tools 3 отмечает в `/lib/apk/db/installed` полем `f:` (`f` — файлы,
  `s` — скрипт, `x` — атрибуты). Проверено той же средой: строка `f:f` у
  binutils — следующая команда `apk add bash` (чужой пакет) печатает «1
  error; …» и кончается кодом 1; `apk fix --no-progress` (без имён — все
  отмеченные) с `--link2symlink` переустанавливает binutils, код 0,
  отметка снята.
- **Помощник входа Git из `GH_TOKEN`.** `/etc/gitconfig` с
  `[credential "https://github.com"]` и помощником-функцией оболочки,
  проверено `git credential fill` в госте: с `GH_TOKEN` — `username=
  x-access-token` и `password=<токен>`; без переменной — помощник молчит,
  Git спрашивает имя (без терминала — «terminal prompts disabled»); для
  другого хоста помощник не вызывается.
