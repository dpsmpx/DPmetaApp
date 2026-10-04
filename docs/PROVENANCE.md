# Происхождение

Срез на **4 октября 2026**. Всё, что лежит в репозитории или попадает в
сборку, — либо написано для DPpass, либо взято из источника, записанного
здесь, с проверяемой контрольной суммой (D-20). Лицензии тех же
компонентов — `docs/legal/THIRD_PARTY_MATRIX.md`.

Сверку делает `tools/provenance/check_dependencies.py` (шаг hostcheck):
каждая запись `gradle/libs.versions.toml` — строка в §2 с той же
версией; SHA-256 дистрибутива Gradle и `gradle-wrapper.jar` — как в §3;
каждый двоичный файл репозитория — строка в §4 с его SHA-256.

## 1. Правила

- **Свой код.** Код, тексты и данные DPpass пишутся для DPpass — людьми
  и ИИ-ассистентами по правилам `CLAUDE.md`. Чужой код, тексты, значки
  и данные не копируются, даже если лицензия позволяет (D-20).
- **Неизвестное происхождение — запрет.** Не принимается: фрагмент «из
  интернета», из ответа на форуме, из чужого репозитория без записи, из
  декомпиляции; фрагмент, который «сгенерировался похожим на проект X»,
  — тоже: он удаляется и пишется заново по описанию нужного свойства.
- **Если чужой фрагмент когда-нибудь понадобится** — сначала решение
  D-NN, затем строка здесь: источник, коммит или версия, лицензия, путь
  в репозитории, SHA-256 исходного файла; уведомление — в матрице.
- **Исследования** (`docs/research/`) — собственный текст с датированными
  ссылками на источники; чужие формулировки не переносятся, результаты
  чужой обратной разработки не используются (`docs/direction/RESEARCH_SCOPE.md`, §4).
- **Данные корпуса** (`tests/data/`) — синтетические (`docs/TESTING.md`,
  «Данные корпуса»), проверяют `check_fixtures.py` и `check_secrets.py`.
- **Двоичные файлы** — только записанные в §4. Значки приложения —
  векторные XML собственного рисунка (D-01), растровых изображений нет.

## 2. Зависимости

Откуда сборка берёт каждую запись каталога. Репозитории —
только `google()`, `mavenCentral()` и (для плагинов сборки)
`gradlePluginPortal()`; другие запрещает `check_dependencies.py`.

| Компонент | Версия | Откуда | Проверено (4 октября 2026) |
|---|---|---|---|
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.11.0 | Maven Central | POM: Apache-2.0; зависимости — `kotlinx-coroutines-core-jvm` 1.11.0, `org.jetbrains:annotations` 23.0.0, `kotlin-stdlib` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.11.0 | Maven Central | POM: Apache-2.0 |
| `org.junit:junit-bom` | 6.1.3 | Maven Central | POM: EPL-2.0; задаёт версию `junit-jupiter` и `junit-platform-launcher` |
| `org.junit.jupiter:junit-jupiter` | 6.1.3 (из `junit-bom`) | Maven Central | POM: EPL-2.0 |
| `org.junit.platform:junit-platform-launcher` | 6.1.3 (из `junit-bom`) | Maven Central | POM: EPL-2.0 |
| `org.jetbrains.kotlin:kotlin-gradle-plugin` | 2.4.20 | Maven Central | POM: Apache-2.0 |
| `com.android.tools.build:gradle` | 9.4.1 | Google Maven | POM: Apache-2.0 |
| `org.jetbrains.kotlin.jvm` | 2.4.20 | Maven Central (маркер плагина) | POM маркера: Apache-2.0, ведёт на `kotlin-gradle-plugin` 2.4.20 |
| `com.android.application` | 9.4.1 | Google Maven (маркер плагина) | POM маркера: Apache-2.0, ведёт на `com.android.tools.build:gradle` 9.4.1 |
| `org.jetbrains.kotlin:kotlin-test` | 2.4.20 | Maven Central | POM: Apache-2.0; подключается как `kotlin("test")` |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.4.20 | Maven Central | POM: Apache-2.0; версию задаёт плагин Kotlin |
| `org.jetbrains:annotations` | 23.0.0 | Maven Central | POM: Apache-2.0; транзитивно (`./gradlew :cli:dependencies`) |

Версии закреплены, а содержимое артефактов Gradle пока не сверяет с
записанными суммами (нет `gradle/verification-metadata.xml`) — долг
в `TODO.md`.

## 3. Gradle

| Что | Значение |
|---|---|
| Дистрибутив | `gradle-9.8.0-bin.zip` с `services.gradle.org/distributions/` |
| SHA-256 дистрибутива | `bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c` — совпадает с опубликованным `gradle-9.8.0-bin.zip.sha256`; записан в `gradle/wrapper/gradle-wrapper.properties` (`distributionSha256Sum`), wrapper сверяет его при загрузке |
| SHA-256 `gradle-wrapper.jar` | `238e777fcddd7e34f9708186085def2abd6e08e658505b38718d79d74c21abd5` — совпадает с опубликованным `gradle-9.8.0-wrapper.jar.sha256` |
| `gradlew`, `gradlew.bat`, `gradle-wrapper.properties` | побайтно совпадают с результатом `gradle wrapper --gradle-version 9.8.0 --distribution-type bin --gradle-distribution-sha256-sum …`, выполненного дистрибутивом 9.8.0 |

Сверено 4 октября 2026. Обновление Gradle — по чек-листу D-18
(`docs/PROCESS.md`, «Зависимости»): обе суммы берутся с
`services.gradle.org` и записываются здесь в том же изменении.

## 4. Двоичные файлы

| Файл | SHA-256 | Откуда |
|---|---|---|
| `gradle/wrapper/gradle-wrapper.jar` | `238e777fcddd7e34f9708186085def2abd6e08e658505b38718d79d74c21abd5` | Gradle 9.8.0 (§3) |

Других двоичных файлов в репозитории нет. Новый — строка здесь с
источником и SHA-256 в том же изменении, иначе `check_dependencies.py`
не пропустит.

## 5. Тексты

| Файл | Происхождение |
|---|---|
| `LICENSE` | текст Apache License 2.0 с `www.apache.org/licenses/LICENSE-2.0.txt` (без начальной пустой строки; иных отличий нет — сверено 4 октября 2026) |
| Остальные документы, комментарии, сообщения диагностики | написаны для DPpass |

## 6. Внешние исходники для исследований

Повторяемые описи (`tools/research/happ_inventory.sh`) клонируют чужие
публичные репозитории в `third_party/` — каталог вне репозитория
(`.gitignore`). Оттуда в DPpass ничего не копируется: опись печатает
состав, выводы записываются своими словами в `docs/research/` с датой.
