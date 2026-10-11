// Реестр сессий, жизненный цикл, heartbeat, поток событий, пауза,
// продолжение, остановка, повтор (этап 6).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core"))
    api(project(":accounts"))
    api(project(":execution"))
    testImplementation(project(":testkit"))
}
