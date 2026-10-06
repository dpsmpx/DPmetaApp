// Учёт использования провайдера и телеметрия производительности
// приложения — раздельно (этапы 11, 17).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core"))
    testImplementation(project(":testkit"))
}
