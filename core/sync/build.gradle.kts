// План и прогон синхронизации (ТЗ, §6.4): реестр → источники → наблюдения.
// О расписании не знает: запускают оболочки (`:cli`, позже — WorkManager в `:app`).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core:formats"))
    api(project(":core:github"))
    api(project(":core:git"))
    api("com.dpsmpx.dpai:storage")
    // Тест чтения клонов (D-65): локальные репозитории вместо GitHub.
    testImplementation(libs.jgit)
}
