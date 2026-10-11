// Категории, дедупликация, агрегация, cooldown, deep links (этап 12).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core"))
    testImplementation(project(":testkit"))
}
