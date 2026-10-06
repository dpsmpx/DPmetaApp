// Межмодульные сценарии (docs/TESTING.md, «Слои»).
plugins {
    id("dpai.test-suite")
}

dependencies {
    testImplementation(project(":orchestrator"))
    testImplementation(project(":testkit"))
}

// Замеры производительности (PerformanceTest) не делят процессор с тестами
// модулей: межмодульный набор идёт после них (docs/PERFORMANCE.md, §3).
tasks.named("test") {
    mustRunAfter(rootProject.subprojects.filter { it.path != project.path && it.path != ":tests" }.map { "${it.path}:test" })
}
