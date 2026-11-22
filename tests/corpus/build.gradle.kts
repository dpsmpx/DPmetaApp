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
    testImplementation(project(":core:notify"))
}

tasks.withType<Test>().configureEach {
    val samples = layout.projectDirectory.dir("samples")
    inputs.dir(samples).withPropertyName("samples")
    systemProperty("corpus.samples", samples.asFile.absolutePath)
    // Манифест dpai.ecosystem из реестра пульта (DP_AI_Orcestrator:D-114): вход и вывод DPmeta:tools/manifest.py.
    val manifest = layout.projectDirectory.dir("manifest")
    inputs.dir(manifest).withPropertyName("manifest")
    systemProperty("corpus.manifest", manifest.asFile.absolutePath)
    val ecosystem = layout.projectDirectory.dir("ecosystem")
    inputs.dir(ecosystem).withPropertyName("ecosystem")
    systemProperty("corpus.ecosystem", ecosystem.asFile.absolutePath)
    // Свои документы DPmetaApp — тоже образцы: разбор не должен отстать от них.
    val repo = rootProject.layout.projectDirectory
    for (f in listOf("docs/direction/DECISIONS.md", "TODO.md", "ROADMAP/ROADMAP.md", "README.md", "config/commands.toml", "config/events.toml", "config/alerts.toml", "docs/direction/TECHNICAL_TASK.md")) {
        inputs.file(repo.file(f)).withPropertyName("own:$f")
    }
    inputs.dir(repo.dir("config/briefs")).withPropertyName("own:config/briefs")
    systemProperty("corpus.repo", repo.asFile.absolutePath)
}
