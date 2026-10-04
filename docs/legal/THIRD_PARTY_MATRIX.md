# Матрица лицензий сторонних компонентов

Срез на **4 октября 2026**. Каждый сторонний компонент, который
попадает в приложение, в `cli`, в сборку или в тесты, — строка
здесь с лицензией и статусом (D-20). Что сборка реально берёт, сверяет
`tools/provenance/check_dependencies.py`: каждая запись
`gradle/libs.versions.toml` должна иметь строку с **той же версией**,
иначе hostcheck падает. Откуда взят компонент и как проверен —
`docs/PROVENANCE.md`.

Статусы:

- **разрешён** — используется; версия закреплена (каталог, wrapper).
- **запланирован** — решение принято, версия ещё не выбрана; в сборку
  не попадает, пока строка не переведена в «разрешён» по чек-листу D-18
  (`docs/PROCESS.md`, «Зависимости»).
- **рассматривается** — вариант для отложенного решения
  (`docs/direction/DECISIONS.md`, «Отложено»); не используется до решения.
- **запрещён** — не используется ни в каком виде; основание — в строке.

Лицензии библиотек сверены по POM опубликованной версии
(Maven Central, Google Maven), Gradle — по `LICENSE` дистрибутива; все —
4 октября 2026.

## 1. Разрешённые

### 1.1. Приложение и `cli`

То, что попадает в APK и в дистрибутив `cli`. Уведомления этих
компонентов показываются в приложении с этапа 5 (`docs/direction/ROADMAP/05-release.md`).

