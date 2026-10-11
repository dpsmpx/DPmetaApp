// ContextBundle, манифест источников, версии, отбор, сжатие, handoff (этап 8).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core"))
    api(project(":security"))
    testImplementation(project(":testkit"))
}
