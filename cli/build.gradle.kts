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
