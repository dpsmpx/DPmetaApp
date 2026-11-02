// dpmeta-cli (D-29; ТЗ, §6.2): синхронизация с корпуса, из клонов или вживую,
// состояние, история чисел, дым. Оболочка над модулями DPmetaApp: печать — здесь.
plugins {
    id("dpai.kotlin-application")
}

dependencies {
    implementation(project(":core:sync"))
    implementation(project(":core:metrics"))
}

application {
    applicationName = "dpmeta-cli"
    mainClass.set("com.dpsmpx.dpmeta.cli.MainKt")
}

tasks.withType<Test>().configureEach {
    val corpus = rootProject.layout.projectDirectory.dir("tests/corpus/ecosystem")
    inputs.dir(corpus).withPropertyName("corpus")
    systemProperty("corpus.ecosystem", corpus.asFile.absolutePath)
}
