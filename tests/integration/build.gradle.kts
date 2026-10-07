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
// Сборка и lint модуля android тяжелее тестов и делят с замерами диск:
// p99 записи в полном прогоне вырастал до 30 мс (7 октября 2026).
tasks.named("test") {
    mustRunAfter(rootProject.subprojects.filter { it.path != project.path && it.path != ":tests" }.map { "${it.path}:test" })
    if (rootProject.findProject(":android") != null) mustRunAfter(":android:assemble", ":android:lint")
}
