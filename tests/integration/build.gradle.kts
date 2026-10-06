// Межмодульные сценарии (docs/TESTING.md, «Слои»).
plugins {
    id("dpai.test-suite")
}

dependencies {
    testImplementation(project(":orchestrator"))
    testImplementation(project(":testkit"))
}
