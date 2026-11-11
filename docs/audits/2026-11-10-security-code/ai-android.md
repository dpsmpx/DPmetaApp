> Приложение к `docs/audits/2026-11-10-security-code.md` — полный отчёт прохода («AI»: Android-оболочка, ui и терминал) как он сдан при сведении аудита (11 ноября 2026); номера находок те же, что в основном документе. Серьёзность, перепроверка и порядок исправлений — в основном документе; при расхождении прав он.

# Аудит dpai/android, ui, terminal, android-app (src/main)

Статус: завершён (11 ноября 2026). Только чтение; в репозитории ничего не менялось. Единственный запуск кода — замер регулярных выражений Markdown на JVM в scratchpad (`rx/`).

## Сводка

| ID | Серьёзность | Суть | Где |
|---|---|---|---|
| N-14 | Средняя | Регулярки Markdown — O(n³)/переполнение стека на тексте модели, разбор в главном потоке: ANR чата | `dpai/ui/.../Markdown.kt:23,27,56`; `DpaiApp.kt:783`, `ModelsPane.kt:434` |
| N-01 | Средняя | ACCEPT («Слить», «Подтвердить», «Ответить») в окне поверх приложений — одно касание, без входа приложения; в уведомлении — только разблокировка | `RequestOverlay.kt:319–327`; `RequestPresenter.kt:130` |
| N-02 | Низкая | Нет защиты от tapjacking (`filterTouchesWhenObscured`) ни у одной кнопки решения | `RequestOverlay.kt:316–377`, `DpaiApp.kt:1187–1230` |
| N-03 | Низкая | `VISIBILITY_PRIVATE` прячет текст только при «скрывать содержимое»; при «показывать всё» — вопрос и задача на экране блокировки | `RequestPresenter.kt:111–112`, `Notifier.kt:383` |
| N-05 | Низкая | OSC 52: в окне 500 из 100 000 знаков, без длины/строк/невидимых; вопрос о вставке снимает режим 2004, включаемый выводом | `TerminalActivity.kt:279,672–677` |
| N-06 | Низкая | Поток OSC 52 плодит неограниченно окон | `TerminalActivity.kt:255–290` |
| N-07 | Низкая | `stopSelf()` до `startForeground()` после `startForegroundService` — падение процесса в гонке | `TerminalService.kt:27–31`, `ControllerService.kt:57–60` |
| N-10 | Низкая | Восстановление копии настроек читает файл без предела — OOM | `SettingsBackups.kt:76` |
| N-11 | Низкая | Файл падения — `e.message` без `Redactor`, виден на диске приложения | `CrashRecord.kt:25,37` |
| N-12 | Низкая | Такт после остановки службы снова выставляет ongoing-уведомление «работает» и берёт wakelock | `ControllerService.kt:137,174–179,234–240` |
| N-13 | Низкая (гипотеза) | Сборка `Controller` с применением облачной копии — в главном потоке (активность, служба, приёмник) | `Controller.kt:113`, `MainActivity.kt:81`, `RequestPresenter.kt:264` |
| N-04 | Инфо | Коллизии requestCode по `hashCode` (~2⁻³⁰) | `RequestPresenter.kt:138,142`, `Notifier.kt:411` |
| N-08 | Инфо | Ссылка OSC 8 / Markdown: видимый текст ≠ адрес, адрес в тосте/снэкбаре обрезается | `TerminalEmulator.kt:596`, `TerminalView.kt:631`, `ChatText.kt:22` |
| N-09 | Инфо | JNI `read`/`write` без проверки `len<0`/границ (сейчас недостижимо) | `dpai_native.c:236,251` |
Учтено как известное: `dpai/docs/SECURITY_MODEL.md` (T1–T21), `dpai/TODO.md`,
`dpai/docs/audits/2026-11-01-security.md` (A-1…A-12, §3 «проверено»).

## Находки

