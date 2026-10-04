# Этап 2 — Реальное ядро (Xray)

Состояние: в работе.

## Цель

Заменить фиктивное ядро настоящим — Xray-core (D-03) — за той же
границей `ProxyEngine`/`BackendCompiler` (D-04) и поднять туннель на
устройстве без утечек.

## Вход

- Этап 1 выполнен.
- Версия Xray выбрана по чек-листу D-18 (`docs/PROCESS.md`,
  «Зависимости»); строка в `docs/legal/THIRD_PARTY_MATRIX.md` из
  «запланирован» переведена в «разрешён» вместе с `docs/PROVENANCE.md`.
  Выполнено: Xray 26.9.30 (D-24).

## Работы

1. **Матрица возможностей Xray** выбранной версии — `EngineCapabilities`
   с тестом: что из IR v1 (D-16) Xray выражает, что — нет.
   Сделано: `XrayBackend.CAPABILITIES`, `XrayCompilerTest.matrixLacksOnlyWhatXrayCannotExpress`.
2. **Компилятор IR → JSON Xray** (`engine/`, пакет своего бэкенда):
   протоколы, транспорты, защиты IR v1; маршрутизация (`engineRules`
   после `AppLowering`), DNS, вход TUN. Отказ с путём там, где смысл не
   сохраняется (D-05). Тесты: корпус `tests/data/configs/` компилируется,
   результат сверяется с эталонными файлами (golden) и проверяется
   самим Xray (`-test`) на хосте. Сделано: `XrayCompiler`,
   `XrayCompilerTest`, эталоны `tests/data/xray/`, шаг hostcheck «Xray»
   (`docs/TESTING.md`, «Эталоны Xray»).
3. **Сверка смысла маршрутизации**: на корпусе запросов
   `tests/data/routing/` решение Xray совпадает с `RouteEvaluator`
   (на хосте, через тестовый запуск ядра). Сделано: `*.probe.json` и
   `dppass-xray-probe route` (маршрутизатор Xray без запуска ядра).
4. **Интеграция на Android**: libXray (MIT) или своя обёртка gomobile;
   `ProxyEngine` поверх неё; `protect()` для исходящих сокетов ядра.
   Сделано: своя обёртка Go с JNI (D-25) — мост `tools/xray/bridge` с
   `protect()` и политикой входов, экспорт JNI `tools/xray/jni`,
   `XrayProxyEngine`; библиотека собирается для хоста и трёх ABI Android
   (`tools/xray/native.py`, задача `xrayNative<Вариант>`), тест JNI на
   хосте (`XrayJniTest`), `AppGraph` на Xray. На устройстве не проверено
   (`TODO.md`).
5. **Мост fd → ядро** — эксперимент (отложенное решение): TUN inbound
   Xray против hev-socks5-tunnel по стабильности, памяти, батарее и
   утечкам; итог — новое решение D-NN. Если победит вариант с локальным
   SOCKS-входом — вход только с аутентификацией (`docs/SECURITY_MODEL.md`, T11).
6. **Сервис туннеля**: путь `AndroidTunnelProvider.establish` →
   `TunInput` → `DPpassCore.connect` включается; решение об отдельном
   процессе (отложенное) принимается здесь.
7. **Проверки**: выравнивание нативных библиотек по 16 КБ — шаг
   hostcheck; размер APK по ABI — в `docs/PERFORMANCE_BUDGET.md`.
   Сделано: `check_apk.py` (только библиотека ядра трёх ABI, без
   сжатия, выравнивание в zip и ELF по 16 КБ), размер — в бюджете.

## Критерии выхода

- [ ] VLESS + REALITY + Vision и ещё по одному кандидату классов HTTP/CDN и
      UDP/QUIC подключаются на устройстве через TUN.
- [ ] Нет утечек: IPv6 и DNS приложений в туннеле не идут мимо него
      (проверка на устройстве — запись в `TODO.md`).
- [x] Компиляция корпуса и сверка маршрутизации — в hostcheck (шаг «Xray», D-24).
- [x] `MockProxyEngine` остаётся вторым бэкендом в тестах; в приложении его
      больше нет (`check_modules.py`: правило `engine.mock`).

## Не делается

Второе реальное ядро; интерфейс продукта; загрузка подписок по сети.
