// Наблюдаемое состояние: сводки, временная шкала, свежесть heartbeat,
// устаревание, ресурсы, самодиагностика (этап 11).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core"))
    api(project(":accounts"))
    api(project(":execution"))
    api(project(":sessions"))
    api(project(":tasks"))
    testImplementation(project(":testkit"))
}