### N-01 — Средняя — авторизация: решающий ответ «да» (ACCEPT) из окна поверх приложений — одним касанием и мимо входа приложения
- `dpai/android/src/main/kotlin/com/dpsmpx/dpai/android/RequestOverlay.kt:319` — `val login = b.confirm && loginNeeded(context)`; `:325–327` — взвод (второе нажатие) только `if (b.confirm …)`.
- `b.confirm` = роль `DESTRUCTIVE` (`dpai/notifications/.../RequestAction.kt`, `confirm = o.role == OptionRole.DESTRUCTIVE`), а «Слить» / «Повторить слияние» (`Orchestrator.kt:1580`), «Подтвердить» (`CONFIRMATION`), «Ответить» (`TEXT_INPUT`) — роль `ACCEPT` (`Tone.ACTIVE`, `ui/Requests.kt:52`).
- Итог: при включённом входе DPmetaApp (`DpaiApplication.loginNeeded()`) окно поверх приложений применяет слияние ветки и прочие ACCEPT одним касанием, без PIN и без второго нажатия. D-91 считает ACCEPT на `task.merge`/`task.unverified`/`task.review`/`recovery.question` решающим; уведомление для ACCEPT требует разблокировки (`RequestPresenter.kt:130`), но окно поверх приложений видно только на разблокированном телефоне, а вход приложения (D-59/D-104) оно не спрашивает. Тот же обход у кнопки ACCEPT в уведомлении (разблокировка телефона есть, входа приложения нет).
- Сценарий: телефон разблокирован и в чужих руках (или у ребёнка), приложение закрыто PIN — в окне запроса «Слить» → слияние без PIN.
- Рекомендация: в окне — `login` считать и для `Tone.ACTIVE` на решающих назначениях (или для всех ACCEPT), отправлять их в `MainActivity.confirm`; в уведомлении при `loginNeeded()` — ACCEPT тоже через `confirm(...)`.
- Уверенность: высокая (код); по документам: D-104 сознательно ограничен ролью `DESTRUCTIVE` — это расхождение D-104 с D-91 («решающее» шире «опасного»), в `TODO.md` не записано.

### N-14 — Средняя — DoS (ANR): регулярные выражения Markdown — кубическое время на тексте модели, в главном потоке
- `dpai/ui/src/main/kotlin/com/dpsmpx/dpai/ui/Markdown.kt:23` — `HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")`: ленивое `.*?` перед `\s*#*\s*$` даёт O(n³) возвратов; применяется к каждой строке дважды (`:56` `matches`, `:57` `matchEntire`). `:27` — `RULE = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")` — повтор группы с обратной ссылкой рекурсивен в `java.util.regex`.
- Замер (JVM 21 на машине аудита, скрипт в scratchpad `rx/Rx.java`): строка `"# a" + " "×n + "#" + " "×n + "b"`: n=1000 → 1,8 с; n=2000 → 12,7 с; n=4000 → 104 с. `RULE` на `"- - - …"` длиной 8000 + пробелы + `x` → `StackOverflowError`.
- Где: `render` зовётся в композиции, в главном потоке — чат сессии (`dpai/android/.../DpaiApp.kt:783`, `remember(line.text) { Markdown.render(...) }`) и ответы «Моделей» (`ModelsPane.kt:434`). Текст — вывод Claude/модели (недоверенный, T3: prompt injection из репозитория может заставить вывести такую строку). Итог — ANR при открытии чата; текст в журнале — экран сессии зависает при каждом открытии.
- Рекомендация: заголовок — без регулярки (`trimEnd()`, затем срезать хвост `#` и пробелы); `RULE` — проверка по символам; предел длины строки для разбора (длиннее — как простой текст); `render` — вне главного потока (`produceState` + `Dispatchers.Default`). Добавить образцы в тесты `ui` (правило проекта «новый баг разбора — образец в корпусе»).
- Уверенность: высокая для JVM (замер); на ART `java.util.regex` реализован ICU — тоже возвратный движок, сложность того же порядка ожидаема, но не замерена (гипотеза); `StackOverflowError` на ART может проявиться иначе (ICU держит свой стек возвратов). В документах не упомянуто.

### N-02 — Низкая — tapjacking: нигде нет `filterTouchesWhenObscured` / проверки `FLAG_WINDOW_IS_(PARTIALLY_)OBSCURED`
- Поиск по `dpai/` и `shell/` — ни одного вхождения. Затронуты: кнопки окна поверх приложений (`RequestOverlay.kt:316–377`, ACCEPT — одно касание), диалог подтверждения опасного ответа (`DpaiApp.kt:1187–1230`), подтверждение OSC 52 и прочие.
- Сценарий: приложение с `SYSTEM_ALERT_WINDOW` кладёт полупрозрачное (≤ 0,8 непрозрачности — Android 12+ такие касания пропускает) окно-приманку над кнопкой «Слить»/«Подтвердить».
- Рекомендация: `setFilterTouchesWhenObscured(true)` у кнопок окна и корня `MainActivity`/`TerminalActivity` (в Compose — `Modifier.pointerInteropFilter`/проверка `MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED` для решающих кнопок).
- Уверенность: средняя (нужна вторая вредоносная программа с разрешением наложения); в документах не упомянуто.

