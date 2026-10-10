// Execution target, процессы, ClaudeRunner и его реализации, включая
// MockRunner (этапы 3, 4).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core"))
    api(project(":security"))
    testImplementation(project(":testkit"))
}
