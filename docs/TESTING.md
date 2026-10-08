# Тестирование

Как проверяется оркестратор: слои тестов, детерминизм, свойства,
mock-исполнение, нагрузка и хаос (ТЗ, §88–§97). Как запускать —
`docs/PROCESS.md`, «Проверки».

## 1. Слои

| Слой | Где | Что | Когда появляется |
|---|---|---|---|
| Модульные | `<модуль>/src/test` | автоматы, результат, диагностика, JSON, хеши, планировщик, политики | этап 1 и далее |
| Свойства | `<модуль>/src/test`, средства — `testkit` | инварианты на случайных входах с зерном | этап 1 |
| Межмодульные | `tests/integration` | сценарии через `orchestrator` на `MockRunner` | этапы 4, 14 |
| Интеграционные с внешним | тесты модулей, включаются свойством | настоящий SSH target — `-Ddpai.test.ssh=пользователь@хост:порт:posix\|windows:команда-агента` (`AgentCommandTest`); Git, Termux, Claude Code CLI | этапы 3, 4, 9 |
| Инварианты ТЗ, §118 | `orchestrator` (`Invariants`) | одиннадцать инвариантов: сессия — задача, target, слот; одна активная сессия на задачу; слияние — с одобрением; id и номера событий; нет секретов в диагностике; DONE — с итогом проверки; деревья — с живым владельцем и без общих путей; проверяются после каждого такта в сценариях | этап 19 |
| Сквозные | `tests/integration` | задача → назначение → сессия → события → проверка → завершение (ТЗ, §88): `V1ScenariosTest`, сценарии V1 1–4 | этап 14 |
| Нагрузка, хаос, выносливость | `tests/integration` (`PerformanceTest`, `RecoveryTest` в `orchestrator`, `ChaosLoopTest` — хаос ТЗ, §117 на модельном времени за минуту, `EnduranceTest`) | ТЗ, §93–§95, §116, §117; выносливость — только явно: `-Ddpai.endurance.minutes=1440`, отчёт `build/audits/final-stability/` | этапы 13, 17, 19 |

## 2. Детерминизм

- Время — `ManualClock`: `now()`, `advance()`; `Thread.sleep` в тестах
  запрещён (ТЗ, §90; D-07, `check_forbidden_apis.py`).
- Случайность — `RandomSource` с зерном; упавший тест печатает зерно, и
  прогон воспроизводится.
- Порядок — устойчивый: тест не зависит от порядка хеш-таблиц.

## 3. Свойства

Property-тесты — свои, без сторонних библиотек (D-17): `testkit`,
`Property.forAll(генератор) { … }`. Число итераций и зерно — системные
свойства `dpai.property.iterations` (по умолчанию 200) и
`dpai.property.seed` (по умолчанию — фиксированное):

```sh
./gradlew :core:test -Ddpai.property.iterations=5000 -Ddpai.property.seed=42
```

При отказе тест сообщает зерно, номер итерации и вход — этого хватает,
чтобы воспроизвести случай одним прогоном. Свойства этапа 1 (ТЗ, §91):

| Свойство | Тест |
|---|---|
| недопустимые переходы отвергаются, допустимые выполняются | `LifecycleTablesTest` |
| повтор перехода не меняет состояние | `StateMachineTest` |
| разбор канонического JSON возвращает то же значение | `CanonicalJsonTest` |
| хеш не зависит от порядка ключей | `HashingTest` |
| повтор события идемпотентен, порядок событий не портит состояние потока | `SequenceTrackerTest` |
| идентификаторы упорядочены по времени создания | `IdsTest` |

## 4. Модель сбоев хранилища

Хранилище проверяется не на настоящем диске, а на `SimulatedVfs`
(тесты `storage`) — модели строже обычной файловой системы (D-24):

- данные файла переживают сбой только после fsync; оборванное
  дописывание оставляет случайную часть хвоста; перезаписанный без fsync
  файл становится прежним, пустым или началом нового;
- создание, переименование и удаление переживают сбой только после
  fsync каталога;
- `crashAtOp` обрывает процесс перед операцией с номером k, `failAtOp` —
  отказ ввода-вывода (диск полон) с частичной записью.

