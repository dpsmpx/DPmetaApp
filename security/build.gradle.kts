// Уровни операций и одобрения с привязкой к хешу предложения, границы
// секретности контекста, ссылки на учётные данные (этапы 5, 18).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core"))
    testImplementation(project(":testkit"))
}
