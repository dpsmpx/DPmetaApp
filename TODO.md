# TODO

Текущие задачи, известные долги и то, что ещё не проверено. Этапы —
`docs/direction/ROADMAP/README.md`; решения — `docs/direction/DECISIONS.md`.
Закрытая запись удаляется (история — в git) или переносится в документ,
если стала знанием.

## Следующее (этап 2)

Работа 4 (`docs/direction/ROADMAP/02-xray-backend.md`) — в работе по
D-25. **Сделано:** мост Go `tools/xray/bridge` (один экземпляр,
политика входов «только TUN» на собранной protobuf, хук `protect()` с
отказом через `dup3`, счётчики `proxy`, неполная проверка при работающем
ядре) и его `go test` (обе сборки, сквозные тесты с тестовым входом);
экспорт JNI `tools/xray/jni` (собирается для хоста:
`go build -buildmode=c-shared -tags dppass_testinbound ./jni` с
`CGO_CFLAGS=-I$JAVA_HOME/include -I$JAVA_HOME/include/linux`);
`SocketProtector`, `TunInput.protector`, `TunnelHandle.protect`,
`toTunInput()`; `XrayRuntime`/`XrayJni`/`JniXrayRuntime`/`XrayProxyEngine`
и `XrayProxyEngineTest`; код `BACKEND_VALIDATION_PARTIAL`; счётчики в
`XrayCompiler` и перезаписанные эталоны; факты — `BACKEND_STRATEGY.md`, §6.
Сборка библиотеки — `tools/xray/native.py` (`host`: go vet, go test моста
в обеих сборках, библиотека для JVM хоста в `build/xray/host/`;
`android --out DIR`: NDK `android-ndk` из каталога, три ABI, проверка
ELF — машина, выравнивание 16 КБ, экспорт JNI = `external` XrayJni),
общее окружение Go — `tools/xray/goenv.py`; `go.mod` приведён `go mod
tidy` (`golang.org/x/sys` — прямая, версии те же). Тест JNI на хосте —
`XrayJniTest` (набор `xrayJni` в `tests/integration`, задача
`xrayJniTest`, без `-Pdppass.xray.lib` — отказ с подсказкой) и шаг
hostcheck «Xray: мост и JNI» (`native.py host` + `xrayJniTest`).

Осталось по работе 4 (порядок — как здесь):

- [ ] `:app`: задача сборки библиотеки на вариант
      (`androidComponents.onVariants` → `sources.jniLibs.addGeneratedSourceDirectory`,
      задача совместима с configuration cache — `ExecOperations`).
- [ ] `run.sh`: шаги Android — требовать NDK (в `--full` его нет — ошибка);
      шаг ядра собирать без `:app` (APK без Go и NDK не собирается, D-25).
- [ ] `check_apk.py`: `NATIVE_OK` — ровно `lib/<3 ABI>/libdppassxray.so`,
      выравнивание ELF и смещения в zip по 16 КБ, без сжатия; размер по
      ABI — `docs/PERFORMANCE_BUDGET.md`.
- [ ] `check_forbidden_apis.py`: `System.load*`/`external fun` — только
      `AppGraph`, `XrayRuntime.kt` и тест JNI.
- [ ] `AppGraph` → `XrayCompiler` + `XrayProxyEngine(JniXrayRuntime.load(
      { System.loadLibrary(LIBRARY) }, каталог геоданных))`;
      `check_modules.py`: `engine.mock` в `app/` запрещён; тексты
      `MainActivity`/`strings.xml` про фиктивное ядро.
- [ ] Геоданные на устройстве (источник, лицензия, обновление) —
      решение; до него конфигурации с geoip/geosite ядро на устройстве
      отвергает.
- [ ] Документы: `ARCHITECTURE.md` (§2 — `engine`, правило
      `engine.mock`; §3.4, §6, §11), `SECURITY_MODEL.md` (T4 — `protect`
      сделан; T10 — сборка библиотеки; T11 — политика моста; таблица §6),
      `TESTING.md` (go test моста, тест JNI на хосте), `PROCESS.md` (NDK,
      новые шаги), матрица лицензий (libXray — «отклонён»; модули Go
      §1.7 теперь и в APK; NDK как инструмент) и `PROVENANCE.md` (NDK
      r30 30.0.16248370 с dl.google.com), `CLAUDE.md` («Что это»).
- [ ] Полный прогон `tools/hostcheck/run.sh --full` — после всего выше
      (в этом изменении не выполнялся).

Дальше по этапу 2:

