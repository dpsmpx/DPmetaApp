// Файлы пользователя (D-99): дерево файлов, копия папки с синхронизацией, текст и вложения.
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core"))
    api(project(":storage"))
    testImplementation(project(":testkit"))
}