### N-03 — Низкая — утечка на экран блокировки: `VISIBILITY_PRIVATE` не прячет текст при настройке телефона «показывать всё»
- `dpai/android/src/main/kotlin/com/dpsmpx/dpai/android/RequestPresenter.kt:111–112`, `:183–184`, `:211–212`; `Notifier.kt:383–384` — `.setVisibility(VISIBILITY_PRIVATE).setPublicVersion(...)` с комментарием «Вопрос Claude и название задачи — не для экрана блокировки (A-12)».
- Публичная версия показывается только если человек выбрал «Скрывать содержимое» (`LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS=0`); при «Показывать всё» (частый заводской вариант) экран блокировки показывает полный текст: название задачи, тело запроса, причину `task.waive` из `dpai`, итог ответа (`result`). `sending()` (`:223–232`) вовсе без видимости/публичной версии.
- Рекомендация: для запросов и итогов — `VISIBILITY_SECRET` либо публичная версия как единственное содержимое на заблокированном экране (проверять `KeyguardManager.isKeyguardLocked` нельзя — уведомление уже показано), либо сказать в `SECURITY_MODEL.md`/A-12, что защита действует только при «Скрывать содержимое».
- Уверенность: высокая по семантике Android; заявление A-12 («исправлено») — частично.

### N-04 — Инфо — коллизии requestCode `PendingIntent`
- `RequestPresenter.kt:138` (`notificationId(id) * 31 + index (+7)`), `:142` (`uri.hashCode() * 31 + index`), `Notifier.kt:411` (`link.hashCode()`), все с `FLAG_UPDATE_CURRENT`. Совпадение хешей двух запросов перепишет extras прежней кнопки (ответ уйдёт другому запросу; проверка `uri↔id` в приёмнике пройдёт, т.к. оба extras заменены). Вероятность ~2⁻³⁰ на пару — практически не бывает. Рекомендация: requestCode из счётчика или `FLAG_ONE_SHOT` не нужен; можно оставить. Уверенность: высокая, влияние ничтожно.

### N-05 — Низкая — подмена буфера обмена: окно OSC 52 показывает 500 знаков из до 100 000 без длины и числа строк
- `dpai/android/src/main/kotlin/com/dpsmpx/dpai/android/TerminalActivity.kt:279` — `setMessage("…просит записать в буфер обмена:\n\n${text.take(PREVIEW)}")`, `PREVIEW = 500` (`:786`), `MAX_CLIP = 100_000` (`:787`). Ни длины, ни признака «показано не всё», ни видимости управляющих/невидимых знаков (U+202E, U+200B) в превью.
- Сценарий: программа на target (или `cat` файла из репозитория) шлёт OSC 52 с безобидным началом и хвостом `\n…\ncurl … | sh`; человек одобряет, позже вставляет в оболочку. При вставке `PasteGuard` (`terminal/PasteGuard.kt:13–14`) спрашивает только без скобок 2004 или при > 16 КиБ — в bash/zsh со скобками вопроса не будет (исполнения тоже — до Enter, но текст уже в строке ввода).
- Рекомендация: в заголовке окна — «N знаков, M строк»; если обрезано — явная пометка; невидимые/bidi знаки — заменять видимыми в превью. Уверенность: высокая (код), влияние низкое.
- Усилитель: вопрос перед многострочной вставкой (A-7) снимается флагом `bracketedPaste`, который включает любой вывод (`ESC[?2004h`, хоть `cat` файла) — `TerminalActivity.kt:672–677`, `terminal/TerminalEmulator.kt:462`. Оболочка без поддержки 2004 (busybox `ash` гостя Alpine, `dash`) режим не сбрасывает — следующая многострочная вставка уходит без вопроса, и каждая строка исполняется. Рекомендация: спрашивать о многострочной вставке всегда, когда режим 2004 включён не оболочкой входа, либо всегда для > 1 строки на `linux`-вкладке. Уверенность: средняя (поведение `ash` относительно 2004 — по памяти).

