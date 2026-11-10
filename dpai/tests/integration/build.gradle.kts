// Межмодульные сценарии (docs/TESTING.md, «Слои»).
plugins {
    id("dpai.test-suite")
}

dependencies {
    testImplementation(project(":orchestrator"))
    testImplementation(project(":testkit"))
}

// mustRunAfter внутри подсистемы не упорядочивает задачи включающей сборки
// DPmetaApp. Замеры — отдельным вызовом Gradle после сборки (D-121).
tasks.named<Test>("test") {
    exclude("**/PerformanceTest*.class")
    // Обычные межмодульные сценарии сохраняют прежний порядок.
    mustRunAfter(rootProject.subprojects.filter { it.path != project.path && it.path != ":tests" }.map { "${it.path}:test" })
    if (rootProject.findProject(":android") != null) mustRunAfter(":android:assemble", ":android:lint")
}

tasks.register<Test>("performanceTest") {
    // Локальное значение: doFirst хранит Provider, а не объект скрипта
    // Gradle, который запрещён в кеше конфигурации.
    val performanceResults = layout.buildDirectory.file("perf/results.txt")
    description = "Бюджеты хоста и нагрузка после сборки (D-121)"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("com.dpsmpx.dpai.tests.integration.PerformanceTest") }
    maxParallelForks = 1
    // Замер относится к этому прогону и хосту: кеш не заменяет измерение.
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Замеры выполняются заново на текущем хосте") { true }
    doFirst { performanceResults.get().asFile.delete() }
}