`crashEverywhere` прогоняет сценарий с обрывом перед каждой операцией
по очереди и проверяет инвариант после восстановления: «прежнее или
новое», «подтверждённое не потеряно». Сила тестов проверена мутациями:
без fsync каталога в `DocumentStore` и без fsync в `EventJournal` тесты
падают. `NioVfsTest` проверяет настоящую файловую систему во временном
каталоге.

## 5. Mock-исполнение

`MockRunner` моделирует без настоящего Claude: успех, отказ, тайм-аут,
падение, медленный вывод, повтор и пропуск событий, rate limit, ошибку
аутентификации, частичное завершение (ТЗ, §89). Это настоящий
`ClaudeCodeCliRunner` поверх `MockAgentHost`: сценарии проходят всю
цепочку «агент → канал → разбор → итог хода» (`MockRunnerTest`). Ядро
тестируется на нём целиком.

## 6. Корпус и fuzz

Образцы вывода Claude Code CLI (stream-json) — `tests/data/claude-stream/`:
записанные с Claude Code 2.1.291 и построенные из них испорченные (что
откуда — README корпуса); fuzz порчи строк — `ClaudeStreamTest`.
Вредоносные входы для контекста — этап 8. Новый баг —
новый файл корпуса. Fuzz разбора событий, графов задач, манифестов
контекста и сохранённого состояния (ТЗ, §92) — этапы 2, 4, 8.

## 7. Настоящие процессы

Процессы, агент и остановка дерева проверяются на настоящих процессах ОС
(`LocalProcessHostTest`, `AgentRuntimeTest`, `AgentCommandTest`):
программа-потомок `TestChild` — та же JVM с известным поведением
(вывод, ожидание, запуск внука), поэтому тесты одинаковы на Linux и
Windows. Внешний мир ждут `RealTime.await` из `testkit` — ограниченно и
с описанием, чего не дождались; логика со временем по-прежнему — на
`ManualClock`. Каждый тест убирает запущенные процессы, даже упав.

## 8. Проверка по SSH

На машине автора (7 октября 2026) — Windows-target на ней же: OpenSSH
Server слушает только `127.0.0.1`; агент — копия `cli/build/install/dpai`
и обёртка, задающая JDK 21; ключ — без пароля (`ssh-keygen -N ""`; в
PowerShell `-N '""'` даёт ключу пароль из двух кавычек, и `BatchMode`
молча отказывается подписывать); для учётной записи администратора ключ —
в `C:\ProgramData\ssh\administrators_authorized_keys`. Запуск:

```sh
./gradlew :cli:test --tests '*AgentCommandTest*' \
  -Ddpai.test.ssh=dpu@127.0.0.1:22:windows:C:/Users/dpu/.dpai/dpai-agent.cmd \
  -Ddpai.test.ssh.identity=C:/Users/dpu/.ssh/dpai_test \
  -Ddpai.test.ssh.knownHosts=C:/Users/dpu/.ssh/dpai_known_hosts
```

Linux-target — Ubuntu 24.04 в WSL2 на той же машине: `openssh-server` и
`openjdk-21-jre-headless`; порт 2223 (`/etc/ssh/sshd_config.d/dpai.conf`,
вход только ключом); в Ubuntu 24.04 порт задаёт `ssh.socket`, поэтому он
выключен, а включена `ssh.service`; агент — копия `dpai` в
`/home/dpu/dpai`. WSL гасит дистрибутив без открытых сессий: на время
проверки его держит `wsl.exe -d Ubuntu-24.04 -u root -- bash -c
"systemctl start ssh.service; exec sleep infinity"`. Из Git Bash —
`MSYS_NO_PATHCONV=1`, иначе путь `/home/…` в свойстве переписывается.

```sh
MSYS_NO_PATHCONV=1 ./gradlew :cli:test --tests '*AgentCommandTest*' \
  -Ddpai.test.ssh=dpu@127.0.0.1:2223:posix:/home/dpu/dpai/bin/dpai \
  -Ddpai.test.ssh.identity=C:/Users/dpu/.ssh/dpai_test \
  -Ddpai.test.ssh.knownHosts=C:/Users/dpu/.ssh/dpai_known_hosts_wsl
```

