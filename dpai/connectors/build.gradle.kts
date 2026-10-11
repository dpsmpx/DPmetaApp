// Внешние сервисы: GitHub и последующие (этап 15).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core"))
    api(project(":security"))
    testImplementation(project(":testkit"))
}