### N-06 — Низкая — DoS окнами: каждая OSC 52 — новый `AlertDialog`, без предела и без слияния
- `TerminalSession.kt` (terminal) `:140–142` — каждое OSC 52 → событие; `TerminalActivity.kt:255–290` — каждое событие → `runOnUiThread { AlertDialog… show() }`. Поток вывода из 8 КиБ кусков с тысячами `ESC]52;c;QQ==\a` складывает тысячи окон с `FLAG_SECURE` — экран терминала не работает, память растёт.
- Рекомендация: одно окно на вкладку (новое — заменяет ждущее), предел частоты. Уверенность: высокая; в документах нет.

### N-07 — Низкая — стабильность: `TerminalService` уходит `stopSelf()` без `startForeground()` после `startForegroundService()`
- `dpai/android/src/main/kotlin/com/dpsmpx/dpai/android/TerminalService.kt:27–31` (`n == 0 → stopSelf()`), `:36–37` (`catch IllegalStateException → stopSelf()`), а `sync()` (`:121–131`) зовёт `stopService`, когда вкладок нет, — возможно до `onStartCommand`.
- Гонка: открыть и сразу закрыть вкладку (или вкладка упала при подключении) — служба, запущенная `startForegroundService`, снимается до `startForeground`; Android (8.1+) в этом случае шлёт `SERVICE_FOREGROUND_CRASH_MSG` → «Context.startForegroundService() did not then call Service.startForeground()» — падение процесса со всеми вкладками и контроллером.
- Рекомендация: в `onStartCommand` всегда сначала `startForeground(...)`, затем `stopForeground(STOP_FOREGROUND_REMOVE)`+`stopSelf()`, если вкладок нет. Уверенность: средняя (поведение платформы — по памяти о `ActiveServices.bringDownServiceLocked`, «Bringing down service while still waiting for start foreground»; проверить на устройстве).
- Тот же узор у `ControllerService`: `ControllerService.kt:57–60` — действие `STOP` → `stopSelf()` до `startForeground`; если экран только что позвал `startForegroundService` (`DpaiApp.kt` цикл, `AiBackground.ensureService`), а «Остановить» пришло первым — та же гонка.

### N-08 — Инфо — ссылка OSC 8: видимый текст и адрес различаются; тост с адресом обрезается
- `terminal/TerminalEmulator.kt:596` — адрес OSC 8 — любой `https?://\S+` до 2048 знаков, независимо от видимого текста; `TerminalView.kt:631–641` + `ui/LinkTaps.kt:33` — первое касание копирует и показывает «Скопировано: $url» тостом (`Toast.LENGTH_LONG`), Android 12+ рисует тост в две строки — длинный адрес (`https://github.com@evil.example/…`, длинный поддомен) виден не целиком; второе касание открывает. Схема — только https (`LinkTaps.openable`) — хорошо. Рекомендация: при первом касании показывать хост отдельно (`URI.host`), без userinfo; отвергать адреса с `@` в authority. Уверенность: высокая; вред — фишинг, требует двух касаний.

## Прочитано (полностью)
- `dpai/android/.../RequestPresenter.kt` (RequestPresenter, RequestReceiver, Answers)
- `dpai/android/.../Notifier.kt`
- `dpai/android/.../RequestOverlay.kt`
- `dpai/android/.../MainActivity.kt`, `DpaiApplication.kt`
- `dpai/android/.../Confirm.kt`, `SettingIntents.kt`
- `dpai/ui/.../Requests.kt`
- `dpai/android/.../DpaiApp.kt` — выборочно: стр. 110–330 (цикл экрана, `incoming`, ссылки), 1185–1230 (`ConfirmDialog`)
- `dpai/terminal/.../TerminalEmulator.kt`, `TerminalSession.kt`, `KeyEncoder.kt`, `PasteGuard.kt`; `TerminalRow.kt` — выборочно (combine/insert/clear/links)
- `dpai/android/.../TerminalHub.kt`, `TerminalService.kt`; `TerminalActivity.kt` — стр. 50–300 и поиск (интенты, OSC 52, вставка); `TerminalView.kt` — выборочно (касание ссылки, кадр и `linkCells`, вставка, ввод, «Поделиться»)
- `dpai/ui/.../TerminalLaunch.kt`, `LinkTaps.kt`; `dpai/android/.../Clipboard.kt`

