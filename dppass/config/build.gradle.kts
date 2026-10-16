// Собственный формат DPpass: разбор, миграции, нормализация, IR, проверка.
plugins {
    id("dppass.kotlin-library")
}

dependencies {
    api(project(":routing"))
}
