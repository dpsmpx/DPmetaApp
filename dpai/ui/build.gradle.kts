// Представление без платформы: состояния экранов, форматирование,
// разбор deep links. Android только рисует (D-02).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":orchestrator"))
    api(project(":lsp"))
    testImplementation(project(":testkit"))
    testImplementation(project(":files"))
}