## Терминал — проверено и в порядке
- Параметры CSI: значение ≤ 65 535, групп ≤ 32, подпараметров ≤ 32, промежуточных ≤ 2; OSC ≤ 1 Mi знаков, заголовок ≤ 1000, составных знаков на ячейку — `MAX_COMBINING`; `REP` ≤ cols×rows; прокрутка/вставка/удаление ограничены областью; прокрутка назад ≤ 5000 строк; размер задаёт только экран (≤ 1000×500), программа его не меняет.
- Ответы программе (DA, DSR, DECRQM, OSC 4/10/11, `CSI 18 t`) — только числа и цвета, текст программы не повторяется; запрос заголовка (CSI 21 t) не поддержан; OSC 52 «?» без ответа; DCS/SOS/PM/APC глотаются до ST.
- Вставка: управляющие C0/C1 и DEL вырезаются, ESC не пройдёт — выйти из скобок 2004 нельзя; клавиатура — `VISIBLE_PASSWORD`, `NO_PERSONALIZED_LEARNING`.
- `linkCells` при рисовании ограничен `LINK_SPAN` строк кадра — длинная строка без переводов не морозит кадр.
- `FLAG_SECURE` у окна терминала и всех его диалогов (OSC 52, составление, вставка, закрыть всё).
- Каталог «Открыть в терминале» идёт в `sh -c 'cd "$1"…' sh <dir>` позиционным аргументом — без внедрения; имя `tmux` — только `[a-z0-9-]`, проверяется и перед `kill-session`.
- `LocalChannel`: ввод — своим потоком, очередь ≤ 1024; дескрипторы закрываются после выхода (A-11 исправлен).

### N-09 — Инфо — JNI `read`/`write`: нет проверки `len < 0` и `off+len`
- `dpai/android/src/main/cpp/dpai_native.c:236` — `read(fd, tmp, len < CHUNK ? (size_t) len : CHUNK)`: при отрицательном `len` приведение к `size_t` даёт огромное число, и `read` пишет в стековый `tmp[8192]` сколько придёт — переполнение стека. `:251` — `GetByteArrayRegion` при выходе за массив оставляет исключение, а цикл продолжает `write(fd, tmp…)` с неинициализированным `tmp` и зовёт JNI с висящим исключением.
- Сейчас недостижимо: `FdInput.read`/`FdOutput.write` (`linux/JniProcesses.kt:371–394`) получают корректные `off/len` от `InputStream`/`OutputStream`-клиентов (`LocalChannel` — буфер 8192). Рекомендация: в Kotlin — `Objects.checkFromIndexSize(off, len, b.size)`; в C — `if (len <= 0) return 0;`, проверка `ExceptionCheck` после `Get/SetByteArrayRegion`. Уверенность: высокая (код), достижимость — нет (гипотеза на будущее).

Дополнение к N-08: то же в тексте модели — `dpai/ui/.../Markdown.kt:172` (ссылка `[текст](адрес)`: видимый текст ≠ адрес), `dpai/android/.../ChatText.kt:22–25` — первое касание показывает адрес снэкбаром (`Undo.say`, `SnackbarDuration.Short`), длинный адрес обрезается; второе — открывает. Bidi-знаки (U+202E и др.) в тексте модели и в адресе не обезвреживаются.

## Модели — проверено и в порядке
- `LocalModel.start`: свободный порт на `127.0.0.1`, ключ 32 байта из `RandomSource.secure()` — файлом гостя (`writeLlamaKey`), не в argv; проба `/v1/models` своим ключом — только после строки «listening … <port>» из вывода своего процесса; профиль сохраняется после ответа; `usable()` сверяет порт запуска. Подмена порта процессом с тем же UID (гость, `SO_REUSEPORT`) — в пределах T13/T18.
- Долгие `start/stop/install` — через `LongJobs` (не главный поток), хотя `start` держит `@Synchronized` до 180 с.
- Ключ API в форме — `PasswordVisualTransformation`, `KeyboardType.Password` (`ModelsPane.kt:276–277`).

