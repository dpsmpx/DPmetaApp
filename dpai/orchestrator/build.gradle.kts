// Сборка системы: команды оркестратора, восстановление и сверка,
// продолжение, полуавтономный режим (этапы 13, 14, 16).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":storage"))
    api(project(":security"))
    api(project(":execution"))
    api(project(":accounts"))
    api(project(":git"))
    api(project(":contexts"))
    api(project(":tasks"))
    api(project(":sessions"))
    api(project(":verification"))
    api(project(":monitoring"))
    api(project(":notifications"))
    api(project(":connectors"))
    api(project(":analytics"))
    testImplementation(project(":testkit"))
}
