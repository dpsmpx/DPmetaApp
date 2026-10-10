// Общие средства тестов: property-тесты с зерном, генераторы, фикстуры.
// Подключается только в тестовые конфигурации (docs/ARCHITECTURE.md, §2).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core"))
}