### N-10 — Низкая — восстановление копии настроек читает выбранный файл целиком без предела
- `dpai/android/src/main/kotlin/com/dpsmpx/dpai/android/SettingsBackups.kt:76` — `openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }`: файл, выбранный в системном окне (в «Загрузках» его может подложить другое приложение или гость Linux в режиме всех файлов, T14/T17), читается в память целиком, затем ещё строкой и JSON-деревом — OOM и падение при большом файле. Рядом в коде предел есть (`SafTree.readLimited`, `UserFiles.kt:214`). Рекомендация: `readLimited(it, 4 МиБ)` и отказ словами. Уверенность: высокая; вред — падение по действию человека.

### N-11 — Низкая — файл падения пишет сообщения исключений без `Redactor` и виден на диске приложения
- `dpai/android/src/main/kotlin/com/dpsmpx/dpai/android/CrashRecord.kt:25` — `vfs.write(FILE, describe(thread.name, error)…)`, `:37` — `"${e.javaClass.name}: ${e.message}"` для всей цепочки причин (до 8) без редактирования; KDoc (`:10–12`) обещает «имена классов, не данные пользователя». Рядом в журнал (`DpaiApplication.kt:185`) то же уходит через `Redactor.STANDARD`.
- `files/last-crash.txt` не в списке скрытых `AppDisk.hidden` (`dpai/ui/.../AppDisk.kt:43–53`) — открытый диск приложения (D-96, T15) отдаёт его менеджеру файлов. Сообщения исключений бывают с данными: путь с именем репозитория, ответ сервера, строка JSON с полем `token`, адрес с параметрами.
- Рекомендация: `Redactor.STANDARD.redact(...)` на каждое `message` в `describe`. Уверенность: высокая (код); насколько часто в `message` секрет — гипотеза.

### N-12 — Низкая — ошибка кода: идущий такт после остановки службы снова выставляет «AI: контроллер работает» и берёт wakelock
- `dpai/android/src/main/kotlin/com/dpsmpx/dpai/android/ControllerService.kt:234–240` — `onDestroy` делает `timer?.shutdown()` без прерывания (идущий такт доходит до конца — сознательно, A-26) и отпускает wakelock; но тело такта после этого продолжает: `:174–179` — `notify(ID, notification(line))` с `setOngoing(true)` (`:267`), если строка счётчиков сменилась, — постоянное уведомление «контроллер работает» возвращается в шторку как обычное и висит после остановки (смахнуть нельзя: ongoing); `:137`/`:120` — `lock.acquire(10 мин)` уже снятого wakelock (keeper остановлен, но `acquire` в теле такта — нет), `RequestPresenter.sync` и `EcosystemAuto.tick` тоже идут.
- Сценарий: человек «Остановить» во время долгого такта (проверка до 30 мин) → служба ушла → через минуты в шторке снова «AI: контроллер работает · открытых задач: N», хотя такты стоят (D-87 «не делать вид, что работаем»).
- Рекомендация: флаг `destroyed` (@Volatile), проверяемый перед `notify`/`acquire`/`sync` в теле такта; или `notify` только при `running`. Уверенность: высокая (код).

### N-13 — Низкая — ANR: применение облачной копии и сборка контроллера — в главном потоке
- `dpai/android/src/main/kotlin/com/dpsmpx/dpai/android/Controller.kt:113` — `val cloudRestored = CloudRestore(vfs, Clock.SYSTEM).applyPending()` в конструкторе; первый `Controller.get(filesDir)` — в `MainActivity.onCreate` (`MainActivity.kt:81`) и `ControllerService.onStartCommand` (`ControllerService.kt:94`) — оба в главном потоке. После «Восстановить из облака» (D-107) следующий запуск раскладывает копию (переименования, запись файлов журнала) до первого кадра/в `onStartCommand` — при большом журнале ANR. Там же в `onStartCommand` — `ControlServer.start(app)`, `DemoModel.get(app)`.
- Рекомендация: первый `Controller.get` — с фонового потока (экран уже умеет «контур собирается…»). Уверенность: средняя — длительность `applyPending` зависит от модуля `sync` (не смотрел), гипотеза.
- То же в `RequestReceiver.onReceive` (главный поток приёмника): `RequestPresenter.kt:264` → `model(app)` → `DemoModel.get(app).hasLive()` → `Controller.get(...).targets()` — после смерти процесса нажатие в уведомлении строит контроллер (и применяет копию) в главном потоке приёмника. В приложении DPmetaApp контроллер часто создаётся раньше фоновым `AiBackground.ensureService` (`AiBackground.kt:165–166`).