- [ ] Имя сервера профиля (`server` — домен) Xray резолвит системным
      резолвером: у исходящего `proxy` нет `sockopt.domainStrategy`
      (`transport/internet/dialer.go`, `DialSystem`). Решить с записью в
      `docs/SECURITY_MODEL.md` (T4); учесть, что `core.New` переназначает
      глобальный DNS-клиент dialer (`BACKEND_STRATEGY.md`, §6).
- [ ] Состав модулей Go в библиотеке для APK: `juju/ratelimit`
      (LGPL-3.0 с исключением, через `xtls/reality`, нужен REALITY) —
      уведомления к этапу 5; модули функций вне IR v1 приходят с
      разбором JSON Xray (`infra/conf`) и не отсекаются.
- [ ] Эксперимент «мост fd → ядро» (работа 5), сервис туннеля и
      отдельный процесс (работа 6).

## Известные долги (из аудита)

Подробности и обоснование — `docs/audits/INITIAL_ARCHITECTURE_AUDIT.md`.

- [ ] `MainActivity` не запрашивает `POST_NOTIFICATIONS` (Android 13+):
      уведомление сервиса не будет видно без разрешения (этап 4 —
      вместе с интерфейсом; до этого — запись здесь).
- [ ] `SimulatedHealthChecker` лежит в основном коде `connection/` и
      попадает в APK; не используется приложением (`check_modules.py`
      это охраняет). Перенести в тестовую фикстуру, когда у `cli`
      появится своя модель сети.
- [ ] `RouteEvaluator` перебирает правила линейно: ~120 мкс на решение
      при 2000 правил на хосте (`docs/PERFORMANCE_BUDGET.md`, §3). Для
      объяснения решений достаточно; если понадобится массово —
      индекс по суффиксам.
- [ ] `ActivationStore.parse` молча пропускает повреждённые записи
      истории (`mapNotNull`) и записи `active`/`previous`, а при
      повреждённом `next_revision` считает ревизии заново с 1;
      повреждение стоит отмечать находкой, как и прочее содержимое.
- [ ] Проверка зависимостей Gradle (`gradle/verification-metadata.xml`,
      SHA-256): версии закреплены, содержимое артефактов — нет (T10).
      Генерировать там, где есть Android SDK, — иначе не войдут
      зависимости `app`.
- [ ] Тесты на пределы без тестов: `maxProfiles`, `maxRuleValues`,
      `maxEntries` подписки, история активаций (32), тайм-аут проверки
      здоровья (`docs/PERFORMANCE_BUDGET.md`, §2).
- [ ] Устаревшее в сборке: `android.useAndroidX=false` (удаляется в
      AGP 10), `Configuration.setVisible` из плагина `com.android.lint`
      (удаляется в Gradle 11) — разобрать при обновлении по D-18.
- [ ] Форматтер Kotlin не выбран: 78 строк длиннее 160 символов
      (самая длинная — 257); `check_format.py` проверяет только
      минимумы (кодировка, переводы строк, пробелы, невидимые символы).
      Форматтер — новая зависимость сборки, выбор по чек-листу D-18.
- [ ] D-12 для сборки: проверить, что `fus-statistics-gradle-plugin`
      (у Kotlin Gradle Plugin) и `analytics-library` (у AGP) ничего не
      отправляют при сборке из командной строки; при
      необходимости выключить явно и записать способ проверки.

## На устройстве

Написано, но на реальном устройстве ещё не проверялось (этап 3):

- [ ] `KeystoreKeyWrapper`: создание и чтение ключа, перезапуск
      процесса, обновление приложения, смена блокировки экрана; какие
      исключения Keystore на деле временные, а какие — утрата ключа;
      сброс хранилища (`DPpassCore.resetStorage`) на устройстве.
- [ ] `EncryptingBlobStore` + `FileBlobStore` в `noBackupFilesDir`:
      атомарная замена на файловых системах устройства.
- [ ] `DPpassVpnService`: foreground `specialUse` на Android 14+,
      уведомление, `onRevoke`, остановка с `BACKEND_NOT_AVAILABLE`.
- [ ] `FLAG_SECURE` в оболочке: скриншоты и миниатюры «Недавних».
- [ ] Правила `data_extraction_rules.xml`: резервная копия и перенос
      ничего не уносят.
- [ ] `AndroidTunnelProvider`: Builder принимает `TunnelSpec` (API 26,
      29, 33+; с `excludeRoute` и без) — вместе с реальным ядром.

## Исследования

- [ ] Обновить `docs/research/RUSSIA_NETWORK_RESTRICTIONS.md` перед
      этапом 3 (пробы сети) — состояние меняется неделями.
- [ ] Повторить опись `tools/research/happ_inventory.sh` перед этапом 4
      (импорт профиля маршрутизации — отложенное решение).
