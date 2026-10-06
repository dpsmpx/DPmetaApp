// Оболочка JVM: команда dpai (doctor, сценарии на MockRunner, режим без
// интерфейса).
plugins {
    id("dpai.kotlin-application")
}

dependencies {
    implementation(project(":orchestrator"))
    testImplementation(project(":testkit"))
}

application {
    applicationName = "dpai"
    mainClass.set("com.dpsmpx.dpai.cli.MainKt")
}

// Версия для `dpai version` — из манифеста jar (gradle.properties, `version`).
tasks.jar {
    manifest { attributes("Implementation-Version" to project.version.toString()) }
}