## Облако, копии, файлы — проверено и в порядке
- `CloudSync`: токен и пароль копии — `PasswordVisualTransformation`, хранятся `ApiKeys.putNamed` (о ключе Keystore без входа — уже найдено); отправка — одна за раз (`AtomicBoolean`), предел размера до шифрования; чужая копия по пути без слова человека не заменяется; при сборе копии ссылки не проходятся.
- `SafTree.write` проверяет путь `FileNames.pathValid`, усечение `"wt"`, чтение — `readLimited`; удаление — только файлов; `FileLinks.remove` отзывает постоянные права.
- `SettingsBackups.restore` перед восстановлением сохраняет нынешние настройки; копия — без секретов (`SettingsBackup.kt`).

## Linux-среда — проверено и в порядке
- `dpai_native.c`: `O_CLOEXEC` у каналов и master, сброс маски и обработчиков сигналов, `setsid`+`TIOCSCTTY`, закрытие дескрипторов ≥ 3 (`close_range` или цикл), отчёт об ошибке `exec` по каналу; `ptsname_r` в буфер 128; освобождение в одном `done`.
- `LinuxSetup.shell`: argv списком, окружение — `ProotHost.GUEST_ENVIRONMENT` + `TERM`/`COLORTERM`, без окружения Android; `share()` пускает только `/root/…` без `..`/`.`.
- `LinuxSetup.sharedBinds`/`storageBinds` — `mkdirs()` внутри rootfs гостя идёт по ссылкам гостя, но создаёт лишь пустые каталоги; в пределах принятого T13 (гость и так пишет данные приложения) — не находка.

## Прочитано (полностью) — продолжение
- `dpai/android/src/main/cpp/dpai_native.c`, `linux/JniProcesses.kt`, `linux/LinuxSetup.kt`

## Проверено и в порядке (пока)
- `RequestReceiver` не экспортируется (манифест — прошлый аудит), id из extras сверяется с id в `uri` (`RequestPresenter.kt:253`); mutable `PendingIntent` — только у `RemoteInput`, компонент явный; при `fillIn` исходные extras (`REQUEST`, `URI`) не перезаписываются.
- `MainActivity.take` принимает extras только через `InternalEntry`; `ConfirmDialog` применяет только `uri`, совпавший с кнопкой текущего ждущего запроса, и повторно проверяет `loginNeeded()`.
- `SettingIntents.load` декодирует любую команду из prefs, но `applied()` для «не настроек» → `true`, и такая команда снимается без `submit` — подложить решающую команду через prefs нельзя.
- `dpai/android/.../LocalModel.kt`, `ModelsStore.kt`, `LocalHints.kt`; `LocalModelPane.kt` — стр. 25–90
- `dpai/android/.../SettingsBackups.kt`, `dpai/ui/.../SettingsBackup.kt`; `CloudSync.kt` — стр. 80–300 и поиск по секретам
- `dpai/android/.../UserFiles.kt` (AllFiles, GuestPaths, SafTree, FileLinks)
- `dpai/ui/.../PcSetup.kt`, `WorkspaceLink.kt`, `TermuxRestore.kt`; `Targets.kt` — поиск по проверке полей
- `dpai/android/.../CrashRecord.kt`, `AppLogs.kt`, `LongJobs.kt`, `ControllerService.kt`, `Drafts.kt`, `Polling.kt`, `AiBackground.kt`, `ChatText.kt`
- `dpai/android/.../EcosystemAuto.kt` — стр. 1–140 и поиск; `EcosystemPane.kt` — стр. 88–125, 220–232, 245–339
- `dpai/android-app/build.gradle.kts`, `dpai/android/src/main/cpp/CMakeLists.txt`, `dpai/android/build.gradle.kts` — поиск

## Покрытие

