// Синтетический корпус форматов и межмодульные тесты (ТЗ, §6.2, §10): образцы —
// samples/<формат>/passes (разбираются) и catches (ловятся с кодом и местом).
// Содержимое закрытых репозиториев сюда не копируется (DPmeta:C-05).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    testImplementation(project(":core:formats"))
    testImplementation(project(":core:sync"))
    testImplementation(project(":core:metrics"))
}

tasks.withType<Test>().configureEach {
    val samples = layout.projectDirectory.dir("samples")
    inputs.dir(samples).withPropertyName("samples")
    systemProperty("corpus.samples", samples.asFile.absolutePath)
    val ecosystem = layout.projectDirectory.dir("ecosystem")
    inputs.dir(ecosystem).withPropertyName("ecosystem")
    systemProperty("corpus.ecosystem", ecosystem.asFile.absolutePath)
    // Свои документы DPmetaApp — тоже образцы: разбор не должен отстать от них.
    val repo = rootProject.layout.projectDirectory
    for (f in listOf("dpmeta/tools/ecosystem.toml", "docs/direction/DECISIONS.md", "TODO.md", "ROADMAP/ROADMAP.md", "README.md")) {
        inputs.file(repo.file(f)).withPropertyName("own:$f")
    }
    systemProperty("corpus.repo", repo.asFile.absolutePath)
}