Linux-target в облачной сессии (8 октября 2026) — Ubuntu 24.04 в
контейнере, локаль `POSIX` (у процессов по SSH `LANG` пуст — так бывает
на серверах): `openssh-server` на `127.0.0.1:2223`, вход только ключом,
отдельный пользователь с агентом в `~/dpai`. `AgentCommandTest`,
`JschProcessHostTest`, `SshKeysTest` прошли с теми же свойствами
`dpai.test.ssh*`; кириллица в аргументах на такой локали —
`PROCESS_ARGUMENT_UNENCODABLE`, а не `?` (`docs/EXECUTION_MODEL.md`, §5).

Termux-target — Realme C21Y по USB (D-33): в Termux `openssh`, `sshd`
слушает 8022, ключ `dpai_test` — в `~/.ssh/authorized_keys`; с ПК —
`adb forward tcp:8022 tcp:8022`, затем вход на `127.0.0.1:8022`, ключ
хоста — в отдельном known_hosts. На время проверки —
`adb shell settings put global stay_on_while_plugged_in 2`. Агент — копия
`dpai` в `~/dpai` и `pkg install openjdk-21`. Claude Code — внутри
`proot-distro login alpine` (`docs/research/CLAUDE_CODE_CLI.md`, §9):
musl-сборка `@anthropic-ai/claude-code-linux-arm64-musl` распакована в
`~/.local/share/claude-musl`, а `tools/termux/claude-proot.sh` поставлен
как `$PREFIX/bin/claude` — агент и `doctor` вызывают `claude` как на
любом target. Места нужно с запасом: JDK — около 260 МиБ, Claude — около
230 МиБ.

Настоящий Claude Code — `RealClaudeTest` в `cli`: два хода через агента,
второй — `--resume`. Тратит лимит подписки, поэтому включается только
вместе с `-Ddpai.test.claude=true`; вход в Claude Code на target делает
человек (D-27).

Тот же путь через SSH-клиент на JVM (D-35) — `JschProcessHostTest` в
модуле `jsch` с теми же свойствами `dpai.test.ssh*`; без них идут только
проверки отказов.

Финальный аудит (ТЗ, §99–§125): после `tools/verify/run.sh full` —
`python3 tools/audit/final_stability.py --endurance <отчёт EnduranceTest>`.
Он сопоставляет пункты аудитов A–S с тестами и берёт статусы из
результатов JUnit; пункт без доказательства — `GAP`, на устройстве —
`DEVICE`, тест не запускался — `SKIPPED`; отчёт —
`build/audits/final-stability/`.

## 9. Приложение Android

Логика экранов проверяется на хосте (`ScreensTest` в `ui`); модуль
`android` только рисует (D-34). Сборка и установка на устройство по USB:

```sh
./gradlew :android:assembleDebug
adb install -r android/build/outputs/apk/debug/android-debug.apk
adb shell am start -n com.dpsmpx.dpai/com.dpsmpx.dpai.android.MainActivity
adb shell dumpsys gfxinfo com.dpsmpx.dpai   # кадры: Janky frames, перцентили
```

Кадры меряются на release (`:android:assembleRelease`, пока подписан
отладочным ключом): debug-сборка Compose в разы медленнее. На устройствах
с выключенным системным журналом (Realme C21Y) причина падения — в
`files/last-crash.txt` приложения: `adb shell run-as com.dpsmpx.dpai cat
files/last-crash.txt` (только debug).

Модуль подключается, только если найден Android SDK (`local.properties`
или `ANDROID_HOME`); без SDK `tools/verify/run.sh full` проверяет ядро
как прежде.

## 10. Что тесты пока не покрывают

Всё, что требует устройства Android или Claude, — пока его нет рядом с
прогоном (этапы 4, 11 и 19): агент `dpai` в Termux и ход сессии в proot
ещё не проверены (`TODO.md`). Это не «пропущено
молча»: такие критерии выхода записаны в файлах этапов и в `TODO.md`.
