// Реестр аккаунтов, возможности, здоровье, cooldown, выбор аккаунта (этап 5).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core"))
    api(project(":security"))
    testImplementation(project(":testkit"))
}
