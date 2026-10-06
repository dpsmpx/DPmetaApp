// Репозиторий, ветки, worktree и их владельцы, diff, конфликты,
// безопасное слияние (этап 9).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core"))
    api(project(":security"))
    testImplementation(project(":testkit"))
}
