// EcosystemProvider и адаптер DPmeta; без DPmeta оркестратор работает (этап 15).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core"))
    testImplementation(project(":testkit"))
}
