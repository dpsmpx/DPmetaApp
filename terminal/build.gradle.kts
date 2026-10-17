// Эмулятор xterm своего терминала: экран, разбор последовательностей, ввод (D-57).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core"))
    testImplementation(project(":testkit"))
}