Полностью: `RequestPresenter.kt` (+`RequestReceiver`, `Answers`), `RequestOverlay.kt`, `Notifier.kt`, `MainActivity.kt`, `DpaiApplication.kt`, `Confirm.kt`, `SettingIntents.kt`, `TerminalHub.kt`, `TerminalService.kt`, `ControllerService.kt`, `linux/LinuxSetup.kt`, `linux/JniProcesses.kt`, `cpp/dpai_native.c`, `LocalModel.kt`, `ModelsStore.kt`, `LocalHints.kt`, `SettingsBackups.kt`, `UserFiles.kt`, `CrashRecord.kt`, `AppLogs.kt`, `LongJobs.kt`, `Clipboard.kt`, `ChatText.kt`, `Drafts.kt`, `Polling.kt`, `AiBackground.kt`; `ui`: `Requests.kt`, `LinkTaps.kt`, `TerminalLaunch.kt`, `PcSetup.kt`, `WorkspaceLink.kt`, `TermuxRestore.kt`, `SettingsBackup.kt`; `terminal`: `TerminalEmulator.kt`, `TerminalSession.kt`, `KeyEncoder.kt`, `PasteGuard.kt`; `android-app/build.gradle.kts`, `CMakeLists.txt`.

Выборочно (поиск по опасным местам и чтение найденного): `TerminalActivity.kt` (стр. 50–300, 655–690), `TerminalView.kt` (касание ссылки, кадр, вставка, ввод, «Поделиться»), `TerminalRow.kt`, `DpaiApp.kt` (110–330, 776–801, 918–938, 1185–1230), `Controller.kt` (1–140, `get`), `DemoModel.kt` (замки, `command`, `intent`), `CloudSync.kt` (80–300), `EcosystemAuto.kt`, `EcosystemPane.kt`, `ModelsPane.kt`, `LinuxPane.kt`, `RequestsPane.kt`, `TargetsPane.kt` (код сопряжения, восстановление Termux, ключ хоста), `SetupPane.kt`, `FilesPane.kt`, `LocalModelPane.kt`, `Markdown.kt` (регулярки, ссылки), `Targets.kt` (проверка полей), `SetupGuide.kt` (подстановки), `android/build.gradle.kts`.

Не смотрел: `TaskSheet.kt` (кроме буфера обмена), `QuickPane.kt`, `AiSettingsPane.kt`, `LogPane.kt` (кроме копирования), `NotifyGroups.kt`, `UsageAuto.kt`, `AiSummary.kt`, `SessionNames.kt`; `ui`: `LiveSystem.kt`, `Screens.kt`, `TaskScreens.kt`, `GlobalSearch.kt` (кроме регулярок), `DemoSystem.kt`, `Project.kt`, `NewTaskForm.kt`, `QuickActions.kt`, `AppPalette.kt`, `DashboardText.kt`, `UsageText.kt`, `AiTabs.kt`, `ExecutorOptions.kt`; `terminal`: `BoxDrawing.kt`, `CharWidth.kt`, `Style.kt`, `Utf8Decoder.kt`, `TerminalSelection.kt` (кроме регулярки); ресурсы. По заданию не читались: `AndroidManifest.xml`, `ControlServer.kt`, `AppDiskProvider.kt`, `ApiKeys.kt`, `Downloads.kt`, `TermuxLauncher.kt`, `ui/AppDisk.kt` (кроме `hidden`).

## Что проверено и в порядке (сводно)
- Запросы: явные неэкспортируемые получатели; mutable `PendingIntent` только для `RemoteInput`; id ↔ `uri` сверяются; DESTRUCTIVE — через `MainActivity` (`InternalEntry`) и повторную проверку входа; окно поверх приложений — только при флаге человека и разрешении системы, скрывается при видимом приложении.
- Терминал: пределы разбора, ответы без текста программы, OSC 52 только запись с вопросом и пределом 100 000, вставка без управляющих знаков, `FLAG_SECURE` окна и диалогов, ссылки — только https и двумя касаниями.
- Linux: JNI-запуск без оболочки, окружение гостя, закрытие дескрипторов; позиционные аргументы `sh -c … sh <arg>`; имена tmux по шаблону.
- Строки для ПК/Termux: ключи подставляются только после строгих регулярок; `user@host` проверены `SshEndpoint.of` и при чтении копии (`TargetBook.fromJson` → `TargetForm.validate`); PowerShell — `-EncodedCommand`.
- Модели: ключ сервера не в argv, проба только после «listening» своего процесса, профиль телефона сверяет порт; ввод ключей — маскированный.
- Экосистема: смена файла/источника манифеста выключает политику; манифест читается с пределом размера; уведомление о каждой задаче политики.