| Компонент | Версия | Лицензия | Куда | Статус | Зачем |
|---|---|---|---|---|---|
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` (JVM-артефакт — `kotlinx-coroutines-core-jvm`; `kotlinx-coroutines-bom` — только выравнивание версий) | 1.11.0 | Apache-2.0 | APK, `cli`, ядро (`connection`) | разрешён | `suspend` на границах ядра, проб, здоровья, загрузки (`docs/ARCHITECTURE.md`, §12) |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.4.20 | Apache-2.0 | APK, `cli`, все модули | разрешён | стандартная библиотека Kotlin; версию задаёт плагин Kotlin, не каталог |
| `org.jetbrains:annotations` | 23.0.0 | Apache-2.0 | APK, `cli` (транзитивно от корутин и `kotlin-stdlib`) | разрешён | аннотации `@NotNull`/`@Nullable`; исполняемого кода нет |

### 1.2. Тесты

В APK и `cli` не попадает: только `testImplementation`/`testRuntimeOnly`.

| Компонент | Версия | Лицензия | Куда | Статус | Зачем |
|---|---|---|---|---|---|
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.11.0 | Apache-2.0 | тесты `connection` | разрешён | виртуальное время корутин в тестах |
| `org.jetbrains.kotlin:kotlin-test` | 2.4.20 | Apache-2.0 | тесты всех модулей | разрешён | утверждения; подключается как `kotlin("test")`, версия = версии Kotlin |
| `org.junit:junit-bom` | 6.1.3 | EPL-2.0 | тесты | разрешён | только выравнивает версии JUnit |
| `org.junit.jupiter:junit-jupiter` | 6.1.3 | EPL-2.0 | тесты | разрешён | JUnit Jupiter: тесты, динамические тесты корпуса |
| `org.junit.platform:junit-platform-launcher` | 6.1.3 | EPL-2.0 | тесты | разрешён | запуск JUnit Platform из Gradle |

EPL-2.0 — слабый копилефт на уровне модуля; тестовые библиотеки не
распространяются вместе с DPpass, обязательств для APK нет.

### 1.3. Сборка

В APK не попадает. Транзитивные зависимости инструментов сборки (у AGP
их десятки) отдельными строками не записываются: они не распространяются,
а их лицензии — в POM самих инструментов. Отдельная строка нужна тому,
что попадает в APK или в `cli`.

| Компонент | Версия | Лицензия | Куда | Статус | Зачем |
|---|---|---|---|---|---|
| `gradle` (Gradle Build Tool, через wrapper) | 9.8.0 | Apache-2.0 | сборка | разрешён | сборка; SHA-256 дистрибутива и wrapper — `docs/PROVENANCE.md` |
| `org.jetbrains.kotlin:kotlin-gradle-plugin` | 2.4.20 | Apache-2.0 | сборка (`build-logic`) | разрешён | компилятор Kotlin; convention-плагины |
| `org.jetbrains.kotlin.jvm` | 2.4.20 | Apache-2.0 | сборка | разрешён | id плагина Kotlin/JVM (запись `[plugins]` каталога; реализация — `kotlin-gradle-plugin` той же версии) |
| `com.android.tools.build:gradle` | 9.4.1 | Apache-2.0 | сборка (`build-logic`) | разрешён | Android Gradle Plugin: сборка APK, D8, Android Lint (и для JVM-модулей) |
| `com.android.application` | 9.4.1 | Apache-2.0 | сборка | разрешён | id плагина приложения (запись `[plugins]` каталога; реализация — AGP той же версии) |

### 1.4. CI

Нет. GitHub Actions не используется и не запускается (D-23), сторонних
действий CI в цепочке поставки нет.

### 1.5. Инструменты вне репозитория

Ставятся на машину разработчика, в репозиторий и в APK не
попадают; версии — `docs/PROCESS.md`, «Проверки».

| Инструмент | Лицензия | Где |
|---|---|---|
| JDK 21 (например, OpenJDK или Eclipse Temurin) | GPL-2.0 с Classpath Exception | сборка, тесты |
| Android SDK (платформа 36, build-tools) | Android Software Development Kit License Agreement | сборка `app`, `check_android_api.py`; не распространяется |
| Python 3.11+ | PSF-2.0 | скрипты проверок `tools/` |

## 2. Запланированные

| Компонент | Версия | Лицензия | Куда | Статус | Основание |
|---|---|---|---|---|---|
| `XTLS/Xray-core` (только upstream, не форки) | выбирается на этапе 2 по D-18 | MPL-2.0 | APK (нативная библиотека) | запланирован | D-03, `docs/research/BACKEND_STRATEGY.md` |

Обязательства MPL-2.0 — файловые: изменённые файлы самого Xray
открываются под MPL-2.0, код DPpass не затрагивается; в приложении —
уведомление и где взять исходники (этап 5).

## 3. Рассматриваемые

Варианты этапа 2 (`docs/direction/ROADMAP/02-xray-backend.md`); до
решения не используются.

| Компонент | Лицензия | Для чего | Статус | Основание |
|---|---|---|---|---|
| `XTLS/libXray` | MIT | обёртка gomobile над Xray (или своя обёртка) | рассматривается | этап 2, работа 4; `docs/research/BACKEND_STRATEGY.md` |
| `heiher/hev-socks5-tunnel` | MIT | мост fd → ядро против TUN inbound Xray | рассматривается | отложенное решение «Мост fd → ядро»; `docs/research/ANDROID_NETWORK_MODEL.md` |

## 4. Запрещённые

| Что | Лицензия | Почему нельзя |
|---|---|---|
| sing-box, libbox (`SagerNet/sing-box`) в одном процессе с приложением | GPL-3.0-or-later с условием об имени | весь APK стал бы производным под GPL (D-20); второе ядро — отложенное решение |
| Код клиентов v2rayNG, NekoBox, sing-box for Android, Hiddify, Amnezia VPN | GPL-3.0 (у Hiddify — с дополнительными условиями) | DPpass пишет свой код (D-20); сравнение — только как факт (`docs/research/COMPETITOR_ARCHITECTURE.md`) |
| Собственные репозитории и документация Happ (`Happ-proxy/*`, `HappDev/happ_su`, `www.happ.su`) | нет лицензии — все права защищены | `docs/research/HAPP_SOURCE_AUDIT.md`, §0 и §4 |
| Форк `Happ-proxy/Xray-core` как источник Xray | MPL-2.0 (upstream) | источник Xray — только `XTLS/Xray-core` (`docs/research/HAPP_SOURCE_AUDIT.md`, §4) |
| Тексты `XTLS/Xray-docs-next` и его форков | CC-BY-SA-4.0 | тексты не копируются (D-20) |
| Эталонная реализация AnyTLS (`anytls-go`) | нет лицензии | `docs/research/TRANSPORT_COMPARISON.md` |
| Результаты обратной разработки закрытых клиентов (Happwner и др.) | — | `docs/direction/RESEARCH_SCOPE.md`, §4 |
| SDK аналитики, отчётов о падениях, push-уведомлений (Firebase, Sentry и подобные) | — | без телеметрии и идентификаторов (D-12) |

## 5. Как меняется матрица

- Новая библиотека, плагин или версия — строка здесь и в
  `docs/PROVENANCE.md` в том же изменении, что и каталог; без них
  `check_dependencies.py` не пропустит сборку.
- Перевод из «запланирован» или «рассматривается» в «разрешён» — только
  по чек-листу D-18 (`docs/PROCESS.md`, «Зависимости»), для ядра — с
  `capabilityDiff`.
- Смена лицензии компонента при обновлении — повод остановиться и
  пересмотреть строку, а не обновить молча.
