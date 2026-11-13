// Клиент языковых служб редактора (D-137): JSON-RPC и LSP поверх потоков, правки текста.
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core"))
    testImplementation(project(":testkit"))
    // Живой тест с настоящим clangd запускает процесс — только через execution (D-03).
    testImplementation(project(":execution"))
}
