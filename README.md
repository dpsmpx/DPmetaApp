# DPpass для Linux (Garuda) — отладочная сборка, 21 октября 2026

Только файлы сборки для компьютера автора (D-24: приложение нигде не
публикуется; репозиторий приватный). Код — коммит
`f68df97ce7a73b12d445c21392b8d57c42c4fa25` ветки `main` (20 октября 2026),
DPpass 0.7.34-dev (`versionName` APK того же коммита). В `main` эта ветка
не вливается.

| Файл | Что это |
|---|---|
| `dppass-cli-0.7.34-dev-f68df97.tar.gz` | `dppass-cli` — DPpass на JVM: каталог `dppass-cli/` с `bin/dppass-cli` и `lib/` (22 файла), нужна Java 17+ |
| `examples/valid-minimal.json`, `examples/valid-multi.json` | синтетические конфигурации из корпуса `dppass/tests/data/configs/` для пробы |
| `SHA256SUMS` | суммы SHA-256 файлов |

## Что это и чего в нём нет

На Linux у DPpass одна форма — `dppass-cli` (модуль `dppass/cli`):
оболочка над теми же модулями, что у Android-приложения (конфигурация,
маршрутизация, подписки, сеть, подключение), без интерфейса и **с
фиктивным ядром**. Она разбирает, проверяет и компилирует конфигурацию,
объясняет решения маршрутизации, строит план кандидатов и «подключается»
на модели сети, обновляет подписку из файла, экспортирует конфигурацию
с отредактированными секретами, пишет локальный отчёт и меряет скорость
конвейера.

Чего нет: **это не VPN-клиент для Linux.** Ядро Xray и туннель есть
только в APK (сервис туннеля Android, D-25); `dppass-cli` трафик не
пропускает и в сеть не ходит вовсе — подписка берётся из файла.

## Установка на Garuda

```sh
sudo pacman -S --needed jre-openjdk     # подойдёт любая Java 17+ (jre17-openjdk, jre21-openjdk)
git clone --depth 1 --branch linux-debug https://github.com/dpsmpx/DPmetaApp.git dppass-linux
cd dppass-linux
sha256sum -c SHA256SUMS
mkdir -p ~/.local/opt ~/.local/bin
tar -xzf dppass-cli-0.7.34-dev-f68df97.tar.gz -C ~/.local/opt
ln -sf ~/.local/opt/dppass-cli/bin/dppass-cli ~/.local/bin/dppass-cli
```

Если `~/.local/bin` нет в `PATH`: в fish (оболочка Garuda по умолчанию) —
`fish_add_path ~/.local/bin`, в bash — строка
`export PATH="$HOME/.local/bin:$PATH"` в `~/.bashrc`. Репозиторий
приватный: `git clone` спросит вход в GitHub (токен или `gh auth login`).

Удалить: `rm -r ~/.local/opt/dppass-cli ~/.local/bin/dppass-cli`.

## Как пользоваться

```sh
dppass-cli help
dppass-cli check examples/valid-multi.json                       # ACTIVATED revision=1 profile=reality-1 …
dppass-cli route examples/valid-multi.json --domain www.example.ru   # DIRECT (ru-direct): правило ru-direct
dppass-cli plan examples/valid-multi.json --condition TLS_DEGRADED
dppass-cli connect examples/valid-multi.json --condition ALLOWLIST_LIKE   # CONNECTED via domestic-relay
dppass-cli export examples/valid-minimal.json                    # секреты — <redacted>
dppass-cli report examples/valid-minimal.json
dppass-cli bench --rules 2000
```

Своя конфигурация — тот же файл формата DPpass, что импортирует
приложение (`dppass/docs/CONFIG_FORMAT.md`). Подписка — тело ответа в
файле: `dppass-cli subscription body.txt [--headers headers.json]`.
Условия сети для `classify`, `plan`, `connect` — `NORMAL`,
`ALLOWLIST_LIKE`, `TLS_DEGRADED`, `OFFLINE` и др.: неверное имя выводит
весь список. Код выхода: 0 — успех, 1 — отказ по существу, 2 — неверный
вызов.

## Как собрано

В облачной сессии 21 октября 2026, OpenJDK 21.0.12, Gradle 9.8.0 из
обёртки `dppass/`: `./gradlew :cli:build :cli:distTar`, архив — `distTar`,
сжатый `gzip -9n`. Байт-код — Java 17 (`-Xjdk-release=17`), как у всех
модулей ядра DPpass.

Отличия от обычной сборки — только в окружении, не в коде:

- **Google Maven (`dl.google.com`) закрыт сетевой политикой окружения.**
  Оттуда `build-logic` берёт плагин Android Gradle — ради Android Lint
  модулей ядра и сборки `:app`. Сборка шла во временной копии `dppass/`
  того же коммита, где в `build-logic` нет зависимости от AGP, плагина
  `com.android.lint` и плагина `dppass.android-app`; исходники модулей,
  каталог версий и `gradle/verification-metadata.xml` — без изменений.
  Поэтому Android Lint в этой сборке не запускался.
- **Maven Central отвечал 429 (Too Many Requests).** Зависимости шли через
  локальное кеширующее зеркало, которое повторяет запрос после паузы.
  Проверка зависимостей Gradle (`verify-metadata`) включена: каждый файл
  сверен с SHA-256 из `verification-metadata.xml` репозитория.

Release DPpass подписывает только автор, и до этапа 5 он не выпускается
(`dppass/CLAUDE.md`); здесь подписи нет — это архив JVM-программы.

## Проверки

- `:cli:build` — компиляция с предупреждениями как ошибками — PASS.
- `./gradlew build ktlintCheck` в той же копии — PASS: 464 теста в 13
  модулях ядра и наборах `tests/integration`, `tests/regression`,
  `tests/fuzz`, отказов и пропусков 0; стиль ktlint — без нарушений.
- Все 15 случаев `smoke.cli` из `dppass/tools/dpverify.toml` на
  **распакованном архиве**, а не на `installDist` — 15/15 PASS.
- Повторная распаковка `.tar.gz` и `check`, `route`, `connect` на
  `examples/` — ожидаемый вывод.

Не запускались: Android Lint и `:app` (нет Google Maven и Android SDK),
тест JNI моста Xray (набор `xrayJni`, нужна библиотека Go; `dppass-cli`
Xray не использует) и, значит, `tools/hostcheck/run.sh --full`.
